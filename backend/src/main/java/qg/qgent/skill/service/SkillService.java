package qg.qgent.skill.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.skill.dto.ContextSkill;
import qg.qgent.skill.dto.SkillResponse;
import qg.qgent.skill.dto.SkillUpdateRequest;
import qg.qgent.skill.entity.SkillEntity;
import qg.qgent.skill.mapper.SkillMapper;
import qg.qgent.user.dto.UserSummary;
import qg.qgent.user.entity.UserEntity;
import qg.qgent.user.mapper.UserMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管理项目 Skill 的内容、审核、发布和下线。
 */
@Service
@RequiredArgsConstructor
public class SkillService {
    private static final Set<String> VISIBILITIES = Set.of("PRIVATE", "PROJECT_SHARED");
    private static final Set<String> SUBMITTABLE = Set.of("DRAFT", "REJECTED");
    private static final Set<String> EDITABLE =
        Set.of("DRAFT", "PENDING_REVIEW", "REJECTED", "PUBLISHED");

    private final SkillMapper skillMapper;
    private final ProjectAccessService access;
    private final UserMapper userMapper;
    private final EventService eventService;

    public SkillDocument preview(Long actor, Long projectId, MultipartFile file) {
        access.requireProjectMember(projectId, actor);
        return SkillDocument.fromFile(file);
    }

