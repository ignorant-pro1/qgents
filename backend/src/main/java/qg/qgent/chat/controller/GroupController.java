package qg.qgent.chat.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.chat.dto.GroupCreateRequest;
import qg.qgent.chat.dto.GroupInviteRequest;
import qg.qgent.chat.dto.GroupPinRequest;
import qg.qgent.chat.dto.GroupUpdateRequest;
import qg.qgent.chat.service.GroupService;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.RequestIdFilter;

/**
 * 项目群聊接口：查询群信息与成员、修改群资料，以及管理当前用户的已读和置顶状态。
 * 默认群随项目创建，也支持新建群及邀请项目成员和 Agent。
 */
@RestController
@RequestMapping("/api/v1")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    /**
     * 查询项目群聊，包含最新消息摘要、当前用户的未读数、被提及未读数和置顶状态。
     */
    @GetMapping("/projects/{projectId}/groups")
    public ApiResponse<?> list(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        HttpServletRequest request) {
        return ok(groupService.list(userId, projectId), request);
    }

    /**
     * 创建项目内群聊，创建者自动入群。
     */
    @PostMapping("/projects/{projectId}/groups")
    public ApiResponse<?> create(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @Valid @RequestBody GroupCreateRequest body,
        HttpServletRequest request) {
        return ok(groupService.create(userId, projectId, body), request);
    }

    /**
     * 群成员可追加邀请项目成员和可用 Agent；默认群用户仍与项目同步。
     */
    @PostMapping("/projects/{projectId}/groups/{groupId}/members")
    public ApiResponse<?> invite(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @PathVariable Long groupId,
        @Valid @RequestBody GroupInviteRequest body,
        HttpServletRequest request) {
        return ok(groupService.invite(userId, projectId, groupId, body), request);
    }

    /**
     * 查询指定群聊的基本信息，以及当前用户的未读和置顶状态。
     */
    @GetMapping("/projects/{projectId}/groups/{groupId}")
    public ApiResponse<?> get(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @PathVariable Long groupId,
        HttpServletRequest request) {
        return ok(groupService.get(userId, projectId, groupId), request);
    }

    /**
     * 修改群名称和描述；仅群创建者或项目管理员可操作，未提供的字段保持原值。
     */
    @PatchMapping("/projects/{projectId}/groups/{groupId}")
    public ApiResponse<?> update(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @PathVariable Long groupId,
        @Valid @RequestBody GroupUpdateRequest body,
        HttpServletRequest request) {
        return ok(groupService.update(userId, projectId, groupId, body), request);
    }

    /**
     * 查询该群的用户成员和 Agent，供成员展示及选择提及对象使用。
     */
    @GetMapping("/projects/{projectId}/groups/{groupId}/members")
    public ApiResponse<?> members(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @PathVariable Long groupId,
        HttpServletRequest request) {
        return ok(groupService.members(userId, projectId, groupId), request);
    }

    /**
     * 将当前用户的已读位置推进到群内最新消息序号。
     */
    @PostMapping("/projects/{projectId}/groups/{groupId}/read")
    public ApiResponse<?> markRead(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @PathVariable Long groupId,
        HttpServletRequest request) {
        return ok(groupService.markRead(userId, projectId, groupId), request);
    }

    /**
     * 设置或取消当前用户的群聊置顶偏好，不影响其他用户。
     */
    @PutMapping("/projects/{projectId}/groups/{groupId}/pin")
    public ApiResponse<?> setPinned(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @PathVariable Long groupId,
        @Valid @RequestBody GroupPinRequest body,
        HttpServletRequest request) {
        return ok(groupService.setPinned(userId, projectId, groupId, body.getPinned()), request);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
