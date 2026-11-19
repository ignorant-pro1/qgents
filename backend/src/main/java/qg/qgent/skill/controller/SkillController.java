package qg.qgent.skill.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import qg.qgent.common.dto.RejectRequest;
import qg.qgent.common.web.ApiException;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.RequestIdFilter;
import qg.qgent.skill.dto.SkillUpdateRequest;
import qg.qgent.skill.service.SkillService;

import java.util.List;

/**
 * 共享 Skill 接口 提供 Skill 列表查询、创建、详情、编辑、审核、发布、拒绝与下线操作。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;
    private final ObjectMapper json;

    /**
     * 查询 Skill（支持状态、标签过滤）。
     */
    @GetMapping("/projects/{projectId}/skills")
    public ApiResponse<?> list(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "tag", required = false) String tag,
        HttpServletRequest request) {
        return ok(skillService.list(userId, projectId, status, tag), request);
    }

    @PostMapping(
        value = "/projects/{projectId}/skills/preview",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<?> preview(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @RequestParam("file") MultipartFile file,
        HttpServletRequest request) {
        return ok(skillService.preview(userId, projectId, file), request);
    }

    /**
     * 创建 Skill；私有内容及管理员创建的共享内容直接发布。
     */
    @PostMapping(
        value = "/projects/{projectId}/skills",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> create(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "visibility", required = false) String visibility,
        @RequestParam(value = "tags", defaultValue = "[]") String tags,
        HttpServletRequest request) {
        List<String> parsedTags;
        try {
            parsedTags = json.readValue(tags, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException exception) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_SKILL_TAGS", "标签必须是字符串数组");
        }
        if (parsedTags == null || parsedTags.stream().anyMatch(java.util.Objects::isNull)) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_SKILL_TAGS", "标签必须是字符串数组");
        }
        return ok(skillService.create(userId, projectId, file, visibility, parsedTags), request);
    }

    /**
     * 获取 Skill 详情。
     */
    @GetMapping("/projects/{projectId}/skills/{skillId}")
    public ApiResponse<?> get(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @PathVariable Long skillId,
        HttpServletRequest request) {
        return ok(skillService.get(userId, projectId, skillId), request);
    }

    /**
     * 编辑可修改的 Skill；已发布内容修改后立即生效。
     */
    @PatchMapping("/projects/{projectId}/skills/{skillId}")
    public ApiResponse<?> update(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @PathVariable Long skillId,
        @Valid @RequestBody SkillUpdateRequest body,
        HttpServletRequest request) {
        return ok(skillService.update(userId, projectId, skillId, body), request);
    }

    /**
     * 提交 Skill 审核。
     */
    @PostMapping("/projects/{projectId}/skills/{skillId}/submit-review")
    public ApiResponse<?> submitReview(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @PathVariable Long skillId,
        HttpServletRequest request) {
        return ok(skillService.submitReview(userId, projectId, skillId), request);
    }

    /**
     * 发布 Skill（Project Admin）。
     */
    @PostMapping("/projects/{projectId}/skills/{skillId}/approve")
    public ApiResponse<?> approve(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @PathVariable Long skillId,
        HttpServletRequest request) {
        return ok(skillService.approve(userId, projectId, skillId), request);
    }

    /**
     * 拒绝 Skill 并给出原因（Project Admin）。
     */
    @PostMapping("/projects/{projectId}/skills/{skillId}/reject")
    public ApiResponse<?> reject(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @PathVariable Long skillId,
        @Valid @RequestBody RejectRequest body,
        HttpServletRequest request) {
        return ok(skillService.reject(userId, projectId, skillId, body.getReason()), request);
    }

    /**
     * 创建者或项目管理员下线已发布 Skill。
     */
    @PostMapping("/projects/{projectId}/skills/{skillId}/archive")
    public ApiResponse<?> archive(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @PathVariable Long skillId,
        HttpServletRequest request) {
        return ok(skillService.archive(userId, projectId, skillId), request);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
