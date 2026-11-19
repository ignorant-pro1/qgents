package qg.qgent.project.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.common.dto.PageSlice;
import qg.qgent.common.storage.AvatarStorageService;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.PagedApiResponse;
import qg.qgent.common.web.RequestIdFilter;
import qg.qgent.project.dto.*;
import qg.qgent.project.service.ProjectService;
import qg.qgent.user.dto.AvatarConfirmRequest;
import qg.qgent.user.dto.AvatarConfirmResponse;
import qg.qgent.user.dto.AvatarCredentialRequest;
import qg.qgent.user.dto.AvatarCredentialResponse;

/**
 * 项目与项目成员接口 项目资料的创建、查询、修改、归档/恢复与项目成员管理。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ProjectController {
    private final ProjectService projects;

    /**
     * 创建项目，团队所有者或管理员可创建，创建者自动成为 PROJECT_ADMIN。
     */
    @PostMapping("/teams/{teamId}/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProjectResponse> create(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long teamId,
        @Valid @RequestBody ProjectCreateRequest body,
        HttpServletRequest request) {
        return ok(projects.create(actor, teamId, body), request);
    }

    /**
     * 分页获取当前用户可见的项目。
     */
    @GetMapping("/teams/{teamId}/projects")
    public PagedApiResponse<ProjectResponse> list(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long teamId,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer limit,
        HttpServletRequest request) {
        return page(projects.list(actor, teamId, cursor, limit), request);
    }

    /**
     * 获取项目资料。
     */
    @GetMapping("/projects/{projectId}")
    public ApiResponse<ProjectResponse> get(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long projectId,
        HttpServletRequest request) {
        return ok(projects.get(actor, projectId), request);
    }

    /**
     * 修改项目资料（Project Admin 或 Team Owner）。
     */
    @PatchMapping("/projects/{projectId}")
    public ApiResponse<ProjectResponse> update(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long projectId,
        @Valid @RequestBody ProjectUpdateRequest body,
        HttpServletRequest request) {
        return ok(projects.update(actor, projectId, body), request);
    }

    /**
     * 签发项目头像直传凭证（与修改项目资料同权限；对象键 projects/{projectId}/{uuid}.{ext}）。
     */
    @PostMapping("/projects/{projectId}/avatar/credential")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AvatarCredentialResponse> avatarCredential(
        @PathVariable Long projectId,
        @AuthenticationPrincipal Long actor,
        @Valid @RequestBody AvatarCredentialRequest body,
        HttpServletRequest request) {
        AvatarStorageService.AvatarCredential credential =
            projects.avatarCredential(
                actor, projectId, body.getMediaType(), body.getSizeBytes());
        return ok(new AvatarCredentialResponse(credential), request);
    }

    /**
     * 确认上传，直接写入项目头像并返回公共读 URL。
     */
    @PostMapping("/projects/{projectId}/avatar/confirm")
    public ApiResponse<AvatarConfirmResponse> avatarConfirm(
        @PathVariable Long projectId,
        @AuthenticationPrincipal Long actor,
        @Valid @RequestBody AvatarConfirmRequest body,
        HttpServletRequest request) {
        return ok(projects.updateAvatar(actor, projectId, body.getObjectKey()), request);
    }

    /**
     * 归档项目。
     */
    @PostMapping("/projects/{projectId}/archive")
    public ApiResponse<ProjectResponse> archive(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long projectId,
        HttpServletRequest request) {
        return ok(projects.archive(actor, projectId), request);
    }

    /**
     * 恢复已归档项目。
     */
    @PostMapping("/projects/{projectId}/restore")
    public ApiResponse<ProjectResponse> restore(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long projectId,
        HttpServletRequest request) {
        return ok(projects.restore(actor, projectId), request);
    }

    /**
     * 分页获取项目成员与角色。
     */
    @GetMapping("/projects/{projectId}/members")
    public PagedApiResponse<ProjectMemberResponse> members(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long projectId,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer limit,
        HttpServletRequest request) {
        return page(projects.members(actor, projectId, cursor, limit), request);
    }

    /**
     * 将团队现有成员加入项目（初始 PROJECT_MEMBER）。
     */
    @PostMapping("/projects/{projectId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProjectMemberResponse> addMember(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long projectId,
        @Valid @RequestBody ProjectMemberAddRequest body,
        HttpServletRequest request) {
        return ok(projects.addMember(actor, projectId, body), request);
    }

    /**
     * 调整项目成员角色；团队所有者的继承权限不可通过此接口调整。
     */
    @PatchMapping("/projects/{projectId}/members/{userId}")
    public ApiResponse<ProjectMemberResponse> updateMember(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long projectId,
        @PathVariable Long userId,
        @Valid @RequestBody ProjectMemberUpdateRequest body,
        HttpServletRequest request) {
        return ok(projects.updateMember(actor, projectId, userId, body), request);
    }

    /**
     * 移除项目成员；团队所有者的继承权限不可通过此接口移除。
     */
    @DeleteMapping("/projects/{projectId}/members/{userId}")
    public ApiResponse<ProjectMemberResponse> removeMember(
        @AuthenticationPrincipal Long actor,
        @PathVariable Long projectId,
        @PathVariable Long userId,
        HttpServletRequest request) {
        return ok(projects.removeMember(actor, projectId, userId), request);
    }

    private <T> ApiResponse<T> ok(T value, HttpServletRequest request) {
        return ApiResponse.ok(value, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    private <T> PagedApiResponse<T> page(PageSlice<T> value, HttpServletRequest request) {
        return new PagedApiResponse<>(
            value.getData(),
            value.getPage(),
            (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
