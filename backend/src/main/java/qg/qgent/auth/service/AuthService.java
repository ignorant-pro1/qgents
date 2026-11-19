package qg.qgent.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.auth.dto.AuthTokensResponse;
import qg.qgent.auth.dto.LoginRequest;
import qg.qgent.auth.dto.RegisterRequest;
import qg.qgent.auth.dto.ResetPasswordRequest;
import qg.qgent.auth.mail.VerificationCodeMailer;
import qg.qgent.common.storage.AvatarStorageService;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.dto.ProjectResponse;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.project.service.ProjectViews;
import qg.qgent.team.dto.TeamResponse;
import qg.qgent.team.entity.TeamEntity;
import qg.qgent.team.entity.TeamMemberEntity;
import qg.qgent.team.mapper.TeamMapper;
import qg.qgent.team.mapper.TeamMemberMapper;
import qg.qgent.team.mapper.view.TeamMemberCountRow;
import qg.qgent.team.service.TeamViews;
import qg.qgent.user.dto.CurrentUserResponse;
import qg.qgent.user.dto.UserResponse;
import qg.qgent.user.dto.UserUpdateRequest;
import qg.qgent.user.entity.UserEntity;
import qg.qgent.user.mapper.UserMapper;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AuthService {
    // 注册验证码有效期（10 分钟）。
    private static final Duration VERIFICATION_TTL = Duration.ofMinutes(10);
    // 验证码校验尝试限流：同一邮箱和同一来源分别限制，防止撞码。
    private static final int CODE_ATTEMPTS_BY_EMAIL = 10;
    private static final int CODE_ATTEMPTS_BY_SOURCE = 30;
    private static final Duration CODE_ATTEMPT_WINDOW = Duration.ofHours(1);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserMapper userMapper;
    private final RefreshTokenStore refreshTokens;
    private final VerificationCodeStore verificationCodes;
    private final TeamMapper teamMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final ProjectMapper projectMapper;
    private final RsaPasswordDecryptor rsa;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final VerificationCodeMailer mailer;
    private final RateLimiter limiter;
    private final AvatarStorageService avatarStorage;
    private final String dummyPasswordHash;

    public AuthService(
        UserMapper userMapper,
        RefreshTokenStore refreshTokens,
        VerificationCodeStore verificationCodes,
        TeamMapper teamMapper,
        TeamMemberMapper teamMemberMapper,
        ProjectMapper projectMapper,
        RsaPasswordDecryptor rsa,
        PasswordEncoder passwords,
        TokenService tokens,
        VerificationCodeMailer mailer,
        RateLimiter limiter,
        AvatarStorageService avatarStorage) {
        this.userMapper = userMapper;
        this.refreshTokens = refreshTokens;
        this.verificationCodes = verificationCodes;
        this.teamMapper = teamMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.projectMapper = projectMapper;
        this.rsa = rsa;
        this.passwords = passwords;
        this.tokens = tokens;
        this.mailer = mailer;
        this.limiter = limiter;
        this.avatarStorage = avatarStorage;
        this.dummyPasswordHash = passwords.encode("qgents-dummy-password-not-used");
    }

    @Transactional
    public AuthTokensResponse register(RegisterRequest input, String fingerprint) {
        String email = normalize(input.getEmail());
        String password = validated(rsa.decrypt(input.getPasswordKeyId(), input.getPassword()));
        // 注册必须通过邮箱验证码校验，防止假邮箱注册
        verifyCode(
            VerificationCodeStore.Purpose.REGISTER,
            email,
            input.getVerificationCode(),
            fingerprint);

        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setDisplayName(input.getDisplayName().trim());
        user.setPasswordHash(passwords.encode(password));
        user.setStatus("ACTIVE");
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw conflict("EMAIL_ALREADY_REGISTERED", "该邮箱已注册");
        }
        return issue(user);
    }

    /**
     * 发送注册邮箱验证码：校验邮箱未注册后生成 6 位数字验证码并异步发送邮件。
     * 已注册邮箱不发送，直接返回 409（与注册时的 EMAIL_ALREADY_REGISTERED 一致）。
     * 限流按 IP+邮箱计，防止验证码轰炸。
     *
     * @param rawEmail    原始邮箱
     * @param fingerprint 请求指纹（IP）
     */
    @Transactional
    public void sendRegisterCode(String rawEmail, String fingerprint) {
        String email = normalize(rawEmail);
        if (!limiter.allow("register-code", fingerprint + ":" + email, 5, Duration.ofHours(1))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "验证码发送过于频繁，请稍后再试");
        }
        if (findByEmail(email) != null) {
            throw conflict("EMAIL_ALREADY_REGISTERED", "该邮箱已注册");
        }
        String code = sixDigitCode();
        verificationCodes.save(
            VerificationCodeStore.Purpose.REGISTER, email, code, VERIFICATION_TTL);
        mailer.sendRegistration(email, code, VERIFICATION_TTL);
    }

    /**
     * 校验并消费验证码；尝试次数先按邮箱和来源限流。
     */
    private void verifyCode(
        VerificationCodeStore.Purpose purpose, String email, String code, String fingerprint) {
        String scope = purpose.key() + "-verify";
        if (!limiter.allow(scope + "-email", email, CODE_ATTEMPTS_BY_EMAIL, CODE_ATTEMPT_WINDOW)
            || !limiter.allow(
            scope + "-source",
            fingerprint,
            CODE_ATTEMPTS_BY_SOURCE,
            CODE_ATTEMPT_WINDOW)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "验证码尝试过于频繁，请稍后再试");
        }
        if (!verificationCodes.consume(purpose, email, code)) {
            throw verificationCodeInvalid();
        }
    }

    private ApiException verificationCodeInvalid() {
        return new ApiException(
            HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_VERIFICATION_CODE", "验证码无效或已过期，请重新获取");
    }

    @Transactional
    public AuthTokensResponse login(LoginRequest input, String fingerprint) {
        String email = normalize(input.getEmail());
        if (!limiter.allow("login", fingerprint + ":" + email, 10, Duration.ofMinutes(10))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "登录尝试过于频繁");
        }

        String plain = rsa.decrypt(input.getPasswordKeyId(), input.getPassword());
        UserEntity user = findByEmail(email);
        // 用户不存在时仍执行密码哈希校验，减少登录耗时的差异。
        boolean passwordMatches =
            passwords.matches(plain, user == null ? dummyPasswordHash : user.getPasswordHash());
        if (user == null || !"ACTIVE".equals(user.getStatus()) || !passwordMatches) {
            throw badCredentials();
        }
        return issue(user);
    }

    public AuthTokensResponse refresh(String raw) {
        Long userId = refreshTokens.consume(raw);
        if (userId == null) {
            throw unauthorized("INVALID_REFRESH_TOKEN", "refresh token无效或已过期");
        }
        UserEntity user = userMapper.selectById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw unauthorized("INVALID_REFRESH_TOKEN", "用户不可用");
        }
        return issue(user);
    }

    /**
     * 退出登录：按提交的 refresh token 撤销，不依赖 access token。
     */
    public void logout(String raw) {
        refreshTokens.revoke(raw);
    }

    public void requestReset(String rawEmail, String fingerprint) {
        String email = normalize(rawEmail);
        if (!limiter.allow("password-reset", fingerprint + ":" + email, 3, Duration.ofHours(1))) {
            return;
        }
        UserEntity user = findByEmail(email);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            return;
        }
        String code = sixDigitCode();
        verificationCodes.save(
            VerificationCodeStore.Purpose.PASSWORD_RESET, email, code, tokens.resetTtl());
        mailer.sendPasswordReset(user.getEmail(), code, tokens.resetTtl());
    }

    /**
     * 先完成密码和目标账户校验，再原子消费验证码；随后撤销该用户全部 refresh token 再改密码。
     * Redis 与 MySQL 不在同一事务，数据库失败时用户重新获取验证码即可。
     */
    @Transactional
    public void reset(ResetPasswordRequest input, String fingerprint) {
        String email = normalize(input.getEmail());
        String password = validated(rsa.decrypt(input.getPasswordKeyId(), input.getNewPassword()));
        UserEntity user = findByEmail(email);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw verificationCodeInvalid();
        }
        verifyCode(
            VerificationCodeStore.Purpose.PASSWORD_RESET, email, input.getToken(), fingerprint);
        refreshTokens.revokeAll(user.getId());
        // 只更新密码字段，避免覆盖并发写入的最近团队选择
        userMapper.update(
            null,
            Wrappers.<UserEntity>lambdaUpdate()
                .set(UserEntity::getPasswordHash, passwords.encode(password))
                .eq(UserEntity::getId, user.getId()));
    }

    @Transactional
    public CurrentUserResponse me(Long userId) {
        UserEntity user = requireUser(userId);
        List<TeamResponse> availableTeams = teams(userId);
        Long currentTeamId =
            availableTeams.stream()
                .map(team -> Long.valueOf(team.getId()))
                .filter(id -> id.equals(user.getLastTeamId()))
                .findFirst()
                .orElseGet(() ->
                    availableTeams.isEmpty()
                        ? null
                        : Long.valueOf(availableTeams.getFirst().getId()));
        if (!Objects.equals(currentTeamId, user.getLastTeamId())) {
            saveTeam(userId, currentTeamId);
        }
        return new CurrentUserResponse(
            view(user),
            availableTeams,
            projects(userId),
            currentTeamId == null ? null : currentTeamId.toString());
    }

    @Transactional
    public void selectTeam(Long userId, Long teamId) {
        TeamEntity team = teamMapper.selectById(teamId);
        if (team == null
            || !"ACTIVE".equals(team.getStatus())
            || teamMemberMapper.selectByTeamAndUser(teamId, userId) == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TEAM_ACCESS_DENIED", "暂无团队访问权限");
        }
        saveTeam(userId, teamId);
    }

    private void saveTeam(Long userId, Long teamId) {
        userMapper.updateLastTeam(userId, teamId);
    }

    /**
     * 修改昵称或清空头像；头像只能通过上传确认接口设置，这里拒绝外链地址。
     */
    @Transactional
    public UserResponse updateMe(Long userId, UserUpdateRequest input) {
        if (input.getDisplayName() == null && input.getAvatarUrl() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "至少提供一个修改字段");
        }
        String name = input.getDisplayName() == null ? null : input.getDisplayName().trim();
        if (name != null && name.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "昵称不能为空");
        }
        String avatar = input.getAvatarUrl() == null ? null : input.getAvatarUrl().trim();
        if (avatar != null && !avatar.isEmpty()) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST, "AVATAR_URL_NOT_ALLOWED", "头像只能通过头像上传接口设置");
        }
        // 只改目标字段，避免覆盖并发写入的最近团队选择
        String previousAvatar = avatar == null ? null : requireUser(userId).getAvatarUrl();
        userMapper.update(
            null,
            Wrappers.<UserEntity>lambdaUpdate()
                .set(name != null, UserEntity::getDisplayName, name)
                .set(
                    avatar != null,
                    UserEntity::getAvatarUrl,
                    avatar == null || avatar.isEmpty() ? null : avatar)
                .eq(UserEntity::getId, userId));
        if (avatar != null) {
            avatarStorage.deleteReplacedAfterCommit("avatars", userId, previousAvatar, null);
        }
        return view(requireUser(userId));
    }

    private AuthTokensResponse issue(UserEntity user) {
        String refresh = tokens.opaque();
        refreshTokens.save(user.getId(), refresh);
        return new AuthTokensResponse(
            tokens.access(user.getId()),
            tokens.accessSeconds(),
            refresh,
            tokens.refreshSeconds(),
            view(user));
    }

    private UserEntity findByEmail(String email) {
        return userMapper.selectOne(
            Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getEmail, email));
    }

    private UserEntity requireUser(Long userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw unauthorized("UNAUTHORIZED", "用户不存在");
        }
        return user;
    }

    private List<TeamResponse> teams(Long userId) {
        List<TeamMemberEntity> members = teamMemberMapper.selectByUserId(userId);
        if (members.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, TeamEntity> teams =
            teamMapper
                .selectBatchIds(members.stream().map(TeamMemberEntity::getTeamId).toList())
                .stream()
                .filter(team -> "ACTIVE".equals(team.getStatus()))
                .collect(Collectors.toMap(TeamEntity::getId, Function.identity()));
        Map<Long, Integer> memberCounts = memberCounts(teams.keySet());
        return members.stream()
            .filter(member -> teams.containsKey(member.getTeamId()))
            .map(member -> {
                TeamEntity team = teams.get(member.getTeamId());
                String role =
                    ProjectAccessService.effectiveTeamRole(
                        team.getOwnerUserId(), userId, member.getRole());
                return TeamViews.response(
                    team, role, memberCounts.getOrDefault(team.getId(), 0));
            })
            .toList();
    }

    /**
     * 一次查询取回所有团队的人数；逐团队 COUNT 会随团队数量线性增长。
     */
    private Map<Long, Integer> memberCounts(Set<Long> teamIds) {
        if (teamIds.isEmpty()) return Map.of();
        return teamMemberMapper.countByTeamIds(teamIds).stream()
            .collect(
                Collectors.toMap(
                    TeamMemberCountRow::getTeamId, TeamMemberCountRow::getMemberCount));
    }

    private List<ProjectResponse> projects(Long userId) {
        // 单次查询合并项目成员权限与 canonical Team Owner 兜底，并由 SQL 去重。
        // memberCount 由项目列表/详情接口补齐，此处不额外统计（前端缺失时隐藏）。
        return projectMapper.selectAccessibleByUser(userId).stream()
            .map(ProjectViews::response)
            .toList();
    }

    private UserResponse view(UserEntity user) {
        return new UserResponse(
            user.getId().toString(),
            user.getEmail(),
            user.getDisplayName(),
            user.getAvatarUrl());
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String validated(String value) {
        if (value.length() < 8 || value.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST, "WEAK_PASSWORD", "密码至少8个字符且UTF-8编码不超过72字节");
        }
        return value;
    }

    // 六位数字，保留前导零。
    private String sixDigitCode() {
        return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
    }

    private ApiException badCredentials() {
        return unauthorized("INVALID_CREDENTIALS", "邮箱或密码错误");
    }

    private ApiException unauthorized(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
