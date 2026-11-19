package qg.qgent.project.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import qg.qgent.agent.service.AgentService;
import qg.qgent.common.storage.AvatarStorageService;
import qg.qgent.notification.service.NotificationService;
import qg.qgent.project.dto.ProjectCreateRequest;
import qg.qgent.project.dto.ProjectUpdateRequest;
import qg.qgent.project.entity.ProjectEntity;
import qg.qgent.project.event.ProjectCreatedEvent;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.mapper.ProjectMemberMapper;
import qg.qgent.project.mapper.view.ProjectMembershipView;
import qg.qgent.realtime.service.EventService;
import qg.qgent.support.TestIds;
import qg.qgent.team.entity.TeamEntity;
import qg.qgent.team.entity.TeamMemberEntity;
import qg.qgent.team.mapper.TeamMapper;
import qg.qgent.team.mapper.TeamMemberMapper;

class ProjectServiceTest {
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final ProjectMemberMapper projectMembers = mock(ProjectMemberMapper.class);
    private final TeamMapper teams = mock(TeamMapper.class);
    private final TeamMemberMapper teamMembers = mock(TeamMemberMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final qg.qgent.workspace.service.WorkspaceService workspaces =
            mock(qg.qgent.workspace.service.WorkspaceService.class);
    private final AvatarStorageService avatarStorage = mock(AvatarStorageService.class);
    private final AgentService agents = mock(AgentService.class);
    private final ProjectService service =
            new ProjectService(
                    projects,
                    projectMembers,
                    teams,
                    teamMembers,
                    access,
                    mock(ApplicationEventPublisher.class),
                    mock(NotificationService.class),
                    mock(EventService.class),
                    workspaces,
                    avatarStorage,
                    agents);

    @Test
    void projectListReturnsAvatarAndMemberCountWithoutExtraQueries() {
        Long actor = TestIds.next(), teamId = TestIds.next();
        TeamEntity team = new TeamEntity();
        team.setStatus("ACTIVE");
        when(teams.selectById(teamId)).thenReturn(team);
        when(teamMembers.selectByTeamAndUser(teamId, actor)).thenReturn(new TeamMemberEntity());
        ProjectMembershipView row = new ProjectMembershipView();
        row.setId(TestIds.next());
        row.setTeamId(teamId);
        row.setAvatarUrl("https://assets.example/avatar.png");
        row.setMemberCount(3L);
        when(projects.selectAccessiblePage(teamId, actor, false, null, 21))
                .thenReturn(List.of(row));

        var response = service.list(actor, teamId, null, 20).getData().getFirst();

        assertThat(response.getAvatarUrl()).isEqualTo(row.getAvatarUrl());
        assertThat(response.getMemberCount()).isEqualTo(3L);
        verifyNoInteractions(projectMembers);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unchanged", "clear", "archive", "restore"})
    void projectChangesPreserveReplaceOrClearAvatar(String operation) {
        Long actor = TestIds.next();
        ProjectEntity project = new ProjectEntity();
        project.setId(TestIds.next());
        project.setTeamId(TestIds.next());
        project.setName("项目");
        project.setStatus("restore".equals(operation) ? "ARCHIVED" : "ACTIVE");
        project.setAvatarUrl("https://assets.example/original.png");
        when(projects.selectByIdForUpdate(project.getId())).thenReturn(project);
        String expectedAvatar = project.getAvatarUrl();
        qg.qgent.project.dto.ProjectResponse result;
        if ("archive".equals(operation)) {
            result = service.archive(actor, project.getId());
        } else if ("restore".equals(operation)) {
            result = service.restore(actor, project.getId());
        } else {
            ProjectUpdateRequest request = new ProjectUpdateRequest();
            request.setName("更新名称");
            if ("clear".equals(operation)) {
                request.setAvatarUrl(" ");
                expectedAvatar = null;
            }
            result = service.update(actor, project.getId(), request);
        }

        assertThat(result.getAvatarUrl()).isEqualTo(expectedAvatar);
        assertThat(project.getAvatarUrl()).isEqualTo(expectedAvatar);
        verify(projects).updateById(project);
    }

    @Test
    void avatarConfirmWritesUrlAndDeletesReplacedObject() {
        Long actor = TestIds.next();
        ProjectEntity project = new ProjectEntity();
        project.setId(TestIds.next());
        project.setTeamId(TestIds.next());
        project.setStatus("ACTIVE");
        project.setAvatarUrl("https://assets.example/original.png");
        when(projects.selectByIdForUpdate(project.getId())).thenReturn(project);
        String newKey = "projects/" + project.getId() + "/new.png";
        when(avatarStorage.confirm("projects", project.getId(), newKey))
                .thenReturn("https://assets.example/new.png");

        var result = service.updateAvatar(actor, project.getId(), newKey);

        assertThat(result.getAvatarUrl()).isEqualTo("https://assets.example/new.png");
        verify(projects).updateById(project);
        verify(avatarStorage)
                .deleteReplacedAfterCommit(
                        "projects", project.getId(), "https://assets.example/original.png", newKey);
    }

    @Test
    void creationInitializesProjectAndChatWithoutRepositoryInputsOrExternalCalls() {
        Long owner = TestIds.next(), teamId = TestIds.next();
        var teams = mock(TeamMapper.class);
        var team = new TeamEntity();
        team.setId(teamId);
        team.setOwnerUserId(owner);
        team.setStatus("ACTIVE");
        when(teams.selectByIdForUpdate(teamId)).thenReturn(team);
        var events = mock(ApplicationEventPublisher.class);
        var members = mock(TeamMemberMapper.class);
        var membership = new qg.qgent.team.entity.TeamMemberEntity();
        membership.setRole("TEAM_OWNER");
        when(members.selectByTeamAndUser(teamId, owner)).thenReturn(membership);
        var projects = mock(ProjectMapper.class);
        when(projects.insert(any(ProjectEntity.class)))
                .thenAnswer(invocation -> {
                    ProjectEntity entity = invocation.getArgument(0);
                    entity.setId(TestIds.next());
                    return 1;
                });
        var service =
                new ProjectService(
                        projects,
                        mock(ProjectMemberMapper.class),
                        teams,
                        members,
                        mock(ProjectAccessService.class),
                        events,
                        mock(NotificationService.class),
                        mock(EventService.class),
                        mock(qg.qgent.workspace.service.WorkspaceService.class),
                        mock(AvatarStorageService.class),
                        mock(qg.qgent.agent.service.AgentService.class));
        var request = new ProjectCreateRequest();
        request.setName("项目");
        var created = service.create(owner, teamId, request);
        assertThat(created.getName()).isEqualTo("项目");
        verify(events)
                .publishEvent(
                        argThat((Object event) ->
                                event instanceof ProjectCreatedEvent project
                                        && project.projectId().toString().equals(created.getId())
                                        && project.creatorUserId().equals(owner)));
    }

    @Test
    void newProjectGetsItsOwnDefaultAgent() {
        Long owner = TestIds.next(), teamId = TestIds.next();
        TeamEntity team = new TeamEntity();
        team.setId(teamId);
        team.setOwnerUserId(owner);
        team.setStatus("ACTIVE");
        when(teams.selectByIdForUpdate(teamId)).thenReturn(team);
        TeamMemberEntity membership = new TeamMemberEntity();
        membership.setRole("TEAM_OWNER");
        when(teamMembers.selectByTeamAndUser(teamId, owner)).thenReturn(membership);
        when(projects.insert(any(ProjectEntity.class)))
                .thenAnswer(invocation -> {
                    ProjectEntity entity = invocation.getArgument(0);
                    entity.setId(TestIds.next());
                    return 1;
                });
        ProjectCreateRequest request = new ProjectCreateRequest();
        request.setName("项目");

        var created = service.create(owner, teamId, request);

        // 默认 Agent 与项目同一事务创建；默认群的成员列表也依赖它。
        verify(agents).createDefaultAgent(Long.valueOf(created.getId()));
    }

    @Test
    void teamAdminCanCreateProjectAndBecomesProjectAdmin() {
        Long admin = TestIds.next(), owner = TestIds.next(), teamId = TestIds.next();
        TeamEntity team = new TeamEntity();
        team.setId(teamId);
        team.setOwnerUserId(owner);
        team.setStatus("ACTIVE");
        when(teams.selectByIdForUpdate(teamId)).thenReturn(team);
        TeamMemberEntity membership = new TeamMemberEntity();
        membership.setRole("TEAM_ADMIN");
        when(teamMembers.selectByTeamAndUser(teamId, admin)).thenReturn(membership);
        when(projects.insert(any(ProjectEntity.class)))
                .thenAnswer(invocation -> {
                    ProjectEntity entity = invocation.getArgument(0);
                    entity.setId(TestIds.next());
                    return 1;
                });
        ProjectCreateRequest request = new ProjectCreateRequest();
        request.setName("管理员项目");

        var created = service.create(admin, teamId, request);

        assertThat(created.getRole()).isEqualTo("PROJECT_ADMIN");
        verify(projectMembers)
                .insert(
                        argThat((qg.qgent.project.entity.ProjectMemberEntity member) ->
                                admin.equals(member.getUserId())
                                        && "PROJECT_ADMIN".equals(member.getRole())));
    }

    @Test
    void ordinaryMemberCannotCreateProject() {
        Long member = TestIds.next(), owner = TestIds.next(), teamId = TestIds.next();
        TeamEntity team = new TeamEntity();
        team.setId(teamId);
        team.setOwnerUserId(owner);
        team.setStatus("ACTIVE");
        when(teams.selectByIdForUpdate(teamId)).thenReturn(team);
        TeamMemberEntity membership = new TeamMemberEntity();
        membership.setRole("TEAM_MEMBER");
        when(teamMembers.selectByTeamAndUser(teamId, member)).thenReturn(membership);
        ProjectCreateRequest request = new ProjectCreateRequest();
        request.setName("普通成员项目");

        ProjectService localService =
                new ProjectService(
                        projects,
                        projectMembers,
                        teams,
                        teamMembers,
                        new ProjectAccessService(projects, projectMembers, teams, teamMembers),
                        mock(ApplicationEventPublisher.class),
                        mock(NotificationService.class),
                        mock(EventService.class),
                        workspaces,
                        avatarStorage,
                        mock(qg.qgent.agent.service.AgentService.class));

        assertThatThrownBy(() -> localService.create(member, teamId, request))
                .isInstanceOf(qg.qgent.common.web.ApiException.class)
                .satisfies(error ->
                        assertThat(((qg.qgent.common.web.ApiException) error).code())
                                .isEqualTo("TEAM_ADMIN_REQUIRED"));
        verify(projects, never()).insert(any(ProjectEntity.class));
    }

    @Test
    void projectAdminCanDemoteAndRemoveAnotherAdmin() {
        Long actor = TestIds.next(), teamId = TestIds.next(), projectId = TestIds.next();
        Long target = TestIds.next();
        lockProject(projectId, teamId);
        qg.qgent.project.entity.ProjectMemberEntity admin =
                new qg.qgent.project.entity.ProjectMemberEntity();
        admin.setProjectId(projectId);
        admin.setUserId(target);
        admin.setRole("PROJECT_ADMIN");
        when(projectMembers.selectByProjectAndUser(projectId, target)).thenReturn(admin);

        var demoted = service.updateMember(actor, projectId, target, updateRole("PROJECT_MEMBER"));
        var removed = service.removeMember(actor, projectId, target);

        assertThat(demoted.getRole()).isEqualTo("PROJECT_MEMBER");
        assertThat(removed.getRole()).isEqualTo("PROJECT_ADMIN");
        verify(projectMembers).updateRole(projectId, target, "PROJECT_MEMBER");
        verify(projectMembers).deleteByProjectAndUser(projectId, target);
    }

    @Test
    void projectAdminCanDemoteOwnRole() {
        Long actor = TestIds.next(), teamId = TestIds.next(), projectId = TestIds.next();
        lockProject(projectId, teamId);
        qg.qgent.project.entity.ProjectMemberEntity admin =
                new qg.qgent.project.entity.ProjectMemberEntity();
        admin.setProjectId(projectId);
        admin.setUserId(actor);
        admin.setRole("PROJECT_ADMIN");
        when(projectMembers.selectByProjectAndUser(projectId, actor)).thenReturn(admin);

        var demoted = service.updateMember(actor, projectId, actor, updateRole("PROJECT_MEMBER"));

        assertThat(demoted.getRole()).isEqualTo("PROJECT_MEMBER");
        verify(projectMembers).updateRole(projectId, actor, "PROJECT_MEMBER");
    }

    @Test
    void teamOwnerInheritedProjectRoleCannotBeChangedOrRemoved() {
        Long actor = TestIds.next(), teamId = TestIds.next(), projectId = TestIds.next();
        Long owner = TestIds.next();
        lockProject(projectId, teamId);
        qg.qgent.project.entity.ProjectMemberEntity membership =
                new qg.qgent.project.entity.ProjectMemberEntity();
        membership.setProjectId(projectId);
        membership.setUserId(owner);
        membership.setRole("PROJECT_ADMIN");
        when(projectMembers.selectByProjectAndUser(projectId, owner)).thenReturn(membership);
        when(access.isCanonicalTeamOwner(teamId, owner)).thenReturn(true);

        assertThatThrownBy(() ->
                        service.updateMember(actor, projectId, owner, updateRole("PROJECT_MEMBER")))
                .isInstanceOf(qg.qgent.common.web.ApiException.class)
                .satisfies(error ->
                        assertThat(((qg.qgent.common.web.ApiException) error).code())
                                .isEqualTo("TEAM_OWNER_PROJECT_ROLE_INHERITED"));
        assertThatThrownBy(() -> service.removeMember(actor, projectId, owner))
                .isInstanceOf(qg.qgent.common.web.ApiException.class)
                .satisfies(error ->
                        assertThat(((qg.qgent.common.web.ApiException) error).code())
                                .isEqualTo("TEAM_OWNER_PROJECT_ROLE_INHERITED"));
        verify(projectMembers, never()).updateRole(any(), any(), any());
        verify(projectMembers, never()).deleteByProjectAndUser(any(), any());
    }

    private void lockProject(Long projectId, Long teamId) {
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(teamId);
        project.setStatus("ACTIVE");
        TeamEntity team = new TeamEntity();
        team.setId(teamId);
        team.setStatus("ACTIVE");
        when(projects.selectById(projectId)).thenReturn(project);
        when(teams.selectByIdForUpdate(teamId)).thenReturn(team);
        when(projects.selectByIdForUpdate(projectId)).thenReturn(project);
    }

    private qg.qgent.project.dto.ProjectMemberUpdateRequest updateRole(String role) {
        qg.qgent.project.dto.ProjectMemberUpdateRequest request =
                new qg.qgent.project.dto.ProjectMemberUpdateRequest();
        request.setRole(role);
        return request;
    }

    @Test
    void emptyPatchIsRejected() {
        Long actor = TestIds.next();
        ProjectEntity project = new ProjectEntity();
        project.setId(TestIds.next());
        project.setTeamId(TestIds.next());
        project.setName("项目");
        project.setStatus("ACTIVE");
        when(projects.selectByIdForUpdate(project.getId())).thenReturn(project);

        assertThatThrownBy(() -> service.update(actor, project.getId(), new ProjectUpdateRequest()))
                .hasMessageContaining("至少需要提供");
    }
}
