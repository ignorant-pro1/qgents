package qg.qgent.team.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import qg.qgent.auth.service.TokenService;
import qg.qgent.common.dto.KeysetPagination;
import qg.qgent.common.dto.PageSlice;
import qg.qgent.common.storage.AvatarStorageService;
import qg.qgent.common.web.ApiException;
import qg.qgent.notification.service.NotificationService;
import qg.qgent.project.mapper.ProjectMemberMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.team.dto.*;
import qg.qgent.team.entity.TeamEntity;
import qg.qgent.team.entity.TeamInvitationEntity;
import qg.qgent.team.entity.TeamMemberEntity;
import qg.qgent.team.mail.TeamInvitationMailer;
import qg.qgent.team.mapper.TeamInvitationMapper;
import qg.qgent.team.mapper.TeamMapper;
import qg.qgent.team.mapper.TeamMemberMapper;
import qg.qgent.team.mapper.view.TeamMemberView;
import qg.qgent.team.mapper.view.TeamMembershipView;
import qg.qgent.user.dto.AvatarConfirmResponse;
import qg.qgent.user.entity.UserEntity;
import qg.qgent.user.mapper.UserMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 管理团队资料、成员和邀请。
 */
@Service
@RequiredArgsConstructor
public class TeamService {
    private final TeamMapper teamMapper;
    private final TeamMemberMapper memberMapper;
    private final TeamInvitationMapper invitationMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectAccessService access;
    private final UserMapper userMapper;
    private final TokenService tokens;
    private final AvatarStorageService avatarStorage;
    private final TeamInvitationMailer invitationMailer;
    private final TeamDisbandService teamDisbandService;
    private final NotificationService notificationService;
    private final EventService eventService;

    /**
     * 创建团队
     */
    @Transactional
    public TeamResponse create(Long actor, TeamCreateRequest request) {
        TeamEntity team = new TeamEntity();
        team.setOwnerUserId(actor);
        team.setName(request.getName().trim());
        team.setDescription(request.getDescription());
        team.setStatus("ACTIVE");
        teamMapper.insert(team);

        TeamMemberEntity member = new TeamMemberEntity();
        member.setTeamId(team.getId());
        member.setUserId(actor);
        member.setRole("TEAM_OWNER");
        memberMapper.insert(member);
        // 重查一次以带回数据库生成的 created_at
        return team(teamMapper.selectById(team.getId()), "TEAM_OWNER");
    }

    /**
     * 获取团队列表
     */
    public PageSlice<TeamResponse> list(Long actor, String cursor, Integer limit) {
        int pageSize = KeysetPagination.pageSize(limit);
        String scope = "teams";
        Long anchor = KeysetPagination.decodeCursor(cursor, scope);

        List<TeamMembershipView> rows =
            teamMapper.selectMembershipPage(actor, anchor, pageSize + 1);
        // 转成指定的分页响应，memberCount/description/createdAt 来自列表聚合查询
        return KeysetPagination.keysetPage(
            rows,
            pageSize,
            scope,
            TeamMembershipView::getId,
            row -> TeamViews.response(row, actor));
    }

    /**
     * 获取团队详情
     */
    public TeamResponse get(Long actor, Long teamId) {
        TeamEntity team = requireTeam(teamId);
        return team(team, access.requireTeamMemberRole(team, actor));
    }

    /**
     * 更新团队信息；团队所有者和管理员均可修改。
     */
    @Transactional
    public TeamResponse update(Long actor, Long teamId, TeamUpdateRequest request) {
        TeamEntity team = requireTeamForUpdate(teamId);

        String actorRole = access.requireTeamManager(team, actor);

        if (request.getName() != null) {
            team.setName(request.getName().trim());
        }
        if (request.getDescription() != null) {
            team.setDescription(request.getDescription());
        }
        // 头像只能通过上传确认接口设置；空串表示清空
        if (request.getAvatarUrl() != null) {
            if (!request.getAvatarUrl().isBlank()) {
                throw invalid("头像只能通过头像上传接口设置");
            }
            String previousAvatar = team.getAvatarUrl();
            team.setAvatarUrl(null);
            teamMapper.updateById(team);
            avatarStorage.deleteReplacedAfterCommit("teams", teamId, previousAvatar, null);
        } else {
            teamMapper.updateById(team);
        }
        return team(team, actorRole);
    }

