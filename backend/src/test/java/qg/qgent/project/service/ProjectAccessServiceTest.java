package qg.qgent.project.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.entity.ProjectEntity;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.mapper.ProjectMemberMapper;
import qg.qgent.support.TestIds;
import qg.qgent.team.entity.TeamEntity;
import qg.qgent.team.entity.TeamMemberEntity;
import qg.qgent.team.mapper.TeamMapper;
import qg.qgent.team.mapper.TeamMemberMapper;

class ProjectAccessServiceTest {
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final ProjectMemberMapper projectMembers = mock(ProjectMemberMapper.class);
    private final TeamMapper teams = mock(TeamMapper.class);
    private final TeamMemberMapper teamMembers = mock(TeamMemberMapper.class);
    private final ProjectAccessService access =
            new ProjectAccessService(projects, projectMembers, teams, teamMembers);

    @Test
    void teamManagerRuleIsDerivedInOnePlace() {
        Long teamId = TestIds.next();
        Long owner = TestIds.next();
        Long admin = TestIds.next();
        Long member = TestIds.next();
        Long outsider = TestIds.next();
        TeamEntity team = team(teamId, owner);
        when(teams.selectById(teamId)).thenReturn(team);
        when(teamMembers.selectByTeamAndUser(teamId, owner))
                .thenReturn(teamMember(teamId, owner, "TEAM_OWNER"));
        when(teamMembers.selectByTeamAndUser(teamId, admin))
                .thenReturn(teamMember(teamId, admin, "TEAM_ADMIN"));
        when(teamMembers.selectByTeamAndUser(teamId, member))
                .thenReturn(teamMember(teamId, member, "TEAM_MEMBER"));

        assertEquals("TEAM_OWNER", access.requireTeamManager(team, owner));
        assertEquals("TEAM_ADMIN", access.requireTeamManager(team, admin));
        access.requireTeamOwner(team, owner);
        assertEquals("TEAM_MEMBER", access.teamRole(team, member));

        ApiException denied =
                assertThrows(ApiException.class, () -> access.requireTeamManager(team, member));
        assertEquals("TEAM_ADMIN_REQUIRED", denied.code());
        ApiException notOwner =
                assertThrows(ApiException.class, () -> access.requireTeamOwner(team, admin));
        assertEquals("TEAM_OWNER_REQUIRED", notOwner.code());
        ApiException unknown =
                assertThrows(ApiException.class, () -> access.requireTeamManager(team, outsider));
        assertEquals("TEAM_RESOURCE_NOT_FOUND", unknown.code());
    }

    @Test
    void canonicalTeamOwnerIsAdminWithoutProjectMembership() {
        Long projectId = TestIds.next();
        Long teamId = TestIds.next();
        Long owner = TestIds.next();
        ProjectEntity project = project(projectId, teamId);
        when(projects.selectById(projectId)).thenReturn(project);
        when(teams.selectById(teamId)).thenReturn(team(teamId, owner));
        when(teamMembers.selectByTeamAndUser(teamId, owner))
                .thenReturn(teamMember(teamId, owner, "TEAM_OWNER"));

        access.requireProjectAdmin(projectId, owner);

        assertEquals("PROJECT_ADMIN", access.requireProjectMember(projectId, owner));
    }

    @Test
    void extraOwnerRoleDoesNotGrantProjectFallback() {
        Long projectId = TestIds.next();
        Long teamId = TestIds.next();
        Long canonicalOwner = TestIds.next();
        Long extraOwner = TestIds.next();
        when(projects.selectById(projectId)).thenReturn(project(projectId, teamId));
        when(teams.selectById(teamId)).thenReturn(team(teamId, canonicalOwner));
        when(teamMembers.selectByTeamAndUser(teamId, extraOwner))
                .thenReturn(teamMember(teamId, extraOwner, "TEAM_OWNER"));

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> access.requireProjectAdmin(projectId, extraOwner));

        assertEquals("PROJECT_NOT_FOUND", error.code());
    }

    @Test
    void teamAdminWithoutProjectMembershipDoesNotGetProjectFallback() {
        Long projectId = TestIds.next();
        Long teamId = TestIds.next();
        Long admin = TestIds.next();
        when(projects.selectById(projectId)).thenReturn(project(projectId, teamId));
        when(teams.selectById(teamId)).thenReturn(team(teamId, TestIds.next()));
        when(teamMembers.selectByTeamAndUser(teamId, admin))
                .thenReturn(teamMember(teamId, admin, "TEAM_ADMIN"));

        ApiException error =
                assertThrows(
                        ApiException.class, () -> access.requireProjectAdmin(projectId, admin));

        assertEquals("PROJECT_NOT_FOUND", error.code());
    }

    @Test
    void teamAdminWithProjectMembershipUsesStoredProjectRole() {
        Long projectId = TestIds.next();
        Long teamId = TestIds.next();
        Long admin = TestIds.next();
        when(projects.selectById(projectId)).thenReturn(project(projectId, teamId));
        when(teams.selectById(teamId)).thenReturn(team(teamId, TestIds.next()));
        when(teamMembers.selectByTeamAndUser(teamId, admin))
                .thenReturn(teamMember(teamId, admin, "TEAM_ADMIN"));
        qg.qgent.project.entity.ProjectMemberEntity membership =
                new qg.qgent.project.entity.ProjectMemberEntity();
        membership.setProjectId(projectId);
        membership.setUserId(admin);
        membership.setRole("PROJECT_ADMIN");
        when(projectMembers.selectByProjectAndUser(projectId, admin)).thenReturn(membership);

        access.requireProjectAdmin(projectId, admin);

        assertEquals("PROJECT_ADMIN", access.requireProjectMember(projectId, admin));
    }

    @Test
    void projectMemberCannotAdministerProject() {
        Long projectId = TestIds.next();
        Long teamId = TestIds.next();
        Long member = TestIds.next();
        when(projects.selectById(projectId)).thenReturn(project(projectId, teamId));
        when(teams.selectById(teamId)).thenReturn(team(teamId, TestIds.next()));
        when(teamMembers.selectByTeamAndUser(teamId, member))
                .thenReturn(teamMember(teamId, member, "TEAM_MEMBER"));
        qg.qgent.project.entity.ProjectMemberEntity membership =
                new qg.qgent.project.entity.ProjectMemberEntity();
        membership.setProjectId(projectId);
        membership.setUserId(member);
        membership.setRole("PROJECT_MEMBER");
        when(projectMembers.selectByProjectAndUser(projectId, member)).thenReturn(membership);

        ApiException error =
                assertThrows(
                        ApiException.class, () -> access.requireProjectAdmin(projectId, member));

        assertEquals("PROJECT_ADMIN_REQUIRED", error.code());
    }

    private ProjectEntity project(Long id, Long teamId) {
        ProjectEntity value = new ProjectEntity();
        value.setId(id);
        value.setTeamId(teamId);
        value.setStatus("ACTIVE");
        return value;
    }

    private TeamEntity team(Long id, Long owner) {
        TeamEntity value = new TeamEntity();
        value.setId(id);
        value.setOwnerUserId(owner);
        return value;
    }

    private TeamMemberEntity teamMember(Long teamId, Long userId, String role) {
        TeamMemberEntity value = new TeamMemberEntity();
        value.setTeamId(teamId);
        value.setUserId(userId);
        value.setRole(role);
        return value;
    }
}
