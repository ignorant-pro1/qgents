package qg.qgent.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.ApplicationEventPublisher;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.mapper.AgentMapper;
import qg.qgent.agent.service.AgentService;
import qg.qgent.attachment.service.AttachmentService;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.chat.mapper.GroupAgentMapper;
import qg.qgent.chat.mapper.GroupReadStateMapper;
import qg.qgent.chat.mapper.GroupUserMapper;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.service.GroupService;
import qg.qgent.chat.service.MessageService;
import qg.qgent.chat.service.MessageViews;
import qg.qgent.memory.config.ProjectMemoryDiscoveryProperties;
import qg.qgent.memory.entity.GroupMemoryStateEntity;
import qg.qgent.memory.mapper.GroupMemoryStateMapper;
import qg.qgent.notification.service.NotificationService;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.mapper.ProjectMemberMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.support.TestIds;
import qg.qgent.user.mapper.UserGroupPreferenceMapper;
import qg.qgent.user.mapper.UserMapper;

/** 项目记忆发现：只从默认群记忆提炼、以普通消息发出建议、重复扫描、失败重试与权限检查。 */
class ProjectMemoryDiscoveryServiceTest {

    private static final int MAX_CHARS = 2000;
    private static final int INTERVAL_MINUTES = 1440;
    private static final int RETRY_DELAY_MINUTES = 60;
    private static final int MAX_ATTEMPTS = 3;
    private static final int BATCH_SIZE = 10;

    @TempDir Path root;

