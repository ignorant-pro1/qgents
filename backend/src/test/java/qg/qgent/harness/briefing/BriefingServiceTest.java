package qg.qgent.harness.briefing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.chat.dto.ContextMessage;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.service.ChatHistoryService;
import qg.qgent.chat.service.GroupService;
import qg.qgent.harness.briefing.BriefingService.Scope;
import qg.qgent.memory.dto.GroupMemoryResponse;
import qg.qgent.memory.dto.ProjectMemoryResponse;
import qg.qgent.memory.service.AgentMemoryService;
import qg.qgent.memory.service.GroupMemoryService;
import qg.qgent.memory.service.ProjectMemoryService;
import qg.qgent.skill.dto.ContextSkill;
import qg.qgent.skill.service.SkillService;
import qg.qgent.support.TestIds;

/** 主运行与子 Agent 看到同一套标签正文，区别只在群信息与近期消息。 */
class BriefingServiceTest {

    private final GroupService groups = mock(GroupService.class);
    private final ChatHistoryService history = mock(ChatHistoryService.class);
    private final SkillService skills = mock(SkillService.class);
    private final ProjectMemoryService projectMemories = mock(ProjectMemoryService.class);
    private final GroupMemoryService groupMemories = mock(GroupMemoryService.class);
    private final AgentMemoryService agentMemories = mock(AgentMemoryService.class);
    private final BriefingService service =
            new BriefingService(
                    groups, history, skills, projectMemories, groupMemories, agentMemories);

    @Test
    void rootBriefingRendersGroupMessagesMemoriesAndSkillCatalogAsLabelledText() {
        Long actor = TestIds.next(),
                projectId = TestIds.next(),
                groupId = TestIds.next(),
                agentId = TestIds.next(),
                skillId = TestIds.next();
        givenContext(actor, projectId, groupId, agentId);
        when(history.recentMessages(actor, projectId, groupId, 30))
                .thenReturn(
                        List.of(
                                new ContextMessage(1L, "TEXT", "USER", "7", "先做邮箱登录"),
                                new ContextMessage(
                                        2L,
                                        "IMAGE",
                                        "USER",
                                        "8",
                                        "",
                                        "9",
                                        "photo.png",
                                        "image/png",
                                        2048L),
                                new ContextMessage(
                                        3L, "FILE", "AGENT", "9", "", "11", null, null, null)));
        when(skills.publishedCatalog(actor, projectId))
                .thenReturn(List.of(new ContextSkill(skillId, "登录", "登录规范")));

        String briefing = service.context(actor, projectId, groupId, agentId, Scope.ROOT);

        assertThat(briefing)
                .isEqualTo(
                        """
                        当前群：登录改造（群 ID %d）
                        近期群消息：
                        [1] USER 7：先做邮箱登录
                        [2] USER 8：[图片 photo.png，附件 ID 9，类型 image/png，2048 字节]
                        [3] AGENT 9：[文件 附件 ID 11]
                        当前项目记忆（memory.md 全文）：
                        # 项目记忆
                        当前群记忆（由后台从群消息提炼，是快照）：
                        # 群记忆
                        当前 Agent 私有记忆：
                        # 分工
                        当前项目可激活的 Skill 目录（包含 ID、名称和用途描述；需要时调用 activate_skill 读取正文）：
                        - 登录（ID %d）：登录规范
                        """
                                .formatted(groupId, skillId));
    }

    @Test
    void childBriefingKeepsMemoriesAndSkillsWithoutGroupNameOrRecentMessages() {
        Long actor = TestIds.next(),
                projectId = TestIds.next(),
                groupId = TestIds.next(),
                agentId = TestIds.next(),
                skillId = TestIds.next();
        givenContext(actor, projectId, groupId, agentId);
        when(skills.publishedCatalog(actor, projectId))
                .thenReturn(List.of(new ContextSkill(skillId, "登录", "登录规范")));

        String briefing = service.context(actor, projectId, groupId, agentId, Scope.CHILD);

        assertThat(briefing)
                .doesNotContain("登录改造", "近期群消息", "附件 ID")
                .contains("# 项目记忆", "# 群记忆", "# 分工", "- 登录（ID " + skillId + "）：登录规范");
    }

    @Test
    void systemPromptCarriesTheAgentsOwnIdentityOnlyInTheRootScope() {
        AgentEntity agent = new AgentEntity();
        agent.setName("评审助手");
        agent.setDescription("负责代码评审");
        agent.setSystemPrompt("你只评审 Pull Request");

        assertThat(service.systemPrompt(agent, Scope.ROOT, true))
                .startsWith("你是 评审助手，项目群聊中的助手。你的职责：负责代码评审")
                .contains("你只评审 Pull Request")
                .contains("有效的普通文本回复即结束本轮 loop");
        assertThat(service.systemPrompt(agent, Scope.CHILD, false))
                .startsWith("你是 评审助手 委派的一名临时子 Agent。")
                .doesNotContain("负责代码评审", "你只评审 Pull Request");
    }

    private void givenContext(Long actor, Long projectId, Long groupId, Long agentId) {
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        group.setName("登录改造");
        when(groups.requireGroupMember(projectId, groupId, actor)).thenReturn(group);
        when(projectMemories.get(actor, projectId))
                .thenReturn(
                        new ProjectMemoryResponse(
                                projectId.toString(), "# 项目记忆", LocalDateTime.now()));
        when(groupMemories.read(projectId, groupId))
                .thenReturn(
                        new GroupMemoryResponse(
                                projectId.toString(), groupId.toString(), "# 群记忆", null));
        when(agentMemories.view(projectId, agentId)).thenReturn("# 分工");
    }
}