    /**
     * 签发团队头像直传凭证；权限与修改团队资料一致（所有者或管理员）。
     */
    public AvatarStorageService.AvatarCredential avatarCredential(
        Long actor, Long teamId, String mediaType, Long sizeBytes) {
        access.requireTeamManager(requireTeam(teamId), actor);
        return avatarStorage.createCredential("teams", teamId, mediaType, sizeBytes);
    }

    /**
     * 确认团队头像上传：直接写入头像 URL 并返回，前端无需再调修改接口。
     */
    @Transactional
    public AvatarConfirmResponse updateAvatar(Long actor, Long teamId, String objectKey) {
        TeamEntity team = requireTeamForUpdate(teamId);
        access.requireTeamManager(team, actor);
        String publicUrl = avatarStorage.confirm("teams", teamId, objectKey);
        String previousAvatar = team.getAvatarUrl();
        team.setAvatarUrl(publicUrl);
        teamMapper.updateById(team);
        avatarStorage.deleteReplacedAfterCommit("teams", teamId, previousAvatar, objectKey);
        return new AvatarConfirmResponse(publicUrl);
    }

    /**
     * 获取团队成员列表
     */
    public PageSlice<TeamMemberResponse> members(
        Long actor, Long teamId, String cursor, Integer limit) {
        TeamEntity team = requireTeam(teamId);
        access.requireTeamMemberRole(team, actor);
        int pageSize = KeysetPagination.pageSize(limit);
        String scope = "team-members:" + teamId;
        Long anchor = KeysetPagination.decodeCursor(cursor, scope);
        List<TeamMemberView> rows = memberMapper.selectMemberPage(teamId, anchor, pageSize + 1);
        return KeysetPagination.keysetPage(
            rows,
            pageSize,
            scope,
            TeamMemberView::getUserId,
            member -> {
                TeamMemberResponse response =
                    new TeamMemberResponse(
                        member.getUserId().toString(),
                        ProjectAccessService.effectiveTeamRole(
                            team.getOwnerUserId(),
                            member.getUserId(),
                            member.getRole()));
                response.setDisplayName(member.getDisplayName());
                response.setEmail(member.getEmail());
                response.setAvatarUrl(member.getAvatarUrl());
                return response;
            });
    }

    /**
     * 邀请成员加入团队；所有者和管理员均可邀请普通成员。
     */
    @Transactional
    public TeamInvitationResponse invite(Long actor, Long teamId, TeamMemberInviteRequest request) {
        TeamEntity team = requireTeamForUpdate(teamId);

        access.requireTeamManager(team, actor);
        if (!"TEAM_MEMBER".equals(request.getRole())) {
            throw invalid("邀请角色目前仅支持 TEAM_MEMBER");
        }
        if (request.getExpiresInDays() > 30) {
            throw invalid("邀请有效期不能超过30天");
        }
        String email = normalize(request.getEmail());

        UserEntity invitedUser =
            userMapper.selectOne(
                Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getEmail, email));
        if (invitedUser != null
            && memberMapper.selectByTeamAndUser(teamId, invitedUser.getId()) != null) {
            throw conflict("ALREADY_TEAM_MEMBER", "该用户已是团队成员");
        }
        TeamInvitationEntity pending =
            invitationMapper.selectOne(
                Wrappers.<TeamInvitationEntity>lambdaQuery()
                    .eq(TeamInvitationEntity::getTeamId, teamId)
                    .eq(TeamInvitationEntity::getEmailNormalized, email)
                    .eq(TeamInvitationEntity::getStatus, "PENDING")
                    .gt(TeamInvitationEntity::getExpiresAt, now())
                    .last("LIMIT 1"));
        if (pending != null) {
            throw conflict("INVITATION_ALREADY_PENDING", "该邮箱已有待处理邀请");
        }

        String rawToken = tokens.opaque();