    private final GroupMemoryStateMapper states = mock(GroupMemoryStateMapper.class);
    private final ChatGroupMapper chatGroups = mock(ChatGroupMapper.class);
    private final MessageMapper messageMapper = mock(MessageMapper.class);
    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final GroupAgentMapper groupAgents = mock(GroupAgentMapper.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final ChatModel model = mock(ChatModel.class);
    private final MemoryDocument document = new MemoryDocument(MAX_CHARS);

    private GroupMemoryService groupMemories;
    private ProjectMemoryService projectMemories;
    private MessageService chat;
    private AgentService agents;
    private ProjectMemoryDiscoveryService service;

    @BeforeEach
    void setUp() {
        initializeMybatisMetadata();
        ProjectDirectoryService directories =
                new ProjectDirectoryService(root.toString(), root.toString());
        EventService events = mock(EventService.class);
        agents = new AgentService(agentMapper);
        GroupService chatGroupsService =
                new GroupService(
                        chatGroups,
                        mock(ProjectMemberMapper.class),
                        mock(GroupUserMapper.class),
                        messageMapper,
                        mock(GroupReadStateMapper.class),
                        mock(UserGroupPreferenceMapper.class),
                        access,
                        events,
                        groupAgents,
                        agents);
        chat =
                new MessageService(
                        messageMapper,
                        chatGroups,
                        mock(UserMapper.class),
                        chatGroupsService,
                        new ObjectMapper(),
                        events,
                        mock(NotificationService.class),
                        new MessageViews(
                                mock(UserMapper.class),
                                agentMapper,
                                mock(AttachmentService.class),
                                new ObjectMapper()),
                        eventPublisher);
        groupMemories = new GroupMemoryService(access, chatGroupsService, directories, document);
        projectMemories = new ProjectMemoryService(access, projects, directories, events, document);
        service =
                new ProjectMemoryDiscoveryService(
                        states,
                        chatGroups,
                        groupMemories,
                        projectMemories,
                        chat,
                        agents,
                        model,
                        Runnable::run,
                        properties(),
                        document);
        when(messageMapper.nextSequence(any())).thenReturn(1L);
        when(messageMapper.insert(any(MessageEntity.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(0, MessageEntity.class).setId(TestIds.next());
                    return 1;
                });
    }

    /** MessageService 的 doSend 用 MyBatis-Plus 构造更新语句，需要先注册实体元数据。 */
    private static void initializeMybatisMetadata() {
        for (Class<?> entity : List.of(ChatGroupEntity.class, MessageEntity.class)) {
            if (TableInfoHelper.getTableInfo(entity) == null) {
                TableInfoHelper.initTableInfo(
                        new MapperBuilderAssistant(
                                new MybatisConfiguration(), "ProjectMemoryDiscoveryServiceTest"),
                        entity);
            }
        }
    }

    @Test
    void discoveredSuggestionIsPostedByTheDefaultAgentWithoutWritingProjectMemory() {
        Long project = TestIds.next(), group = TestIds.next(), agent = TestIds.next();
        groupMemories.write(project, group, "- 议题：登录方式\n- 结论：先做邮箱登录");
        defaultGroupWithAgent(project, group, agent);
        when(model.call(any(Prompt.class))).thenReturn(reply("- 新增：登录方式先用邮箱登录"));
        claims(group);

        service.discover(project, group);

        // 群记忆是唯一的提炼来源：模型拿到的是群记忆正文和当前项目记忆，而不是原始聊天
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions())
                .anySatisfy(instruction -> assertThat(instruction.getText()).contains("先做邮箱登录"));
        // 建议由默认 Agent 在默认群以普通文本发出：走真实发送路径，发送者是 Agent 而非用户
        MessageEntity sent = insertedMessage();
        assertThat(sent.getSenderType()).isEqualTo("AGENT");
        assertThat(sent.getSenderAgentId()).isEqualTo(agent);
        assertThat(sent.getAuthorUserId()).isNull();
        assertThat(sent.getMessageType()).isEqualTo("TEXT");
        assertThat(sent.getContent()).contains("项目管理员", "- 新增：登录方式先用邮箱登录");
        // 建议本身不生效：写入要等管理员在对话里确认
        assertThat(projectMemories.read(project).getContent()).isEmpty();
        verify(states).finishDiscovery(eq(group), any(), any(), any());
    }

    @Test
    void restrictedGroupContentNeverReachesTheModelOrDefaultGroup() {
        Long project = TestIds.next(), group = TestIds.next();
        groupMemories.write(project, group, "- 受限结论：调整了报价");
        when(chatGroups.selectById(group)).thenReturn(group(group, project, false));
        when(states.selectById(group)).thenReturn(state(project, group));
        claims(group);

        service.discover(project, group);

        verifyNoInteractions(model);
        verify(messageMapper, never()).insert(any(MessageEntity.class));
        assertThat(projectMemories.read(project).getContent()).isEmpty();
    }

    @Test
    void suggestionIsNotSentWhenTheDefaultAgentIsNotInTheGroup() {
        Long project = TestIds.next(), group = TestIds.next(), agent = TestIds.next();
        groupMemories.write(project, group, "- 结论：先做邮箱登录");
        when(chatGroups.selectById(group)).thenReturn(group(group, project, true));
        when(agentMapper.selectDefaultAgent(project)).thenReturn(agent(agent));
        when(agentMapper.selectBatchIds(any())).thenReturn(List.of(agent(agent)));
        when(agentMapper.selectById(agent)).thenReturn(agent(agent));
        when(chatGroups.selectOne(any())).thenReturn(group(group, project, true));
        when(groupAgents.selectAgentIds(group)).thenReturn(List.of());
        when(states.selectById(group)).thenReturn(state(project, group));
        claims(group);
        when(model.call(any(Prompt.class))).thenReturn(reply("- 新增：登录方式先用邮箱登录"));

        service.discover(project, group);

        verify(messageMapper, never()).insert(any(MessageEntity.class));
        verify(states).recordDiscoveryFailure(eq(group), contains("Agent 未加入当前群聊"), any(), any());
        verify(states, never()).finishDiscovery(any(), any(), any(), any());
    }

    @Test
    void sameGroupMemoryVersionIsNotDiscoveredTwice() {
        Long project = TestIds.next(), group = TestIds.next();
        groupMemories.write(project, group, "- 结论：先做邮箱登录");
        GroupMemoryStateEntity state = state(project, group);
        state.setDiscoveredVersion(document.version("- 结论：先做邮箱登录"));
        when(chatGroups.selectById(group)).thenReturn(group(group, project, true));
        when(states.selectById(group)).thenReturn(state);
        claims(group);

        service.discover(project, group);

        verifyNoInteractions(model);
        verify(messageMapper, never()).insert(any(MessageEntity.class));
        verify(states).finishDiscovery(eq(group), any(), any(), any());
    }

    @Test
    void nothingToDiscoverRecordsTheVersionWithoutPosting() {
        Long project = TestIds.next(), group = TestIds.next();
        groupMemories.write(project, group, "- 闲聊：明天吃火锅");
        when(chatGroups.selectById(group)).thenReturn(group(group, project, true));
        when(states.selectById(group)).thenReturn(state(project, group));
        claims(group);
        when(model.call(any(Prompt.class))).thenReturn(reply("NONE."));

        service.discover(project, group);

        verify(messageMapper, never()).insert(any(MessageEntity.class));
        verify(states).finishDiscovery(eq(group), eq(document.version("- 闲聊：明天吃火锅")), any(), any());
    }

    @Test
    void failureIsRecordedForRetryAndTheNextAttemptRecovers() {
        Long project = TestIds.next(), group = TestIds.next(), agent = TestIds.next();
        groupMemories.write(project, group, "- 结论：先做邮箱登录");
        defaultGroupWithAgent(project, group, agent);
        when(states.selectById(group)).thenReturn(state(project, group));
        claims(group);
        when(model.call(any(Prompt.class)))
                .thenThrow(new IllegalStateException("模型调用超时"))
                .thenReturn(reply("- 新增：登录方式先用邮箱登录"));

        service.discover(project, group);

        verify(messageMapper, never()).insert(any(MessageEntity.class));
        verify(states).recordDiscoveryFailure(eq(group), contains("模型调用超时"), any(), any());
        verify(states, never()).finishDiscovery(any(), any(), any(), any());
        assertThat(projectMemories.read(project).getContent()).isEmpty();

        // 重试成功：发出建议并推进已发现的群记忆版本
        service.discover(project, group);

        assertThat(insertedMessage().getSenderAgentId()).isEqualTo(agent);
        verify(states)
                .finishDiscovery(eq(group), eq(document.version("- 结论：先做邮箱登录")), any(), any());
    }

    @Test
    void skipsWhenTheSlotIsNotClaimable() {
        Long project = TestIds.next(), group = TestIds.next();
        when(states.claimDiscovery(eq(group), any(), any())).thenReturn(0);

        service.discover(project, group);

        verify(states, never()).selectById(any());
        verifyNoInteractions(model);
        verify(messageMapper, never()).insert(any(MessageEntity.class));
    }

    @Test
    void scanSubmitsDueGroupsToTheBackgroundExecutorInsteadOfCallingTheModelInline() {
        Long project = TestIds.next(), group = TestIds.next(), agent = TestIds.next();
        groupMemories.write(project, group, "- 结论：先做邮箱登录");
        defaultGroupWithAgent(project, group, agent);
        when(states.selectById(group)).thenReturn(state(project, group));
        claims(group);
        when(model.call(any(Prompt.class))).thenReturn(reply("- 新增：登录方式先用邮箱登录"));
        when(states.selectDiscoveryCandidates(any(), eq(MAX_ATTEMPTS), eq(BATCH_SIZE)))
                .thenReturn(List.of(state(project, group)));
        List<Runnable> queued = new ArrayList<>();
        ProjectMemoryDiscoveryService scheduled =
                new ProjectMemoryDiscoveryService(
                        states,
                        chatGroups,
                        groupMemories,
                        projectMemories,
                        chat,
                        agents,
                        model,
                        queued::add,
                        properties(),
                        document);

        scheduled.scan();

        verifyNoInteractions(model);
        assertThat(queued).hasSize(1);
        queued.getFirst().run();
        verify(model).call(any(Prompt.class));
        assertThat(insertedMessage().getSenderAgentId()).isEqualTo(agent);
    }

    @Test
    void fullQueueSkipsThisRoundWithoutFailingTheScan() {
        Long project = TestIds.next(), group = TestIds.next();
        when(states.selectDiscoveryCandidates(any(), anyInt(), anyInt()))
                .thenReturn(List.of(state(project, group)));
        ProjectMemoryDiscoveryService saturated =
                new ProjectMemoryDiscoveryService(
                        states,
                        chatGroups,
                        groupMemories,
                        projectMemories,
                        chat,
                        agents,
                        model,
                        task -> {
                            throw new RejectedExecutionException("队列已满");
                        },
                        properties(),
                        document);

        saturated.scan();

        verify(states, never()).claimDiscovery(any(), any(), any());
        verifyNoInteractions(model);
    }

    private void defaultGroupWithAgent(Long project, Long group, Long agent) {
        when(chatGroups.selectById(group)).thenReturn(group(group, project, true));
        when(chatGroups.selectOne(any())).thenReturn(group(group, project, true));
        when(agentMapper.selectDefaultAgent(project)).thenReturn(agent(agent));
        when(agentMapper.selectBatchIds(any())).thenReturn(List.of(agent(agent)));
        when(groupAgents.selectAgentIds(group)).thenReturn(List.of(agent));
        when(states.selectById(group)).thenReturn(state(project, group));
    }

    private void claims(Long group) {
        when(states.claimDiscovery(eq(group), any(), any())).thenReturn(1);
        when(states.finishDiscovery(eq(group), any(), any(), any())).thenReturn(1);
    }

    private MessageEntity insertedMessage() {
        ArgumentCaptor<MessageEntity> captor = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageMapper).insert(captor.capture());
        return captor.getValue();
    }

    private ProjectMemoryDiscoveryProperties properties() {
        ProjectMemoryDiscoveryProperties properties = new ProjectMemoryDiscoveryProperties();
        properties.setIntervalMinutes(INTERVAL_MINUTES);
        properties.setRetryDelayMinutes(RETRY_DELAY_MINUTES);
        properties.setMaxAttempts(MAX_ATTEMPTS);
        properties.setBatchSize(BATCH_SIZE);
        return properties;
    }

    private GroupMemoryStateEntity state(Long projectId, Long groupId) {
        GroupMemoryStateEntity state = new GroupMemoryStateEntity();
        state.setProjectId(projectId);
        state.setGroupId(groupId);
        state.setDiscoveryAttemptCount(0);
        return state;
    }

    private ChatGroupEntity group(Long groupId, Long projectId, boolean defaultGroup) {
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        group.setDefaultGroup(defaultGroup);
        return group;
    }

    private AgentEntity agent(Long agentId) {
        AgentEntity agent = new AgentEntity();
        agent.setId(agentId);
        agent.setName("Qgent");
        agent.setDefaultAgent(true);
        return agent;
    }

    private ChatResponse reply(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
