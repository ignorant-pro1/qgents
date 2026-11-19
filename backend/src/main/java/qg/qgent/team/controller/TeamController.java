package qg.qgent.team.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import qg.qgent.common.dto.PageSlice;
import qg.qgent.common.storage.AvatarStorageService;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.PagedApiResponse;
import qg.qgent.common.web.RequestIdFilter;
import qg.qgent.realtime.service.EventService;
import qg.qgent.team.dto.*;
import qg.qgent.team.service.TeamService;
import qg.qgent.user.dto.AvatarConfirmRequest;
import qg.qgent.user.dto.AvatarConfirmResponse;
import qg.qgent.user.dto.AvatarCredentialRequest;
import qg.qgent.user.dto.AvatarCredentialResponse;

/**
 * 团队与团队邀请接口
 */
@RestController
@RequestMapping("/api/v1/teams")
@RequiredArgsConstructor
public class TeamController {
    private final TeamService teams;
    private final EventService eventService;

    /**
     * 团队级实时事件流（需团队成员）。
     */
    @GetMapping("/{teamId}/events")
    public SseEmitter events(
        @PathVariable Long teamId,
        @AuthenticationPrincipal Long userId,
        @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId) {
        return eventService.teamStream(teamId, userId, lastEventId);
    }

    /**
     * 创建团队，创建者成为 TEAM_OWNER。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TeamResponse> create(
        @AuthenticationPrincipal Long actor,
        @Valid @RequestBody TeamCreateRequest body,
        HttpServletRequest request) {
        return ok(teams.create(actor, body), request);
    }

    /**
     * 分页获取我加入的团队。
     */
    @GetMapping
    public PagedApiResponse<TeamResponse> list(
        @AuthenticationPrincipal Long actor,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer limit,
        HttpServletRequest request) {
        return page(teams.list(actor, cursor, limit), request);
    }

    /**
     * 获取团队资料与当前用户生效角色。
     */
    @GetMapping("/{teamId}")
    public ApiResponse<TeamResponse> get(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long teamId,
        HttpServletRequest request) {
        return ok(teams.get(actor, teamId), request);
    }

    /**
     * 团队所有者或管理员修改团队名称（description 传 null 保留原值，传空串清空）。
     */
    @PatchMapping("/{teamId}")
    public ApiResponse<TeamResponse> update(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long teamId,
        @Valid @RequestBody TeamUpdateRequest body,
        HttpServletRequest request) {
        return ok(teams.update(actor, teamId, body), request);
    }

    /**
     * Team Owner 解散团队：级联删除团队下所有项目、项目群聊、消息与成员关系（不可恢复）。
     */
    @DeleteMapping("/{teamId}")
    public ApiResponse<TeamResponse> disband(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long teamId,
        HttpServletRequest request) {
        return ok(teams.disband(actor, teamId), request);
    }

    /**
     * 分页获取团队成员及其生效角色。
     */
    @GetMapping("/{teamId}/members")
    public PagedApiResponse<TeamMemberResponse> members(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long teamId,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer limit,
        HttpServletRequest request) {
        return page(teams.members(actor, teamId, cursor, limit), request);
    }

    /**
     * 团队所有者或管理员按邮箱创建团队邀请。
     */
    @PostMapping("/{teamId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TeamInvitationResponse> invite(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long teamId,
        @Valid @RequestBody TeamMemberInviteRequest body,
        HttpServletRequest request) {
        return ok(teams.invite(actor, teamId, body), request);
    }

    /**
     * 分页查询团队邀请状态（团队所有者或管理员）。
     */
    @GetMapping("/{teamId}/invitations")
    public PagedApiResponse<TeamInvitationResponse> invitations(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long teamId,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer limit,
        HttpServletRequest request) {
        return page(teams.invitations(actor, teamId, cursor, limit), request);
    }

    /**
     * 撤销未接受的团队邀请。
     */
    @DeleteMapping("/{teamId}/invitations/{invitationId}")
    public ApiResponse<TeamInvitationResponse> revoke(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long teamId,
        @PathVariable Long invitationId,
        HttpServletRequest request) {
        return ok(teams.revoke(actor, teamId, invitationId), request);
    }

    /**
     * 修改团队成员角色；仅团队所有者可设置 TEAM_ADMIN/TEAM_MEMBER，不能修改团队所有者。
     */
    @PatchMapping("/{teamId}/members/{userId}")
    public ApiResponse<TeamMemberResponse> updateMember(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long teamId,
        @PathVariable Long userId,
        @Valid @RequestBody TeamMemberUpdateRequest body,
        HttpServletRequest request) {
        return ok(teams.updateMember(actor, teamId, userId, body), request);
    }

    /**
     * 移除团队成员；所有者可移除管理员，管理员只能移除普通成员。
     */
    @DeleteMapping("/{teamId}/members/{userId}")
    public ApiResponse<TeamMemberResponse> removeMember(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long teamId,
        @PathVariable Long userId,
        HttpServletRequest request) {
        return ok(teams.removeMember(actor, teamId, userId), request);
    }

    /**
     * 签发团队头像直传凭证（所有者或管理员；对象键 teams/{teamId}/{uuid}.{ext}）。
     */
    @PostMapping("/{teamId}/avatar/credential")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AvatarCredentialResponse> avatarCredential(
        @PathVariable Long teamId,
        @AuthenticationPrincipal Long actor,
        @Valid @RequestBody AvatarCredentialRequest body,
        HttpServletRequest request) {
        AvatarStorageService.AvatarCredential credential =
            teams.avatarCredential(actor, teamId, body.getMediaType(), body.getSizeBytes());
        return ok(new AvatarCredentialResponse(credential), request);
    }

    /**
     * 确认上传，直接写入团队头像并返回公共读 URL。
     */
    @PostMapping("/{teamId}/avatar/confirm")
    public ApiResponse<AvatarConfirmResponse> avatarConfirm(
        @PathVariable Long teamId,
        @AuthenticationPrincipal Long actor,
        @Valid @RequestBody AvatarConfirmRequest body,
        HttpServletRequest request) {
        return ok(teams.updateAvatar(actor, teamId, body.getObjectKey()), request);
    }

    private <T> ApiResponse<T> ok(T data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    private <T> PagedApiResponse<T> page(PageSlice<T> value, HttpServletRequest request) {
        return new PagedApiResponse<>(
            value.getData(),
            value.getPage(),
            (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