        TeamInvitationEntity invitation = new TeamInvitationEntity();
        invitation.setTeamId(teamId);
        invitation.setInvitedBy(actor);
        invitation.setEmailNormalized(email);
        invitation.setTokenHash(tokens.hash(rawToken));
        invitation.setStatus("PENDING");
        invitation.setExpiresAt(now().plusDays(request.getExpiresInDays()));
        invitationMapper.insert(invitation);
        // 注册一个事务同步，确保在事务提交后发送邮件
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invitationMailer.send(email, rawToken);
                }
            });
        // 被邀请邮箱已注册时，通知被邀请人（未注册用户继续走邮件邀请）
        if (invitedUser != null) {
            UserEntity inviter = userMapper.selectById(actor);
            notificationService.notify(
                invitedUser.getId(),
                null,
                null,
                "INVITED",
                "你被邀请加入团队 " + team.getName(),
                inviter == null ? null : "邀请人：" + inviter.getDisplayName(),
                teamId.toString());
        }
        return invitation(invitation);
    }

    /**
     * 获取团队邀请列表；所有者和管理员均可查看。
     */
    public PageSlice<TeamInvitationResponse> invitations(
        Long actor, Long teamId, String cursor, Integer limit) {
        access.requireTeamManager(requireTeam(teamId), actor);
        int pageSize = KeysetPagination.pageSize(limit);
        String scope = "team-invitations:" + teamId;
        Long anchor = KeysetPagination.decodeCursor(cursor, scope);
        List<TeamInvitationEntity> rows =
            invitationMapper.selectInvitationPage(teamId, anchor, pageSize + 1);
        return KeysetPagination.keysetPage(
            rows, pageSize, scope, TeamInvitationEntity::getId, this::effectiveInvitation);
    }

    /**
     * 当前用户收到的待处理团队邀请（收件人视角）。
     */
    public PageSlice<ReceivedInvitationResponse> myInvitations(
        Long actor, String cursor, Integer limit) {
        UserEntity user = userMapper.selectById(actor);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            throw notFound();
        }
        int pageSize = KeysetPagination.pageSize(limit);
        String scope = "my-team-invitations";
        Long anchor = KeysetPagination.decodeCursor(cursor, scope);
        List<TeamInvitationEntity> rows =
            invitationMapper.selectPendingByEmail(
                normalize(user.getEmail()), anchor, pageSize + 1);
        Map<Long, String> teamNames = loadTeamNames(rows);
        Map<Long, String> inviterNames = loadUserDisplayNames(rows);
        return KeysetPagination.keysetPage(
            rows,
            pageSize,
            scope,
            TeamInvitationEntity::getId,
            invitation -> receivedInvitation(invitation, teamNames, inviterNames));
    }

    /**
     * 接受团队邀请。过期状态由 expires_at 动态判定，不写入数据库。
     */
    @Transactional
    public TeamMemberResponse accept(Long actor, String reference) {
        if (reference == null || reference.isBlank() || reference.length() > 512) {
            throw notFound();
        }
        UserEntity user = userMapper.selectById(actor);
        if (user == null) {
            throw notFound();
        }
        // reference 为邀请数字 ID 或明文邀请 token；两者以数字解析区分
        TeamInvitationEntity invitation = resolveInvitation(reference);
        if (invitation == null
            || !normalize(user.getEmail()).equals(invitation.getEmailNormalized())) {
            throw notFound();
        }
        if ("ACCEPTED".equals(invitation.getStatus())) {
            TeamMemberEntity existing =
                memberMapper.selectByTeamAndUser(invitation.getTeamId(), actor);
            if (existing != null) {
                TeamEntity team = requireTeam(invitation.getTeamId());
                return memberResponse(actor, team, existing.getRole(), user);
            }
        }
        if (!"PENDING".equals(invitation.getStatus())) {
            throw conflict("INVITATION_NOT_PENDING", "邀请已不可接受");
        }
        if (!invitation.getExpiresAt().isAfter(now())) {
            throw conflict("INVITATION_EXPIRED", "邀请已过期");
        }
        TeamMemberEntity member = memberMapper.selectByTeamAndUser(invitation.getTeamId(), actor);
        if (member == null) {
            member = new TeamMemberEntity();
            member.setTeamId(invitation.getTeamId());
            member.setUserId(actor);
            member.setRole("TEAM_MEMBER");
            memberMapper.insert(member);
            // 团队级 SSE：新成员加入团队
            eventService.publishTeamEvent(
                invitation.getTeamId(),
                "team.member.updated",
                id(actor),
                Map.of("teamId", id(invitation.getTeamId()), "userId", id(actor)));
        }
        invitation.setStatus("ACCEPTED");
        invitation.setAcceptedAt(now());
        invitationMapper.updateById(invitation);
        TeamEntity team = requireTeam(invitation.getTeamId());
        // 通知邀请者：该用户已加入团队
        if (invitation.getInvitedBy() != null) {
            notificationService.notify(
                invitation.getInvitedBy(),
                null,
                null,
                "TEAM_JOINED",
                user.getDisplayName() + " 已加入团队 " + team.getName(),
                null,
                team.getId().toString());
        }
        return memberResponse(actor, team, member.getRole(), user);
    }

    /**
     * 解析接受邀请的引用：reference 为邀请 id 时按主键查找（加行锁），否则视为明文 token 按其哈希查找。
     */
    private TeamInvitationEntity resolveInvitation(String reference) {
        Long parsed = tryId(reference);
        if (parsed != null) {
            return invitationMapper.selectOne(
                Wrappers.<TeamInvitationEntity>lambdaQuery()
                    .eq(TeamInvitationEntity::getId, parsed)
                    .last("FOR UPDATE"));
        }
        return invitationMapper.selectOne(
            Wrappers.<TeamInvitationEntity>lambdaQuery()
                .eq(TeamInvitationEntity::getTokenHash, tokens.hash(reference))
                .last("FOR UPDATE"));
    }

    private Long tryId(String value) {
        try {
            return Long.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 撤销团队邀请；所有者和管理员均可撤销。
     */
    @Transactional
    public TeamInvitationResponse revoke(Long actor, Long teamId, Long invitationId) {
        TeamEntity team = requireTeamForUpdate(teamId);

        access.requireTeamManager(team, actor);
        TeamInvitationEntity invitation =
            invitationMapper.selectOne(
                Wrappers.<TeamInvitationEntity>lambdaQuery()
                    .eq(TeamInvitationEntity::getId, invitationId)
                    .eq(TeamInvitationEntity::getTeamId, teamId)
                    .last("FOR UPDATE"));
        if (invitation == null) {
            throw notFound();
        }
        if ("PENDING".equals(invitation.getStatus()) && !invitation.getExpiresAt().isAfter(now())) {
            throw conflict("INVITATION_EXPIRED", "邀请已过期");
        }
        if (!"PENDING".equals(invitation.getStatus())) {
            throw conflict("INVITATION_NOT_PENDING", "仅能撤销待处理邀请");
        }
        invitation.setStatus("REVOKED");
        invitationMapper.updateById(invitation);
        return invitation(invitation);
    }

    /**
     * 更新团队成员角色；仅团队所有者可任命、降级或移除团队管理员。
     */
    @Transactional
    public TeamMemberResponse updateMember(
        Long actor, Long teamId, Long userId, TeamMemberUpdateRequest request) {
        TeamEntity team = requireTeamForUpdate(teamId);

        access.requireTeamOwner(team, actor);
        if (!"TEAM_ADMIN".equals(request.getRole()) && !"TEAM_MEMBER".equals(request.getRole())) {
            throw invalid("成员角色仅支持 TEAM_ADMIN 或 TEAM_MEMBER，不能通过成员接口设置 TEAM_OWNER");
        }
        TeamMemberEntity target = access.requireTeamMemberRow(teamId, userId);
        if (team.getOwnerUserId().equals(userId)) {
            throw conflict("TEAM_OWNER_IMMUTABLE", "Team Owner 不能通过成员角色接口降级");
        }

        target.setRole(request.getRole());
        memberMapper.updateRole(teamId, userId, request.getRole());
        return memberResponse(userId, team, target.getRole(), userMapper.selectById(userId));
    }

    /**
     * 移除团队成员；所有者可移除管理员，管理员只能移除普通成员。
     */
    @Transactional
    public TeamMemberResponse removeMember(Long actor, Long teamId, Long userId) {
        TeamEntity team = requireTeamForUpdate(teamId);

        String actorRole = access.requireTeamManager(team, actor);
        TeamMemberEntity target = access.requireTeamMemberRow(teamId, userId);
        String targetRole =
            ProjectAccessService.effectiveTeamRole(
                team.getOwnerUserId(), userId, target.getRole());
        if ("TEAM_OWNER".equals(targetRole)) {
            throw conflict("TEAM_OWNER_IMMUTABLE", "Team Owner 不能被移除");
        }
        if ("TEAM_ADMIN".equals(targetRole) && !"TEAM_OWNER".equals(actorRole)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TEAM_OWNER_REQUIRED", "只有团队所有者可以移除团队管理员");
        }
        // 团队所有者始终是团队下项目的生效管理员，移除显式项目管理员不会让项目失去管理员。
        projectMemberMapper.deleteByTeamAndUser(teamId, userId);
        memberMapper.deleteByTeamAndUser(teamId, userId);
        // 团队级 SSE：成员移出团队
        eventService.publishTeamEvent(
            teamId,
            "team.member.updated",
            id(userId),
            Map.of("teamId", id(teamId), "userId", id(userId)));
        return memberResponse(userId, team, target.getRole(), userMapper.selectById(userId));
    }

    /**
     * 团队所有者解散团队，删除团队及其项目数据。
     */
    @Transactional
    public TeamResponse disband(Long actor, Long teamId) {
        TeamEntity team = requireTeamForUpdate(teamId);
        access.requireTeamOwner(team, actor);
        // 解散前取一次摘要，避免删除后无法查询
        TeamResponse summary = team(team, "TEAM_OWNER");
        teamDisbandService.deleteTeam(teamId, team.getAvatarUrl());
        return summary;
    }

    // 读取团队；不存在时返回 404。
    private TeamEntity requireTeam(Long teamId) {
        TeamEntity team = teamMapper.selectById(teamId);
        if (team == null) {
            throw notFound();
        }
        return team;
    }

    // 锁定团队行，使成员和邀请变更按团队串行。
    private TeamEntity requireTeamForUpdate(Long teamId) {
        TeamEntity team = teamMapper.selectByIdForUpdate(teamId);
        if (team == null) {
            throw notFound();
        }
        return team;
    }

    private TeamResponse team(TeamEntity team, String role) {
        return TeamViews.response(team, role, memberCount(team.getId()));
    }

    private int memberCount(Long teamId) {
        Long count =
            memberMapper.selectCount(
                Wrappers.<TeamMemberEntity>lambdaQuery()
                    .eq(TeamMemberEntity::getTeamId, teamId));
        return count == null ? 0 : count.intValue();
    }

    private TeamMemberResponse memberResponse(
        Long userId, TeamEntity team, String storedRole, UserEntity user) {
        TeamMemberResponse response =
            new TeamMemberResponse(
                userId.toString(),
                ProjectAccessService.effectiveTeamRole(
                    team.getOwnerUserId(), userId, storedRole));
        response.setDisplayName(user == null ? null : user.getDisplayName());
        response.setEmail(user == null ? null : user.getEmail());
        response.setAvatarUrl(user == null ? null : user.getAvatarUrl());
        return response;
    }

    private TeamInvitationResponse invitation(TeamInvitationEntity value) {
        return new TeamInvitationResponse(
            value.getId().toString(),
            value.getEmailNormalized(),
            value.getStatus(),
            value.getExpiresAt());
    }

    private TeamInvitationResponse effectiveInvitation(TeamInvitationEntity value) {
        TeamInvitationResponse response = invitation(value);
        if ("PENDING".equals(response.getStatus()) && !value.getExpiresAt().isAfter(now())) {
            response.setStatus("EXPIRED");
        }
        return response;
    }

    private ReceivedInvitationResponse receivedInvitation(
        TeamInvitationEntity value,
        Map<Long, String> teamNames,
        Map<Long, String> inviterNames) {
        String status =
            "PENDING".equals(value.getStatus()) && !value.getExpiresAt().isAfter(now())
                ? "EXPIRED"
                : value.getStatus();
        return new ReceivedInvitationResponse(
            value.getId().toString(),
            value.getTeamId().toString(),
            teamNames.get(value.getTeamId()),
            "TEAM_MEMBER",
            inviterNames.get(value.getInvitedBy()),
            status,
            iso(value.getExpiresAt()),
            iso(value.getCreatedAt()));
    }

    private Map<Long, String> loadTeamNames(List<TeamInvitationEntity> rows) {
        Set<Long> teamIds =
            rows.stream()
                .map(TeamInvitationEntity::getTeamId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (teamIds.isEmpty()) {
            return Map.of();
        }
        return teamMapper.selectBatchIds(teamIds).stream()
            .collect(Collectors.toMap(TeamEntity::getId, TeamEntity::getName));
    }

    private Map<Long, String> loadUserDisplayNames(List<TeamInvitationEntity> rows) {
        Set<Long> userIds =
            rows.stream()
                .map(TeamInvitationEntity::getInvitedBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(userIds).stream()
            .collect(
                Collectors.toMap(
                    UserEntity::getId,
                    user ->
                        user.getDisplayName() == null
                            || user.getDisplayName().isBlank()
                            ? "已注销用户"
                            : user.getDisplayName()));
    }

    private String iso(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC).toInstant().toString();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "TEAM_RESOURCE_NOT_FOUND", "团队资源不存在或不可见");
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TEAM_OPERATION", message);
    }

    private String id(Long value) {
        return value == null ? null : value.toString();
    }
}
