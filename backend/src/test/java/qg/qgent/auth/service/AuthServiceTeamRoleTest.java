package qg.qgent.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import qg.qgent.auth.mail.VerificationCodeMailer;
import qg.qgent.common.storage.AvatarStorageService;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.mapper.view.ProjectMembershipView;
import qg.qgent.support.TestIds;
import qg.qgent.team.entity.TeamEntity;
import qg.qgent.team.entity.TeamMemberEntity;
import qg.qgent.team.mapper.TeamMapper;
import qg.qgent.team.mapper.TeamMemberMapper;
import qg.qgent.user.entity.UserEntity;
import qg.qgent.user.mapper.UserMapper;

class AuthServiceTeamRoleTest {
    @Test
    void meReturnsTeamAdminRole() {
        Long userId = TestIds.next();
        Long teamId = TestIds.next();
        UserMapper userMapper = mock(UserMapper.class);
        TeamMapper teamMapper = mock(TeamMapper.class);
        TeamMemberMapper memberMapper = mock(TeamMemberMapper.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        when(passwords.encode("qgents-dummy-password-not-used")).thenReturn("dummy");
        AuthService service =
                new AuthService(
                        userMapper,
                        mock(RefreshTokenStore.class),
                        mock(VerificationCodeStore.class),
                        teamMapper,
                        memberMapper,
                        mock(ProjectMapper.class),
                        mock(RsaPasswordDecryptor.class),
                        passwords,
                        mock(TokenService.class),
                        mock(VerificationCodeMailer.class),
                        mock(RateLimiter.class),
                        mock(AvatarStorageService.class));
        UserEntity user = new UserEntity();
        user.setId(userId);
        TeamMemberEntity membership = new TeamMemberEntity();
        membership.setTeamId(teamId);
        membership.setUserId(userId);
        membership.setRole("TEAM_ADMIN");
        TeamEntity team = new TeamEntity();
        team.setId(teamId);
        team.setOwnerUserId(TestIds.next());
        team.setName("team");
        team.setStatus("ACTIVE");
        when(userMapper.selectById(userId)).thenReturn(user);
        when(memberMapper.selectByUserId(userId)).thenReturn(List.of(membership));
        when(teamMapper.selectBatchIds(anyCollection())).thenReturn(List.of(team));

        var response = service.me(userId);

        assertEquals("TEAM_ADMIN", response.getTeams().get(0).getRole());
    }

    @Test
    void meDoesNotExposeExtraOwnerRoleAsCanonicalOwner() {
        Long userId = TestIds.next();
        Long canonicalOwner = TestIds.next();
        Long teamId = TestIds.next();
        UserMapper userMapper = mock(UserMapper.class);
        TeamMapper teamMapper = mock(TeamMapper.class);
        TeamMemberMapper memberMapper = mock(TeamMemberMapper.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        when(passwords.encode("qgents-dummy-password-not-used")).thenReturn("dummy");
        AuthService service =
                new AuthService(
                        userMapper,
                        mock(RefreshTokenStore.class),
                        mock(VerificationCodeStore.class),
                        teamMapper,
                        memberMapper,
                        mock(ProjectMapper.class),
                        mock(RsaPasswordDecryptor.class),
                        passwords,
                        mock(TokenService.class),
                        mock(VerificationCodeMailer.class),
                        mock(RateLimiter.class),
                        mock(AvatarStorageService.class));
        UserEntity user = new UserEntity();
        user.setId(userId);
        TeamMemberEntity membership = new TeamMemberEntity();
        membership.setTeamId(teamId);
        membership.setUserId(userId);
        membership.setRole("TEAM_OWNER");
        TeamEntity team = new TeamEntity();
        team.setId(teamId);
        team.setOwnerUserId(canonicalOwner);
        team.setName("team");
        team.setStatus("ACTIVE");
        when(userMapper.selectById(userId)).thenReturn(user);
        when(memberMapper.selectByUserId(userId)).thenReturn(List.of(membership));
        when(teamMapper.selectBatchIds(anyCollection())).thenReturn(List.of(team));

        var response = service.me(userId);

        assertEquals("TEAM_MEMBER", response.getTeams().get(0).getRole());
    }

    @Test
    void meIncludesCanonicalOwnerProjectsWithoutProjectMembership() {
        Long userId = TestIds.next();
        Long teamId = TestIds.next();
        Long projectId = TestIds.next();
        UserMapper userMapper = mock(UserMapper.class);
        TeamMapper teamMapper = mock(TeamMapper.class);
        TeamMemberMapper memberMapper = mock(TeamMemberMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        when(passwords.encode("qgents-dummy-password-not-used")).thenReturn("dummy");
        AuthService service =
                new AuthService(
                        userMapper,
                        mock(RefreshTokenStore.class),
                        mock(VerificationCodeStore.class),
                        teamMapper,
                        memberMapper,
                        projectMapper,
                        mock(RsaPasswordDecryptor.class),
                        passwords,
                        mock(TokenService.class),
                        mock(VerificationCodeMailer.class),
                        mock(RateLimiter.class),
                        mock(AvatarStorageService.class));
        UserEntity user = new UserEntity();
        user.setId(userId);
        ProjectMembershipView project = new ProjectMembershipView();
        project.setId(projectId);
        project.setTeamId(teamId);
        project.setName("owner-project");
        project.setRole("PROJECT_ADMIN");
        project.setStatus("ACTIVE");
        when(userMapper.selectById(userId)).thenReturn(user);
        when(memberMapper.selectByUserId(userId)).thenReturn(List.of());
        when(projectMapper.selectAccessibleByUser(userId)).thenReturn(List.of(project));

        var response = service.me(userId);

        assertEquals(1, response.getProjects().size());
        assertEquals("PROJECT_ADMIN", response.getProjects().get(0).getRole());
    }
}