    /**
     * 私有 Skill 和管理员创建的共享 Skill 直接发布，其他共享 Skill 先建草稿。
     */
    @Transactional
    public SkillResponse create(
        Long actor, Long projectId, MultipartFile file, String visibility, List<String> tags) {
        access.requireProjectMember(projectId, actor);
        SkillDocument document = SkillDocument.fromFile(file);
        visibility = visibility == null || visibility.isBlank() ? "PRIVATE" : visibility.trim();
        if (!VISIBILITIES.contains(visibility)) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_VISIBILITY",
                "可见性仅支持 PRIVATE 或 PROJECT_SHARED");
        }
        SkillEntity skill = new SkillEntity();
        skill.setProjectId(projectId);
        skill.setCreatedBy(actor);
        skill.setName(document.name());
        skill.setDescription(document.description());
        skill.setContent(document.content());
        skill.setTags(tags);
        skill.setVisibility(visibility);
        boolean autoPublish =
            "PRIVATE".equals(visibility) || access.isProjectAdmin(projectId, actor);
        skill.setStatus(autoPublish ? "PUBLISHED" : "DRAFT");
        if (autoPublish) {
            skill.setReviewerId(actor);
            skill.setReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
        }
        skillMapper.insert(skill);
        if (autoPublish) publish("skill.published", projectId, skill.getId());
        return toResponse(skillMapper.selectById(skill.getId()));
    }

    /**
     * 返回自己的 Skill 和已发布共享内容；管理员还可查询共享草稿及审核记录。
     */
    public List<SkillResponse> list(Long actor, Long projectId, String status, String tag) {
        access.requireProjectMember(projectId, actor);
        return skillMapper
            .listSkills(
                projectId,
                actor,
                blankToNull(status),
                blankToNull(tag),
                access.isProjectAdmin(projectId, actor))
            .stream()
            .map(this::toResponse)
            .toList();
    }

    /**
     * 详情可见范围与列表一致。
     */
    public SkillResponse get(Long actor, Long projectId, Long skillId) {
        access.requireProjectMember(projectId, actor);
        SkillEntity skill = requireSkillInProject(projectId, skillId);
        if (!skill.getCreatedBy().equals(actor)
            && ("PRIVATE".equals(skill.getVisibility())
            || (!"PUBLISHED".equals(skill.getStatus())
            && !access.isProjectAdmin(projectId, actor)))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SKILL_NOT_FOUND", "Skill 不存在或无权访问");
        }
        return toResponse(skill);
    }

    /**
     * 创建者或管理员可编辑草稿、审核中、被拒绝及已发布内容；发布内容修改后立即生效。
     */
    @Transactional
    public SkillResponse update(
        Long actor, Long projectId, Long skillId, SkillUpdateRequest request) {
        SkillEntity skill = requireEditable(actor, projectId, skillId);
        if (request.getContent() != null) {
            SkillDocument document = SkillDocument.parse(request.getContent());
            skill.setName(document.name());
            skill.setDescription(document.description());
            skill.setContent(document.content());
        }
        if (request.getTags() != null) {
            skill.setTags(request.getTags());
        }
        skillMapper.updateById(skill);
        publish("skill.updated", projectId, skillId);
        return toResponse(skillMapper.selectById(skillId));
    }

    /**
     * 创建者或项目管理员提交草稿或被拒绝的 Skill 审核。
     */
    @Transactional
    public SkillResponse submitReview(Long actor, Long projectId, Long skillId) {
        SkillEntity skill = requireOwned(actor, projectId, skillId);
        if (!SUBMITTABLE.contains(skill.getStatus())) {
            throw stateConflict(skill.getStatus());
        }
        skill.setStatus("PENDING_REVIEW");
        skill.setSubmittedBy(actor);
        skill.setSubmittedAt(LocalDateTime.now(ZoneOffset.UTC));
        skillMapper.updateById(skill);
        publish("skill.submit-review", projectId, skillId);
        return toResponse(skillMapper.selectById(skillId));
    }

    /**
     * 发布 Skill（Project Admin）；仅 PENDING_REVIEW，发布后自动转为 PROJECT_SHARED。
     */
    @Transactional
    public SkillResponse approve(Long actor, Long projectId, Long skillId) {
        access.requireProjectAdmin(projectId, actor);
        SkillEntity skill = requireSkillInProject(projectId, skillId);
        if (!"PENDING_REVIEW".equals(skill.getStatus())) {
            throw stateConflict(skill.getStatus());
        }
        skill.setStatus("PUBLISHED");
        skill.setVisibility("PROJECT_SHARED");
        skill.setReviewerId(actor);
        skill.setReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
        skillMapper.updateById(skill);
        publish("skill.published", projectId, skillId);
        return toResponse(skillMapper.selectById(skillId));
    }

    /**
     * 拒绝 Skill 并给出原因（Project Admin）；仅 PENDING_REVIEW。
     */
    @Transactional
    public SkillResponse reject(Long actor, Long projectId, Long skillId, String reason) {
        access.requireProjectAdmin(projectId, actor);
        SkillEntity skill = requireSkillInProject(projectId, skillId);
        if (!"PENDING_REVIEW".equals(skill.getStatus())) {
            throw stateConflict(skill.getStatus());
        }
        skill.setStatus("REJECTED");
        skill.setReviewerId(actor);
        skill.setRejectionReason(reason);
        skill.setReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
        skillMapper.updateById(skill);
        publish("skill.rejected", projectId, skillId);
        return toResponse(skillMapper.selectById(skillId));
    }

    /**
     * 创建者或项目管理员下线已发布 Skill；仅 PUBLISHED。
     */
    @Transactional
    public SkillResponse archive(Long actor, Long projectId, Long skillId) {
        SkillEntity skill = requireOwned(actor, projectId, skillId);
        if (!"PUBLISHED".equals(skill.getStatus())) {
            throw stateConflict(skill.getStatus());
        }
        skill.setStatus("ARCHIVED");
        skillMapper.updateById(skill);
        publish("skill.archived", projectId, skillId);
        return toResponse(skillMapper.selectById(skillId));
    }

    /**
     * 发布状态变化，通知在线客户端刷新 Skill。
     */
    private void publish(String event, Long projectId, Long skillId) {
        eventService.publish(
            projectId,
            null,
            event,
            skillId.toString(),
            Map.of(
                "projectId",
                projectId.toString(),
                "resourceType",
                "SKILL",
                "resourceId",
                skillId.toString(),
                "eventVersion",
                1,
                "updatedAt",
                LocalDateTime.now(ZoneOffset.UTC).toInstant(ZoneOffset.UTC).toString()));
    }

    private SkillEntity requireSkillInProject(Long projectId, Long skillId) {
        SkillEntity skill = skillMapper.selectById(skillId);
        if (skill == null || !skill.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SKILL_NOT_FOUND", "Skill 不存在或无权访问");
        }
        return skill;
    }

    private SkillEntity requireOwned(Long actor, Long projectId, Long skillId) {
        access.requireProjectMember(projectId, actor);
        SkillEntity skill = requireSkillInProject(projectId, skillId);
        if (!skill.getCreatedBy().equals(actor)) {
            access.requireProjectAdmin(projectId, actor);
        }
        return skill;
    }

    private SkillEntity requireEditable(Long actor, Long projectId, Long skillId) {
        SkillEntity skill = requireOwned(actor, projectId, skillId);
        if (!EDITABLE.contains(skill.getStatus())) {
            throw stateConflict(skill.getStatus());
        }
        return skill;
    }

    private ApiException stateConflict(String status) {
        return new ApiException(
            HttpStatus.CONFLICT, "SKILL_STATE_CONFLICT", "Skill 当前状态不允许该操作: " + status);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private SkillResponse toResponse(SkillEntity skill) {
        return new SkillResponse(
            skill.getId().toString(),
            skill.getProjectId().toString(),
            skill.getName(),
            skill.getDescription(),
            skill.getContent(),
            skill.getTags(),
            skill.getVisibility(),
            skill.getStatus(),
            userSummary(skill.getCreatedBy()),
            userSummary(skill.getReviewerId()),
            skill.getRejectionReason(),
            skill.getReviewedAt(),
            skill.getCreatedAt(),
            skill.getUpdatedAt());
    }

    private UserSummary userSummary(Long userId) {
        if (userId == null) {
            return null;
        }
        UserEntity user = userMapper.selectById(userId);
        return user == null
            ? null
            : new UserSummary(
            user.getId().toString(), user.getDisplayName(), user.getAvatarUrl());
    }

    /**
     * 目录只包含可见且已发布 Skill 的标识和名称，正文显式激活时再读取。
     */
    public List<ContextSkill> publishedCatalog(Long actor, Long projectId) {
        access.requireProjectMember(projectId, actor);
        return skillMapper.listPublishedCatalog(projectId, actor);
    }

    public SkillEntity activateSkill(Long actor, Long projectId, Long skillId) {
        access.requireProjectMember(projectId, actor);
        SkillEntity skill = skillMapper.findVisiblePublishedById(projectId, actor, skillId);
        if (skill == null) {
            throw new ApiException(
                HttpStatus.NOT_FOUND, "SKILL_NOT_AVAILABLE", "Skill 不存在、未发布或无权访问");
        }
        return skill;
    }
}
