package qg.qgent.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.service.AgentService;
import qg.qgent.chat.dto.GroupCreateRequest;
import qg.qgent.chat.dto.GroupInviteRequest;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.event.*;
import qg.qgent.chat.mapper.*;
import qg.qgent.chat.mapper.view.GroupAgentRow;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.event.ProjectCreatedEvent;
import qg.qgent.project.mapper.*;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.support.TestIds;
import qg.qgent.user.mapper.*;

class ProjectChatTest {
    private final ChatGroupMapper groups = mock(ChatGroupMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final GroupUserMapper users = mock(GroupUserMapper.class);
    private final GroupAgentMapper groupAgents = mock(GroupAgentMapper.class);
    private final AgentService agents = mock(AgentService.class);
    private final ProjectMemberMapper projectMembers = mock(ProjectMemberMapper.class);
    private final GroupService service =
            new GroupService(
                    groups,
                    projectMembers,
                    users,
                    mock(MessageMapper.class),
                    mock(GroupReadStateMapper.class),
                    mock(UserGroupPreferenceMapper.class),
                    access,
                    mock(EventService.class),
                    groupAgents,
                    agents);

    @org.junit.jupiter.api.BeforeEach
    void assignGeneratedGroupIds() {
        when(groups.insert(any(ChatGroupEntity.class)))
                .thenAnswer(call -> {
                    call.getArgument(0, ChatGroupEntity.class).setId(TestIds.next());
                    return 1;
                });
    }

    @Test
    void projectCreationCreatesOneDefaultChatWithTheProjectsOwnAgent() {
        Long projectId = TestIds.next();
        Long actor = TestIds.next();
        Long agentId = TestIds.next();
        AgentEntity agent = new AgentEntity();
        agent.setId(agentId);
        agent.setProjectId(projectId);
        when(agents.defaultAgent(projectId)).thenReturn(agent);
        ChatGroupEntity[] stored = new ChatGroupEntity[1];
        when(groups.insert(any(ChatGroupEntity.class)))
                .thenAnswer(call -> {
                    stored[0] = call.getArgument(0);
                    stored[0].setId(TestIds.next());
                    return 1;
                });
        when(groups.selectById(any())).thenAnswer(call -> stored[0]);
        service.onProjectCreated(new ProjectCreatedEvent(projectId, "项目", actor));
        when(groups.selectOne(any())).thenReturn(stored[0]);
        assertThat(service.ensureProjectGroup(projectId, actor, "项目").getId())
                .isEqualTo(stored[0].getId().toString());
        verify(groups).insert(any(ChatGroupEntity.class));
        verify(groupAgents).insertAgent(stored[0].getId(), agentId);
        assertThat(stored[0].getProjectId()).isEqualTo(projectId);
    }

    @Test
    void projectMemberCanAccessChatWithoutSeparateGroupMembership() {
        Long projectId = TestIds.next(), groupId = TestIds.next(), actor = TestIds.next();
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        when(groups.selectById(groupId)).thenReturn(group);
        service.requireGroupMember(projectId, groupId, actor);
        verify(access).requireProjectMember(projectId, actor);
    }

    @Test
    void membersListAgentsByTheirRealIdentity() {
        Long projectId = TestIds.next(), groupId = TestIds.next(), actor = TestIds.next();
        Long agentId = TestIds.next();
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        group.setDefaultGroup(true);
        when(groups.selectById(groupId)).thenReturn(group);
        GroupAgentRow agent = new GroupAgentRow();
        agent.setAgentId(agentId);
        agent.setName("项目助手");
        agent.setAvatarUrl("https://cdn.example.com/agent.png");
        when(groupAgents.selectAgents(groupId)).thenReturn(List.of(agent));

        var members = service.members(actor, projectId, groupId);

        assertThat(members)
                .anySatisfy(member -> {
                    assertThat(member.getId()).isEqualTo(agentId.toString());
                    assertThat(member.getDisplayName()).isEqualTo("项目助手");
                    assertThat(member.getMemberType()).isEqualTo("AGENT");
                    assertThat(member.getAvatarUrl())
                            .isEqualTo("https://cdn.example.com/agent.png");
                });
    }

    @Test
    void newGroupIncludesCreatorAndSelectedUsersAndAgents() {
        Long projectId = TestIds.next(), actor = TestIds.next(), teammate = TestIds.next();
        Long agentId = TestIds.next();
        GroupCreateRequest body = new GroupCreateRequest();
        body.setTitle("  方案讨论  ");
        body.setUserIds(List.of(teammate));
        body.setAgentIds(List.of(agentId));
        var response = service.create(actor, projectId, body);
        ArgumentCaptor<ChatGroupEntity> saved = ArgumentCaptor.forClass(ChatGroupEntity.class);
        verify(groups).insert(saved.capture());
        assertThat(saved.getValue().getDefaultGroup()).isFalse();
        assertThat(response.getTitle()).isEqualTo("方案讨论");
        assertThat(
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .valueToTree(response)
                                .has("description"))
                .isFalse();
        verify(users).insertUser(saved.getValue().getId(), actor);
        verify(users).insertUser(saved.getValue().getId(), teammate);
        verify(groupAgents).insertAgent(saved.getValue().getId(), agentId);
        verify(agents).requireProjectAgents(projectId, List.of(agentId));
        verify(access).requireProjectMember(projectId, teammate);
    }

    @Test
    void newGroupRequiresInvitedMembershipAndIsHiddenFromOthers() {
        Long projectId = TestIds.next(), groupId = TestIds.next(), actor = TestIds.next();
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        group.setDefaultGroup(false);
        when(groups.selectById(groupId)).thenReturn(group);
        when(groups.listByProject(projectId)).thenReturn(List.of(group));
        when(users.selectUserIds(groupId)).thenReturn(List.of(TestIds.next()));
        assertThatThrownBy(() -> service.requireGroupMember(projectId, groupId, actor))
                .isInstanceOf(ApiException.class);
        assertThat(service.list(actor, projectId)).isEmpty();
        when(users.selectUserIds(groupId)).thenReturn(List.of(actor));
        service.requireGroupMember(projectId, groupId, actor);
    }

    @Test
    void inviteAddsMembersToExistingGroupWithoutChangingItsIdentity() {
        Long projectId = TestIds.next(),
                groupId = TestIds.next(),
                actor = TestIds.next(),
                teammate = TestIds.next();
        Long agentId = TestIds.next();
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        group.setDefaultGroup(false);
        when(groups.selectById(groupId)).thenReturn(group);
        when(users.selectUserIds(groupId)).thenReturn(List.of(actor));
        GroupInviteRequest body = new GroupInviteRequest();
        body.setUserIds(List.of(teammate));
        body.setAgentIds(List.of(agentId));
        service.invite(actor, projectId, groupId, body);
        verify(users).insertUser(groupId, teammate);
        verify(groupAgents).insertAgent(groupId, agentId);
        verify(agents).requireProjectAgents(projectId, List.of(agentId));
        verify(groups, never()).insert(any(ChatGroupEntity.class));
    }

    @Test
    void rejectsAgentFromAnotherProjectBeforeSavingGroup() {
        Long projectId = TestIds.next(), actor = TestIds.next(), foreignAgentId = TestIds.next();
        doThrow(
                        new ApiException(
                                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                                "INVALID_GROUP_AGENT",
                                "只能邀请本项目内的 Agent"))
                .when(agents)
                .requireProjectAgents(projectId, List.of(foreignAgentId));
        GroupCreateRequest body = new GroupCreateRequest();
        body.setTitle("讨论");
        body.setAgentIds(List.of(foreignAgentId));
        assertThatThrownBy(() -> service.create(actor, projectId, body))
                .isInstanceOf(ApiException.class);
        verify(groups, never()).insert(any(ChatGroupEntity.class));
        verify(groupAgents, never()).insertAgent(any(), any());
    }

    @Test
    void groupAgentRepliesOnlyForAgentsInThatGroup() {
        Long groupId = TestIds.next(), agentId = TestIds.next();
        when(groupAgents.selectAgentIds(groupId)).thenReturn(List.of(agentId));
        service.requireGroupAgent(groupId, agentId);

        assertThatThrownBy(() -> service.requireGroupAgent(groupId, TestIds.next()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("AGENT_NOT_IN_GROUP"));
    }
}
