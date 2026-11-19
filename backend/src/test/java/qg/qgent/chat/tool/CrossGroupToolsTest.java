package qg.qgent.chat.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.agent.service.AgentService;
import qg.qgent.chat.dto.ContextMessage;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.chat.mapper.GroupAgentMapper;
import qg.qgent.chat.mapper.GroupReadStateMapper;
import qg.qgent.chat.mapper.GroupUserMapper;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.mapper.view.GroupMemberRow;
import qg.qgent.chat.service.ChatHistoryService;
import qg.qgent.chat.service.CrossGroupAccessService;
import qg.qgent.chat.service.GroupService;
import qg.qgent.common.web.ApiException;
import qg.qgent.memory.service.GroupMemoryService;
import qg.qgent.memory.service.MemoryDocument;
import qg.qgent.project.mapper.ProjectMemberMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.support.TestIds;
import qg.qgent.user.mapper.UserGroupPreferenceMapper;

/**
 * 跨群读取其他群的记忆和聊天记录：发起用户与被调用 Agent 都必须属于来源群；
 * 目的群有哪些成员不影响读取，被拒绝时把是哪一侧不在群里告诉模型。
 */
class CrossGroupToolsTest {

    @TempDir Path root;

    private final ChatGroupMapper groupMapper = mock(ChatGroupMapper.class);
    private final ProjectMemberMapper projectMembers = mock(ProjectMemberMapper.class);
    private final GroupUserMapper groupUsers = mock(GroupUserMapper.class);
    private final MessageMapper messages = mock(MessageMapper.class);
    private final GroupAgentMapper groupAgents = mock(GroupAgentMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final GroupService groups =
            new GroupService(
                    groupMapper,
                    projectMembers,
                    groupUsers,
                    messages,
                    mock(GroupReadStateMapper.class),
                    mock(UserGroupPreferenceMapper.class),
                    access,
                    mock(EventService.class),
                    groupAgents,
                    mock(AgentService.class));
    private ChatHistoryService history;
    private GroupMemoryService groupMemories;
    private CrossGroupAccessService crossGroup;

    private final Long projectId = TestIds.next();
    private final Long defaultGroupId = TestIds.next();
    private final Long restrictedGroupId = TestIds.next();
    private final Long actor = TestIds.next();
    private final Long outsider = TestIds.next();
    private final Long agentId = TestIds.next();
    private final Long otherAgentId = TestIds.next();

    private ChatGroupEntity defaultGroup;
    private ChatGroupEntity restrictedGroup;

    @BeforeEach
    void setUp() {
        history = new ChatHistoryService(messages, groups, new ObjectMapper());
        groupMemories =
                new GroupMemoryService(
                        access,
                        groups,
                        new ProjectDirectoryService(root.toString(), root.toString()),
                        new MemoryDocument(12000));
        crossGroup = new CrossGroupAccessService(groups);
        defaultGroup = group(defaultGroupId, projectId, true, "项目群聊");
        restrictedGroup = group(restrictedGroupId, projectId, false, "后端改造");
        when(projectMembers.selectMembers(projectId))
                .thenReturn(List.of(member(actor), member(outsider)));
        when(groupUsers.selectUserIds(restrictedGroupId)).thenReturn(List.of(actor));
        when(groupAgents.selectAgentIds(defaultGroupId)).thenReturn(List.of(agentId));
        when(groupAgents.selectAgentIds(restrictedGroupId)).thenReturn(List.of(agentId));
    }

    @Test
    void readsAnotherGroupsMemoryAndHistoryWhenTheAskingUserAndAgentAreBothMembers() {
        groupMemories.write(projectId, defaultGroupId, "# 后端群结论：先做邮箱登录");
        when(messages.searchByQuery(projectId, List.of(defaultGroupId), "登录", 10))
                .thenReturn(List.of(message(defaultGroupId, "昨天完成登录联调")));

        CrossGroupTools tools = tools(actor, restrictedGroupId, agentId);
        Map<String, Object> memory = tools.readOtherGroupMemory(defaultGroupId.toString());
        Map<String, Object> found =
                tools.searchOtherGroupChatHistory(defaultGroupId.toString(), "登录", null);

        assertThat(memory).containsEntry("ok", true).containsEntry("memory", "# 后端群结论：先做邮箱登录");
        assertThat(found)
                .containsEntry("ok", true)
                .containsEntry("group", defaultGroupId.toString());
        assertThat((List<?>) found.get("messages"))
                .singleElement()
                .satisfies(item ->
                        assertThat((Map<String, Object>) item)
                                .containsEntry("senderType", "USER")
                                .containsEntry("text", "昨天完成登录联调"));
    }

    @Test
    void readsAnotherGroupsMessageByIdWithinThatGroup() {
        Long messageId = TestIds.next();
        MessageEntity stored = message(defaultGroupId, "需求：本周完成登录联调");
        stored.setId(messageId);
        stored.setReplyToMessageId(TestIds.next());
        when(messages.selectById(messageId)).thenReturn(stored);

        Map<String, Object> result =
                tools(actor, restrictedGroupId, agentId)
                        .readOtherGroupChatMessage(defaultGroupId.toString(), messageId.toString());

        assertThat(result)
                .containsEntry("ok", true)
                .containsEntry("group", defaultGroupId.toString());
        ContextMessage read = (ContextMessage) result.get("message");
        assertThat(read.getId()).isEqualTo(messageId.toString());
        assertThat(read.getText()).isEqualTo("需求：本周完成登录联调");
        assertThat(read.getReplyToId()).isEqualTo(stored.getReplyToMessageId().toString());
    }

    /** 提问者与 Agent 都在受限群时，把它问到成员更多的目的群里也允许；泄露风险由提问者自己负责。 */
    @Test
    void defaultGroupCanReadARestrictedGroupWhenTheAskingUserAndAgentAreBothMembers() {
        groupMemories.write(projectId, restrictedGroupId, "# 受限群内部计划");

        Map<String, Object> result =
                tools(actor, defaultGroupId, agentId)
                        .readOtherGroupMemory(restrictedGroupId.toString());

        assertThat(result).containsEntry("ok", true).containsEntry("memory", "# 受限群内部计划");
    }

    @Test
    void userOutsideTheSourceGroupIsToldTheyAreNotInThatGroup() {
        groupMemories.write(projectId, restrictedGroupId, "# 受限群内部计划");

        Map<String, Object> result =
                tools(outsider, restrictedGroupId, agentId)
                        .readOtherGroupMemory(restrictedGroupId.toString());

        assertThat(result)
                .containsEntry("ok", false)
                .containsEntry("errorCode", "REQUESTER_NOT_IN_GROUP")
                .doesNotContainValue("# 受限群内部计划");
        assertThat((String) result.get("error")).contains("你不在那个群聊");
    }

    @Test
    void agentOutsideTheSourceGroupIsToldItIsNotInThatGroup() {
        Map<String, Object> result =
                tools(actor, restrictedGroupId, otherAgentId)
                        .readOtherGroupMemory(restrictedGroupId.toString());

        assertThat(result)
                .containsEntry("ok", false)
                .containsEntry("errorCode", "AGENT_NOT_IN_GROUP");
        assertThat((String) result.get("error")).contains("我不在那个群");
    }

    @Test
    void groupFromAnotherProjectIsTreatedAsUserNotInThatGroup() {
        Long foreignProject = TestIds.next();
        Long foreignGroupId = TestIds.next();
        group(foreignGroupId, foreignProject, false, "别的项目的群");

        Map<String, Object> result =
                tools(actor, restrictedGroupId, agentId)
                        .readOtherGroupMemory(foreignGroupId.toString());

        assertThat(result)
                .containsEntry("ok", false)
                .containsEntry("errorCode", "REQUESTER_NOT_IN_GROUP");
    }

    @Test
    void sourceGroupMembershipChangeTakesEffectOnTheNextRead() {
        groupMemories.write(projectId, restrictedGroupId, "# 受限群内部计划");
        CrossGroupTools tools = tools(actor, restrictedGroupId, agentId);
        assertThat(tools.readOtherGroupMemory(restrictedGroupId.toString()))
                .containsEntry("ok", true);

        when(groupUsers.selectUserIds(restrictedGroupId)).thenReturn(List.of());

        Map<String, Object> result = tools.readOtherGroupMemory(restrictedGroupId.toString());
        assertThat(result)
                .containsEntry("ok", false)
                .containsEntry("errorCode", "REQUESTER_NOT_IN_GROUP");
    }

    @Test
    void messageIdFromAnotherGroupIsNotFound() {
        Long foreignMessageId = TestIds.next();
        MessageEntity stored = message(defaultGroupId, "默认群里的讨论");
        stored.setId(foreignMessageId);
        when(messages.selectById(foreignMessageId)).thenReturn(stored);

        ApiException denied =
                assertThrows(
                        ApiException.class,
                        () ->
                                tools(actor, restrictedGroupId, agentId)
                                        .readOtherGroupChatMessage(
                                                restrictedGroupId.toString(),
                                                foreignMessageId.toString()));

        assertThat(denied.code()).isEqualTo("MESSAGE_NOT_FOUND");
        assertThat(denied.getMessage()).doesNotContain("默认群里的讨论");
    }

    @Test
    void listShowsEveryOtherGroupWithWhetherThisRunCanReadIt() {
        Long agentOnlyGroupId = TestIds.next();
        ChatGroupEntity agentOnlyGroup = group(agentOnlyGroupId, projectId, false, "只加了 Agent 的群");
        when(groupAgents.selectGroupIds(agentId))
                .thenReturn(List.of(defaultGroupId, restrictedGroupId, agentOnlyGroupId));
        when(groupMapper.listByProject(projectId))
                .thenReturn(List.of(defaultGroup, restrictedGroup));

        Map<String, Object> result = tools(outsider, defaultGroupId, agentId).listOtherGroups();

        assertThat((List<?>) result.get("groups"))
                .hasSize(2)
                .satisfies(items -> {
                    assertThat((Map<String, Object>) items.get(0))
                            .containsEntry("id", restrictedGroupId.toString())
                            .containsEntry("readable", false)
                            .containsEntry("reason", "REQUESTER_NOT_IN_GROUP");
                    assertThat((Map<String, Object>) items.get(1))
                            .containsEntry("id", agentOnlyGroupId.toString())
                            .containsEntry("title", agentOnlyGroup.getName())
                            .containsEntry("readable", false)
                            .containsEntry("reason", "REQUESTER_NOT_IN_GROUP");
                });
    }

    @Test
    void listMarksGroupsTheAskingUserAndAgentAreBothInAsReadable() {
        when(groupMapper.listByProject(projectId))
                .thenReturn(List.of(defaultGroup, restrictedGroup));
        when(groupAgents.selectGroupIds(agentId)).thenReturn(List.of(defaultGroupId));

        Map<String, Object> result = tools(actor, restrictedGroupId, agentId).listOtherGroups();

        assertThat((List<?>) result.get("groups"))
                .singleElement()
                .satisfies(item ->
                        assertThat((Map<String, Object>) item)
                                .containsEntry("id", defaultGroupId.toString())
                                .containsEntry("title", "项目群聊")
                                .containsEntry("readable", true)
                                .doesNotContainKey("reason"));
    }

    @Test
    void invalidOrMissingArgumentsAreRejectedWithoutReadingAnything() {
        CrossGroupTools tools = tools(actor, restrictedGroupId, agentId);

        assertThatThrownBy(() -> tools.readOtherGroupMemory("后端群"))
                .extracting("code")
                .isEqualTo("GROUP_ID_INVALID");
        assertThatThrownBy(() ->
                        tools.searchOtherGroupChatHistory(defaultGroupId.toString(), " ", null))
                .extracting("code")
                .isEqualTo("CHAT_SEARCH_QUERY_REQUIRED");
    }

    private CrossGroupTools tools(Long actor, Long destinationGroupId, Long agentId) {
        return new CrossGroupTools(
                crossGroup, groupMemories, history, actor, projectId, destinationGroupId, agentId);
    }

    private ChatGroupEntity group(Long id, Long projectId, boolean defaultGroup, String name) {
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(id);
        group.setProjectId(projectId);
        group.setDefaultGroup(defaultGroup);
        group.setName(name);
        when(groupMapper.selectById(id)).thenReturn(group);
        return group;
    }

    private MessageEntity message(Long groupId, String text) {
        MessageEntity message = new MessageEntity();
        message.setId(TestIds.next());
        message.setGroupId(groupId);
        message.setSequenceNo(1L);
        message.setMessageType("TEXT");
        message.setSenderType("USER");
        message.setAuthorUserId(actor);
        message.setContent("{\"text\":\"" + text + "\"}");
        return message;
    }

    private GroupMemberRow member(Long userId) {
        GroupMemberRow row = new GroupMemberRow();
        row.setUserId(userId);
        return row;
    }
}
