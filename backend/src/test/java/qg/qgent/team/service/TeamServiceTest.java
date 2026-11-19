package qg.qgent.team.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qg.qgent.auth.service.TokenService;
import qg.qgent.common.storage.AvatarStorageService;
import qg.qgent.common.web.ApiException;
import qg.qgent.notification.service.NotificationService;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.mapper.ProjectMemberMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.support.TestIds;
import qg.qgent.team.dto.TeamCreateRequest;
import qg.qgent.team.dto.TeamMemberInviteRequest;
import qg.qgent.team.dto.TeamMemberUpdateRequest;
import qg.qgent.team.dto.TeamResponse;
import qg.qgent.team.dto.TeamUpdateRequest;
import qg.qgent.team.entity.TeamEntity;
import qg.qgent.team.entity.TeamInvitationEntity;
import qg.qgent.team.entity.TeamMemberEntity;
import qg.qgent.team.mail.TeamInvitationMailer;
import qg.qgent.team.mapper.TeamInvitationMapper;
import qg.qgent.team.mapper.TeamMapper;
import qg.qgent.team.mapper.TeamMemberMapper;
import qg.qgent.team.mapper.view.TeamMemberView;
import qg.qgent.team.mapper.view.TeamMembershipView;
import qg.qgent.user.entity.UserEntity;
import qg.qgent.user.mapper.UserMapper;

class TeamServiceTest {

