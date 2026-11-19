package qg.qgent.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import qg.qgent.chat.dto.GroupPinResponse;
import qg.qgent.chat.dto.GroupResponse;
import qg.qgent.chat.dto.GroupUpdateRequest;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.chat.mapper.GroupReadStateMapper;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.mapper.view.GroupLatestMessageRow;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.mapper.ProjectMemberMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.support.TestIds;
import qg.qgent.user.mapper.UserGroupPreferenceMapper;

/** 群聊置顶（个人偏好持久化）：列表返回 pinned、setPinned 幂等 upsert、非项目成员 403。 */
class GroupPinServiceTest {

    private final ChatGroupMapper groupMapper = mock(ChatGroupMapper.class);
    private final ProjectMemberMapper projectMemberMapper = mock(ProjectMemberMapper.class);
    private final MessageMapper messageMapper = mock(MessageMapper.class);
    private final GroupReadStateMapper groupReadStateMapper = mock(GroupReadStateMapper.class);
    private final UserGroupPreferenceMapper userGroupPreferenceMapper =
            mock(UserGroupPreferenceMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final EventService eventService = mock(EventService.class);

    private final GroupService service =
            new GroupService(
                    groupMapper,
                    projectMemberMapper,
                    mock(qg.qgent.chat.mapper.GroupUserMapper.class),
                    messageMapper,
                    groupReadStateMapper,
                    userGroupPreferenceMapper,
                    access,
                    eventService,
                    mock(qg.qgent.chat.mapper.GroupAgentMapper.class),
                    mock(qg.qgent.agent.service.AgentService.class));

    private final Long projectId = TestIds.next();
    private final Long creator = TestIds.next();
    private final Long memberA = TestIds.next();
    private final Long groupId = TestIds.next();

    private ChatGroupEntity projectChat() {
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        group.setCreatedBy(creator);
        return group;
    }

    @BeforeEach
    void stubDefaults() {
        when(groupMapper.selectById(groupId)).thenReturn(projectChat());
        when(projectMemberMapper.countMembers(projectId)).thenReturn(0L);
    }

    @Test
    void listMarksPinnedGroups() {
        when(groupMapper.listByProject(projectId)).thenReturn(List.of(projectChat()));
        when(messageMapper.selectLatestByGroupIds(List.of(groupId))).thenReturn(List.of());
        when(messageMapper.countUnreadByGroupIds(List.of(groupId), memberA)).thenReturn(List.of());
        when(messageMapper.countMentionUnreadByGroupIds(
                        List.of(groupId), memberA, memberA.toString()))
                .thenReturn(List.of());
        when(userGroupPreferenceMapper.selectPinnedGroupIds(memberA, List.of(groupId)))
                .thenReturn(List.of(groupId));

        List<GroupResponse> groups = service.list(memberA, projectId);

        assertEquals(1, groups.size());
        assertTrue(groups.get(0).getPinned());
        verify(userGroupPreferenceMapper).selectPinnedGroupIds(memberA, List.of(groupId));
    }

    @Test
    void listDefaultsUnpinnedGroupsToFalse() {
        when(groupMapper.listByProject(projectId)).thenReturn(List.of(projectChat()));
        when(messageMapper.selectLatestByGroupIds(List.of(groupId))).thenReturn(List.of());
        when(messageMapper.countUnreadByGroupIds(List.of(groupId), memberA)).thenReturn(List.of());
        when(messageMapper.countMentionUnreadByGroupIds(
                        List.of(groupId), memberA, memberA.toString()))
                .thenReturn(List.of());
        // 默认 stub：selectPinnedGroupIds 返回空 → 未置顶
        when(userGroupPreferenceMapper.selectPinnedGroupIds(memberA, List.of(groupId)))
                .thenReturn(List.of());

        List<GroupResponse> groups = service.list(memberA, projectId);

        assertEquals(1, groups.size());
        assertFalse(groups.get(0).getPinned());
    }

    @Test
    void detailIncludesLatestMessageAndPersonalPin() {
        GroupLatestMessageRow latest = new GroupLatestMessageRow();
        latest.setGroupId(groupId);
        latest.setSenderName("助手");
        latest.setText("已完成修改");
        latest.setMessageType("TEXT");
        when(messageMapper.selectLatestByGroupIds(List.of(groupId))).thenReturn(List.of(latest));
        when(userGroupPreferenceMapper.selectPinnedGroupIds(memberA, List.of(groupId)))
                .thenReturn(List.of(groupId));

        GroupResponse response = service.get(memberA, projectId, groupId);

        assertEquals("已完成修改", response.getLatestMessage().getText());
        assertTrue(response.getPinned());
    }

    @Test
    void updateRejectsBlankTitleWithoutWritingOrPublishing() {
        GroupUpdateRequest body = new GroupUpdateRequest();
        body.setTitle("  ");

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> service.update(creator, projectId, groupId, body));

        assertEquals("GROUP_TITLE_REQUIRED", error.code());
        org.mockito.Mockito.verifyNoInteractions(eventService);
    }

    @Test
    void setPinnedUpsertsAndReturnsStatus() {
        GroupPinResponse response = service.setPinned(creator, projectId, groupId, true);

        assertEquals(groupId.toString(), response.getGroupId());
        assertTrue(response.getPinned());
        verify(userGroupPreferenceMapper).upsertPin(creator, groupId, true);
    }

    @Test
    void setPinnedUnpinUpsertsFalse() {
        GroupPinResponse response = service.setPinned(creator, projectId, groupId, false);

        assertEquals(groupId.toString(), response.getGroupId());
        assertFalse(response.getPinned());
        verify(userGroupPreferenceMapper).upsertPin(creator, groupId, false);
    }

    @Test
    void setPinnedRequiresProjectMember() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "PROJECT_MEMBER_REQUIRED", "需要项目成员身份"))
                .when(access)
                .requireProjectMember(projectId, memberA);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> service.setPinned(memberA, projectId, groupId, true));

        assertEquals(HttpStatus.FORBIDDEN, error.status());
        verify(userGroupPreferenceMapper, never()).upsertPin(any(), any(), anyBoolean());
    }

    @Test
    void setPinnedRejectsGroupNotInProject() {
        // 群不属于该项目：requireGroupInProject 抛 404
        when(groupMapper.selectById(groupId)).thenReturn(null);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> service.setPinned(creator, projectId, groupId, true));

        assertEquals(HttpStatus.NOT_FOUND, error.status());
        verify(userGroupPreferenceMapper, never()).upsertPin(any(), any(), anyBoolean());
    }
}
