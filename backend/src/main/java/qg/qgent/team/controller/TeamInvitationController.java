package qg.qgent.team.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.common.dto.PageSlice;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.PagedApiResponse;
import qg.qgent.common.web.RequestIdFilter;
import qg.qgent.team.dto.ReceivedInvitationResponse;
import qg.qgent.team.dto.TeamMemberResponse;
import qg.qgent.team.service.TeamService;

/**
 * 团队邀请接口（收件人视角）
 */
@RestController
@RequestMapping("/api/v1/team-invitations")
@RequiredArgsConstructor
public class TeamInvitationController {
    private final TeamService teams;

    /**
     * 当前用户收到的待处理团队邀请列表（PENDING，过期按 EXPIRED 展示）。
     */
    @GetMapping
    public PagedApiResponse<ReceivedInvitationResponse> myInvitations(
        @AuthenticationPrincipal Long actor,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer limit,
        HttpServletRequest request) {
        PageSlice<ReceivedInvitationResponse> slice = teams.myInvitations(actor, cursor, limit);
        return new PagedApiResponse<>(
            slice.getData(),
            slice.getPage(),
            (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    /**
     * 接受团队邀请并加入团队。reference 为邀请数字 ID 或邮件中的随机 token。
     */
    @PostMapping("/{reference}/accept")
    public ApiResponse<TeamMemberResponse> accept(
        @AuthenticationPrincipal Long actor,
        @PathVariable String reference,
        HttpServletRequest request) {
        return ApiResponse.ok(
            teams.accept(actor, reference),
            (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