    /**
     * 纯单元测试未启动 MyBatis/Spring，lambda 包装器依赖实体 TableInfo；显式注册
     * 涉及到的实体，避免裸 JVM 下懒初始化列缓存的行为差异导致测试偶发失败。
     */
    @BeforeEach
    void assignGeneratedIds() {
        when(invitationMapper.insert(any(TeamInvitationEntity.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(0, TeamInvitationEntity.class).setId(TestIds.next());
                    return 1;
                });
    }

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, TeamEntity.class);
        TableInfoHelper.initTableInfo(assistant, TeamMemberEntity.class);
        TableInfoHelper.initTableInfo(assistant, TeamInvitationEntity.class);
    }

    private final TeamMapper teamMapper = mock(TeamMapper.class);
    private final TeamMemberMapper memberMapper = mock(TeamMemberMapper.class);
    private final TeamInvitationMapper invitationMapper = mock(TeamInvitationMapper.class);
    private final ProjectMemberMapper projectMemberMapper = mock(ProjectMemberMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final TeamDisbandService teamDisbandService = mock(TeamDisbandService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final ProjectAccessService access =
            new ProjectAccessService(
                    mock(ProjectMapper.class), projectMemberMapper, teamMapper, memberMapper);
    private final TeamService service =
            new TeamService(
                    teamMapper,
                    memberMapper,
                    invitationMapper,
                    projectMemberMapper,
                    access,
                    userMapper,
                    mock(TokenService.class),
                    mock(AvatarStorageService.class),
                    mock(TeamInvitationMailer.class),
                    teamDisbandService,
                    notificationService,
                    mock(EventService.class));

    @Test
    void createAddsCreatorAsOwner() {
        Long actor = TestIds.next();
        TeamCreateRequest request = new TeamCreateRequest();
        request.setName(" 研发团队 ");

        // create() 插入后重查团队以带回数据库生成的 created_at，这里返回刚插入的实体
        java.util.concurrent.atomic.AtomicReference<TeamEntity> inserted =
                new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            inserted.set(inv.getArgument(0));
                            inserted.get().setId(TestIds.next());
                            return 1;
                        })
                .when(teamMapper)
                .insert(any(TeamEntity.class));
        when(teamMapper.selectById(any(Long.class))).thenAnswer(inv -> inserted.get());

        TeamResponse response = service.create(actor, request);

        assertEquals("研发团队", response.getName());
        assertEquals("TEAM_OWNER", response.getRole());
        assertEquals(0, response.getMemberCount());
        verify(teamMapper).insert(any(TeamEntity.class));
        verify(memberMapper).insert(any(TeamMemberEntity.class));
    }

    @Test
    void ordinaryMemberCannotUpdateTeamRole() {
        Long teamId = TestIds.next();
        Long actor = TestIds.next();
        TeamMemberEntity actorMember = member(teamId, actor, "TEAM_MEMBER");
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team(teamId, actor));
        when(memberMapper.selectByTeamAndUser(teamId, actor)).thenReturn(actorMember);
        TeamMemberUpdateRequest request = new TeamMemberUpdateRequest();
        request.setRole("TEAM_OWNER");

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> service.updateMember(actor, teamId, TestIds.next(), request));

        assertEquals("TEAM_OWNER_REQUIRED", error.code());
    }

    @Test
    void primaryOwnerCannotBeRemoved() {
        Long teamId = TestIds.next();
        Long owner = TestIds.next();
        TeamMemberEntity ownerMember = member(teamId, owner, "TEAM_OWNER");
        when(memberMapper.selectByTeamAndUser(teamId, owner)).thenReturn(ownerMember);
        TeamEntity team = new TeamEntity();
        team.setId(teamId);
        team.setOwnerUserId(owner);
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team);
        ApiException error =
                assertThrows(ApiException.class, () -> service.removeMember(owner, teamId, owner));

        assertEquals("TEAM_OWNER_IMMUTABLE", error.code());
    }

    @Test
    void primaryOwnerCannotBeDowngraded() {
        Long teamId = TestIds.next();
        Long owner = TestIds.next();
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team(teamId, owner));
        when(memberMapper.selectByTeamAndUser(teamId, owner))
                .thenReturn(member(teamId, owner, "TEAM_OWNER"));
        TeamMemberUpdateRequest request = new TeamMemberUpdateRequest();
        request.setRole("TEAM_MEMBER");

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> service.updateMember(owner, teamId, owner, request));

        assertEquals("TEAM_OWNER_IMMUTABLE", error.code());
    }

    @Test
    void extraOwnerRoleCannotRemoveCanonicalOwner() {
        Long teamId = TestIds.next();
        Long primary = TestIds.next();
        Long otherOwner = TestIds.next();
        TeamEntity team = team(teamId, primary);
        TeamMemberEntity primaryMember = member(teamId, primary, "TEAM_OWNER");
        TeamMemberEntity otherMember = member(teamId, otherOwner, "TEAM_OWNER");
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team);
        when(memberMapper.selectByTeamAndUser(teamId, otherOwner)).thenReturn(otherMember);
        when(memberMapper.selectByTeamAndUser(teamId, primary)).thenReturn(primaryMember);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> service.removeMember(otherOwner, teamId, primary));

        assertEquals("TEAM_ADMIN_REQUIRED", error.code());
    }

    @Test
    void memberRoleEndpointCannotCreateAnotherOwner() {
        Long teamId = TestIds.next();
        Long owner = TestIds.next();
        Long memberId = TestIds.next();
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team(teamId, owner));
        when(memberMapper.selectByTeamAndUser(teamId, owner))
                .thenReturn(member(teamId, owner, "TEAM_OWNER"));
        TeamMemberUpdateRequest request = new TeamMemberUpdateRequest();
        request.setRole("TEAM_OWNER");

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> service.updateMember(owner, teamId, memberId, request));

        assertEquals("INVALID_TEAM_OPERATION", error.code());
    }

    @Test
    void removingLastExplicitProjectAdminIsAllowedBecauseTeamOwnerInheritsAdmin() {
        Long teamId = TestIds.next();
        Long owner = TestIds.next();
        Long admin = TestIds.next();
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team(teamId, owner));
        when(memberMapper.selectByTeamAndUser(teamId, owner))
                .thenReturn(member(teamId, owner, "TEAM_OWNER"));
        when(memberMapper.selectByTeamAndUser(teamId, admin))
                .thenReturn(member(teamId, admin, "TEAM_MEMBER"));

        var response = service.removeMember(owner, teamId, admin);

        assertEquals("TEAM_MEMBER", response.getRole());
        verify(projectMemberMapper).deleteByTeamAndUser(teamId, admin);
        verify(memberMapper).deleteByTeamAndUser(teamId, admin);
    }

    @Test
    void removingTeamMemberClearsProjectMemberships() {
        Long teamId = TestIds.next();
        Long owner = TestIds.next();
        Long targetUser = TestIds.next();
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team(teamId, owner));
        when(memberMapper.selectByTeamAndUser(teamId, owner))
                .thenReturn(member(teamId, owner, "TEAM_OWNER"));
        when(memberMapper.selectByTeamAndUser(teamId, targetUser))
                .thenReturn(member(teamId, targetUser, "TEAM_MEMBER"));

        service.removeMember(owner, teamId, targetUser);

        verify(projectMemberMapper).deleteByTeamAndUser(teamId, targetUser);
        verify(memberMapper).deleteByTeamAndUser(teamId, targetUser);
    }

    @Test
    void canonicalOwnerCanNormalizeExtraOwnerRole() {
        Long teamId = TestIds.next();
        Long owner = TestIds.next();
        Long extraOwner = TestIds.next();
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team(teamId, owner));
        when(memberMapper.selectByTeamAndUser(teamId, owner))
                .thenReturn(member(teamId, owner, "TEAM_OWNER"));
        when(memberMapper.selectByTeamAndUser(teamId, extraOwner))
                .thenReturn(member(teamId, extraOwner, "TEAM_OWNER"));
        TeamMemberUpdateRequest request = new TeamMemberUpdateRequest();
        request.setRole("TEAM_MEMBER");

        var response = service.updateMember(owner, teamId, extraOwner, request);

        assertEquals("TEAM_MEMBER", response.getRole());
        verify(memberMapper).updateRole(teamId, extraOwner, "TEAM_MEMBER");
    }

    @Test
    void teamDetailsNormalizeExtraOwnerRole() {
        Long teamId = TestIds.next();
        Long canonicalOwner = TestIds.next();
        Long actor = TestIds.next();
        when(memberMapper.selectByTeamAndUser(teamId, actor))
                .thenReturn(member(teamId, actor, "TEAM_OWNER"));
        when(teamMapper.selectById(teamId)).thenReturn(team(teamId, canonicalOwner));

        var response = service.get(actor, teamId);

        assertEquals("TEAM_MEMBER", response.getRole());
    }

    @Test
    void memberListNormalizesExtraOwnerRole() {
        Long teamId = TestIds.next();
        Long canonicalOwner = TestIds.next();
        Long actor = TestIds.next();
        Long extraOwner = TestIds.next();
        when(memberMapper.selectByTeamAndUser(teamId, actor))
                .thenReturn(member(teamId, actor, "TEAM_MEMBER"));
        when(teamMapper.selectById(teamId)).thenReturn(team(teamId, canonicalOwner));
        TeamMemberView memberView = new TeamMemberView();
        memberView.setTeamId(teamId);
        memberView.setUserId(extraOwner);
        memberView.setRole("TEAM_OWNER");
        when(memberMapper.selectMemberPage(teamId, null, 31))
                .thenReturn(java.util.List.of(memberView));

        var response = service.members(actor, teamId, null, null);

        assertEquals("TEAM_MEMBER", response.getData().get(0).getRole());
    }

    @Test
    void acceptExpiredInvitationFailsWithoutWritingStatus() {
        Long actor = TestIds.next();
        Long invitationId = TestIds.next();
        UserEntity user = new UserEntity();
        user.setId(actor);
        user.setEmail("member@example.com");
        TeamInvitationEntity invitation = new TeamInvitationEntity();
        invitation.setId(invitationId);
        invitation.setEmailNormalized("member@example.com");
        invitation.setStatus("PENDING");
        invitation.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        TokenService tokens = mock(TokenService.class);
        TeamService localService =
                new TeamService(
                        teamMapper,
                        memberMapper,
                        invitationMapper,
                        projectMemberMapper,
                        access,
                        userMapper,
                        tokens,
                        mock(AvatarStorageService.class),
                        mock(TeamInvitationMailer.class),
                        mock(TeamDisbandService.class),
                        mock(NotificationService.class),
                        mock(EventService.class));
        when(userMapper.selectById(actor)).thenReturn(user);
        when(tokens.hash("raw-token")).thenReturn(new byte[] {1});
        when(invitationMapper.selectOne(any())).thenReturn(invitation);

        ApiException error =
                assertThrows(ApiException.class, () -> localService.accept(actor, "raw-token"));

        assertEquals("INVITATION_EXPIRED", error.code());
        // 过期状态按 expires_at 动态展示，不再写入数据库
        verify(invitationMapper, never()).updateById(any(TeamInvitationEntity.class));
    }

    @Test
    void revokeExpiredInvitationFailsWithoutWritingStatus() {
        Long teamId = TestIds.next();
        Long owner = TestIds.next();
        Long invitationId = TestIds.next();
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team(teamId, owner));
        when(memberMapper.selectByTeamAndUser(teamId, owner))
                .thenReturn(member(teamId, owner, "TEAM_OWNER"));
        TeamInvitationEntity invitation = new TeamInvitationEntity();
        invitation.setId(invitationId);
        invitation.setTeamId(teamId);
        invitation.setStatus("PENDING");
        invitation.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        when(invitationMapper.selectOne(any())).thenReturn(invitation);

        ApiException error =
                assertThrows(ApiException.class, () -> service.revoke(owner, teamId, invitationId));

        assertEquals("INVITATION_EXPIRED", error.code());
        verify(invitationMapper, never()).updateById(any(TeamInvitationEntity.class));
    }

    @Test
    void inviteLocksTeamBeforeCheckingForPendingInvitation() {
        Long teamId = TestIds.next();
        Long owner = TestIds.next();
        TeamMemberEntity ownerMember = member(teamId, owner, "TEAM_OWNER");
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team(teamId, owner));
        when(memberMapper.selectByTeamAndUser(teamId, owner)).thenReturn(ownerMember);
        TokenService tokens = mock(TokenService.class);
        when(tokens.opaque()).thenReturn("raw-token");
        when(tokens.hash("raw-token")).thenReturn(new byte[] {1});
        TeamService localService =
                new TeamService(
                        teamMapper,
                        memberMapper,
                        invitationMapper,
                        projectMemberMapper,
                        access,
                        userMapper,
                        tokens,
                        mock(AvatarStorageService.class),
                        mock(TeamInvitationMailer.class),
                        mock(TeamDisbandService.class),
                        mock(NotificationService.class),
                        mock(EventService.class));
        TeamMemberInviteRequest request = new TeamMemberInviteRequest();
        request.setEmail("new@example.com");
        request.setRole("TEAM_MEMBER");
        request.setExpiresInDays(7);

        org.springframework.transaction.support.TransactionSynchronizationManager
                .initSynchronization();
        try {
            localService.invite(owner, teamId, request);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .clearSynchronization();
        }

        var order = inOrder(teamMapper, memberMapper, invitationMapper);
        order.verify(teamMapper).selectByIdForUpdate(teamId);
        order.verify(memberMapper).selectByTeamAndUser(teamId, owner);
        order.verify(invitationMapper).selectOne(any());
    }

    @Test
    void listUsesDefaultThirtyAndReturnsCursor() {
        Long actor = TestIds.next();
        java.util.List<TeamMembershipView> firstRows = new java.util.ArrayList<>();
        for (int i = 0; i < 31; i++) {
            Long teamId = i + 1L;
            TeamMembershipView row = new TeamMembershipView();
            row.setId(teamId);
            row.setOwnerUserId(TestIds.next());
            row.setName("team-" + i);
            row.setRole(i == 0 ? "TEAM_OWNER" : "TEAM_MEMBER");
            firstRows.add(row);
        }
        when(teamMapper.selectMembershipPage(actor, null, 31)).thenReturn(firstRows);
        when(teamMapper.selectMembershipPage(actor, 30L, 31))
                .thenReturn(java.util.List.of(firstRows.get(30)));

        var page = service.list(actor, null, null);

        assertEquals(30, page.getData().size());
        assertEquals("TEAM_MEMBER", page.getData().get(0).getRole());
        assertEquals(true, page.getPage().isHasMore());

        var nextPage = service.list(actor, page.getPage().getNextCursor(), null);
        assertEquals(1, nextPage.getData().size());
        assertEquals(false, nextPage.getPage().isHasMore());
    }

    @Test
    void cursorCannotBeReusedAcrossListScopes() {
        Long actor = TestIds.next();
        Long teamId = TestIds.next();
        TeamMembershipView first = new TeamMembershipView();
        first.setId(1L);
        first.setOwnerUserId(TestIds.next());
        first.setName("one");
        first.setRole("TEAM_MEMBER");
        TeamMembershipView second = new TeamMembershipView();
        second.setId(2L);
        second.setOwnerUserId(TestIds.next());
        second.setName("two");
        second.setRole("TEAM_MEMBER");
        when(teamMapper.selectMembershipPage(actor, null, 2))
                .thenReturn(java.util.List.of(first, second));
        String teamListCursor = service.list(actor, null, 1).getPage().getNextCursor();
        when(memberMapper.selectByTeamAndUser(teamId, actor))
                .thenReturn(member(teamId, actor, "TEAM_MEMBER"));
        when(teamMapper.selectById(teamId)).thenReturn(team(teamId, TestIds.next()));

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> service.members(actor, teamId, teamListCursor, 1));

        assertEquals("INVALID_CURSOR", error.code());
    }

    @Test
    void onlyOwnerCanDisbandTeam() {
        Long teamId = TestIds.next();
        Long owner = TestIds.next();
        Long memberId = TestIds.next();
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team(teamId, owner));
        when(memberMapper.selectByTeamAndUser(teamId, memberId))
                .thenReturn(member(teamId, memberId, "TEAM_MEMBER"));

        ApiException error =
                assertThrows(ApiException.class, () -> service.disband(memberId, teamId));

        assertEquals("TEAM_OWNER_REQUIRED", error.code());
        verify(teamDisbandService, org.mockito.Mockito.never()).deleteTeam(any(), any());
    }

    @Test
    void ownerDisbandDelegatesToDisbandService() {
        Long teamId = TestIds.next();
        Long owner = TestIds.next();
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team(teamId, owner));
        when(memberMapper.selectByTeamAndUser(teamId, owner))
                .thenReturn(member(teamId, owner, "TEAM_OWNER"));

        TeamResponse response = service.disband(owner, teamId);

        assertEquals("TEAM_OWNER", response.getRole());
        assertEquals(teamId.toString(), response.getId());
        verify(teamDisbandService).deleteTeam(eq(teamId), any());
    }

    @Test
    void disbandUnknownTeamReturnsNotFound() {
        Long actor = TestIds.next();
        when(teamMapper.selectByIdForUpdate(any())).thenReturn(null);

        ApiException error =
                assertThrows(ApiException.class, () -> service.disband(actor, TestIds.next()));

        assertEquals("TEAM_RESOURCE_NOT_FOUND", error.code());
        verify(teamDisbandService, org.mockito.Mockito.never()).deleteTeam(any(), any());
    }

    @Test
    void teamAdminCanUpdateTeamAndRemoveOrdinaryMember() {
        Long teamId = TestIds.next();
        Long owner = TestIds.next();
        Long admin = TestIds.next();
        Long member = TestIds.next();
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team(teamId, owner));
        when(memberMapper.selectByTeamAndUser(teamId, admin))
                .thenReturn(member(teamId, admin, "TEAM_ADMIN"));
        when(memberMapper.selectByTeamAndUser(teamId, member))
                .thenReturn(member(teamId, member, "TEAM_MEMBER"));

        TeamUpdateRequest update = new TeamUpdateRequest();
        update.setName("新名称");
        var updated = service.update(admin, teamId, update);
        var removed = service.removeMember(admin, teamId, member);

        assertEquals("TEAM_ADMIN", updated.getRole());
        assertEquals("TEAM_MEMBER", removed.getRole());
        verify(projectMemberMapper).deleteByTeamAndUser(teamId, member);
        verify(memberMapper).deleteByTeamAndUser(teamId, member);
    }

    @Test
    void teamAdminCannotRemoveAnotherTeamAdmin() {
        Long teamId = TestIds.next();
        Long owner = TestIds.next();
        Long admin = TestIds.next();
        Long otherAdmin = TestIds.next();
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team(teamId, owner));
        when(memberMapper.selectByTeamAndUser(teamId, admin))
                .thenReturn(member(teamId, admin, "TEAM_ADMIN"));
        when(memberMapper.selectByTeamAndUser(teamId, otherAdmin))
                .thenReturn(member(teamId, otherAdmin, "TEAM_ADMIN"));

        ApiException error =
                assertThrows(
                        ApiException.class, () -> service.removeMember(admin, teamId, otherAdmin));

        assertEquals("TEAM_OWNER_REQUIRED", error.code());
        verify(projectMemberMapper, never()).deleteByTeamAndUser(teamId, otherAdmin);
    }

    @Test
    void teamAdminCannotChangeMemberRoles() {
        Long teamId = TestIds.next();
        Long owner = TestIds.next();
        Long admin = TestIds.next();
        Long member = TestIds.next();
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team(teamId, owner));
        when(memberMapper.selectByTeamAndUser(teamId, admin))
                .thenReturn(member(teamId, admin, "TEAM_ADMIN"));
        TeamMemberUpdateRequest request = new TeamMemberUpdateRequest();
        request.setRole("TEAM_MEMBER");

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> service.updateMember(admin, teamId, member, request));

        assertEquals("TEAM_OWNER_REQUIRED", error.code());
        verify(memberMapper, never()).updateRole(teamId, member, "TEAM_MEMBER");
    }

    @Test
    void ownerCanPromoteMemberAndDemoteTeamAdmin() {
        Long teamId = TestIds.next();
        Long owner = TestIds.next();
        Long member = TestIds.next();
        Long admin = TestIds.next();
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team(teamId, owner));
        when(memberMapper.selectByTeamAndUser(teamId, owner))
                .thenReturn(member(teamId, owner, "TEAM_OWNER"));
        when(memberMapper.selectByTeamAndUser(teamId, member))
                .thenReturn(member(teamId, member, "TEAM_MEMBER"));
        when(memberMapper.selectByTeamAndUser(teamId, admin))
                .thenReturn(member(teamId, admin, "TEAM_ADMIN"));

        TeamMemberUpdateRequest promote = new TeamMemberUpdateRequest();
        promote.setRole("TEAM_ADMIN");
        TeamMemberUpdateRequest demote = new TeamMemberUpdateRequest();
        demote.setRole("TEAM_MEMBER");

        assertEquals("TEAM_ADMIN", service.updateMember(owner, teamId, member, promote).getRole());
        assertEquals("TEAM_MEMBER", service.updateMember(owner, teamId, admin, demote).getRole());
        verify(memberMapper).updateRole(teamId, member, "TEAM_ADMIN");
        verify(memberMapper).updateRole(teamId, admin, "TEAM_MEMBER");
    }

    @Test
    void teamDetailsAndMemberListReturnTeamAdminRole() {
        Long teamId = TestIds.next();
        Long owner = TestIds.next();
        Long actor = TestIds.next();
        Long admin = TestIds.next();
        when(memberMapper.selectByTeamAndUser(teamId, actor))
                .thenReturn(member(teamId, actor, "TEAM_ADMIN"));
        when(teamMapper.selectById(teamId)).thenReturn(team(teamId, owner));
        assertEquals("TEAM_ADMIN", service.get(actor, teamId).getRole());

        TeamMemberView view = new TeamMemberView();
        view.setTeamId(teamId);
        view.setUserId(admin);
        view.setRole("TEAM_ADMIN");
        when(memberMapper.selectMemberPage(teamId, null, 31)).thenReturn(java.util.List.of(view));
        var page = service.members(actor, teamId, null, null);
        assertEquals("TEAM_ADMIN", page.getData().getFirst().getRole());
    }

    @Test
    void extraOwnerRoleDoesNotGrantManagerPermission() {
        Long teamId = TestIds.next();
        Long canonicalOwner = TestIds.next();
        Long extraOwner = TestIds.next();
        when(teamMapper.selectByIdForUpdate(teamId)).thenReturn(team(teamId, canonicalOwner));
        when(memberMapper.selectByTeamAndUser(teamId, extraOwner))
                .thenReturn(member(teamId, extraOwner, "TEAM_OWNER"));
        TeamUpdateRequest request = new TeamUpdateRequest();
        request.setName("新名称");

        ApiException error =
                assertThrows(ApiException.class, () -> service.update(extraOwner, teamId, request));

        assertEquals("TEAM_ADMIN_REQUIRED", error.code());
    }

    @Test
    void teamAdminCanListInvitations() {
        Long teamId = TestIds.next();
        Long owner = TestIds.next();
        Long admin = TestIds.next();
        when(teamMapper.selectById(teamId)).thenReturn(team(teamId, owner));
        when(memberMapper.selectByTeamAndUser(teamId, admin))
                .thenReturn(member(teamId, admin, "TEAM_ADMIN"));
        when(invitationMapper.selectInvitationPage(teamId, null, 31))
                .thenReturn(java.util.List.of());

        var page = service.invitations(admin, teamId, null, null);

        assertEquals(0, page.getData().size());
    }

    private TeamMemberEntity member(Long teamId, Long userId, String role) {
        TeamMemberEntity member = new TeamMemberEntity();
        member.setTeamId(teamId);
        member.setUserId(userId);
        member.setRole(role);
        return member;
    }

    private TeamEntity team(Long teamId, Long ownerId) {
        TeamEntity team = new TeamEntity();
        team.setId(teamId);
        team.setOwnerUserId(ownerId);
        team.setStatus("ACTIVE");
        return team;
    }
}
