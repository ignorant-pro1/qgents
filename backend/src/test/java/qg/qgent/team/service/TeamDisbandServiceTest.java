package qg.qgent.team.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import qg.qgent.attachment.entity.AttachmentEntity;
import qg.qgent.attachment.mapper.AttachmentMapper;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.harness.entity.AgentRunEntity;
import qg.qgent.harness.mapper.AgentRunMapper;
import qg.qgent.notification.entity.NotificationEntity;
import qg.qgent.notification.mapper.NotificationMapper;
import qg.qgent.project.entity.ProjectEntity;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.realtime.entity.EventEntity;
import qg.qgent.realtime.mapper.EventMapper;
import qg.qgent.sandbox.service.SandboxService;
import qg.qgent.skill.entity.SkillEntity;
import qg.qgent.skill.mapper.SkillMapper;
import qg.qgent.support.TestIds;
import qg.qgent.team.mapper.TeamMapper;
import qg.qgent.workspace.entity.WorkspaceEntity;
import qg.qgent.workspace.mapper.WorkspaceMapper;

class TeamDisbandServiceTest {

    /**
     * 纯单元测试未启动 MyBatis/Spring，MyBatis-Plus 的 lambda 包装器在裸 JVM 下
     * 无法通过懒初始化拿到实体列缓存；这里显式注册全部涉及实体的 TableInfo，
     * 与生产环境由 MyBatis 启动时注册的行为保持一致，保证包装器构造确定性。
     */
    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        for (Class<?> entity :
                List.of(
                        ProjectEntity.class,
                        NotificationEntity.class,
                        EventEntity.class,
                        ChatGroupEntity.class,
                        MessageEntity.class,
                        AgentRunEntity.class,
                        qg.qgent.agent.entity.AgentEntity.class,
                        WorkspaceEntity.class,
                        SkillEntity.class,
                        AttachmentEntity.class,
                        ChatGroupEntity.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final NotificationMapper notificationMapper = mock(NotificationMapper.class);
    private final EventMapper eventMapper = mock(EventMapper.class);
    private final ChatGroupMapper chatGroupMapper = mock(ChatGroupMapper.class);
    private final MessageMapper messageMapper = mock(MessageMapper.class);
    private final AgentRunMapper agentRuns = mock(AgentRunMapper.class);
    private final qg.qgent.agent.mapper.AgentMapper agentMapper =
            mock(qg.qgent.agent.mapper.AgentMapper.class);
    private final WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
    private final SkillMapper skillMapper = mock(SkillMapper.class);
    private final AttachmentMapper attachmentMapper = mock(AttachmentMapper.class);
    private final TeamMapper teamMapper = mock(TeamMapper.class);
    private final SandboxService sandboxes = mock(SandboxService.class);

    @TempDir Path projectDataRoot;

    private TeamDisbandService service;

    @BeforeEach
    void setUp() {
        service =
                new TeamDisbandService(
                        projectMapper,
                        notificationMapper,
                        eventMapper,
                        chatGroupMapper,
                        messageMapper,
                        agentRuns,
                        agentMapper,
                        workspaceMapper,
                        skillMapper,
                        attachmentMapper,
                        teamMapper,
                        new ProjectDirectoryService(
                                projectDataRoot.toString(), projectDataRoot.toString()),
                        sandboxes,
                        mock(qg.qgent.common.storage.AvatarStorageService.class));
    }

    @Test
    void deleteTeamRefusesWhileSandboxIsStillRunning() {
        Long teamId = TestIds.next();
        Long projectId = TestIds.next();
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        when(projectMapper.selectList(any())).thenReturn(List.of(project));
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(TestIds.next());
        workspace.setProjectId(projectId);
        workspace.setIsDefault(true);
        when(workspaceMapper.selectList(any())).thenReturn(List.of(workspace));
        when(sandboxes.isWorkspaceInUse(projectId + "/workspaces/default")).thenReturn(true);

        assertThatThrownBy(() -> service.deleteTeam(teamId, null))
                .isInstanceOf(qg.qgent.common.web.ApiException.class)
                .hasMessageContaining("沙箱");
        verify(teamMapper, never()).deleteById(teamId);
    }

    @Test
    void deleteTeamCleansProjectsInDependencyOrder() throws IOException {
        Long teamId = TestIds.next();
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();

        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(teamId);
        when(projectMapper.selectList(any())).thenReturn(List.of(project));

        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        when(chatGroupMapper.selectList(any())).thenReturn(List.of(group));
        when(workspaceMapper.selectList(any())).thenReturn(List.of());

        Path projectDirectory = projectDataRoot.resolve(projectId.toString());
        Files.createDirectories(projectDirectory);
        Files.writeString(projectDirectory.resolve("memory.md"), "# 项目约定");

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.deleteTeam(teamId, null);
            // 磁盘清理排在事务提交之后，回滚时不会丢文件
            assertThat(projectDirectory).exists();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertThat(projectDirectory).doesNotExist();

        // 关键依赖顺序：子表先于父表，引用行先于被引用行；agents 在项目级引用清理后最后删除。
        var order =
                inOrder(
                        notificationMapper,
                        eventMapper,
                        messageMapper,
                        agentRuns,
                        agentMapper,
                        workspaceMapper,
                        skillMapper,
                        attachmentMapper,
                        chatGroupMapper,
                        agentMapper,
                        projectMapper,
                        teamMapper);
        order.verify(notificationMapper).delete(any());
        order.verify(eventMapper).delete(any());
        order.verify(agentRuns).delete(any());
        order.verify(messageMapper).deleteUnreferencedMessages(any());
        order.verify(workspaceMapper).delete(any());
        order.verify(skillMapper).delete(any());
        order.verify(attachmentMapper).delete(any());
        order.verify(chatGroupMapper).delete(any());
        order.verify(agentMapper).delete(any());
        order.verify(projectMapper).deleteById(projectId);
        order.verify(teamMapper).deleteById(teamId);
    }
}
