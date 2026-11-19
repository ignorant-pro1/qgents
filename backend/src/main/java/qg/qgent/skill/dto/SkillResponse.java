package qg.qgent.skill.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import qg.qgent.user.dto.UserSummary;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Skill 视图。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillResponse {

    @Schema(description = "Skill ID")
    private String id;

    @Schema(description = "所属项目 ID")
    private String projectId;

    @Schema(description = "Skill 名称")
    private String name;

    @Schema(description = "Skill 用途描述")
    private String description;

    @Schema(description = "完整 SKILL.md 内容")
    private String content;

    @Schema(description = "标签列表")
    private List<String> tags;

    @Schema(description = "可见性：PRIVATE / PROJECT_SHARED")
    private String visibility;

    @Schema(description = "状态：DRAFT / PENDING_REVIEW / PUBLISHED / REJECTED / ARCHIVED")
    private String status;

    @Schema(description = "创建者摘要")
    private UserSummary creator;

    @Schema(description = "最近审核者摘要")
    private UserSummary reviewer;

    @Schema(description = "最近驳回原因")
    private String rejectionReason;

    @Schema(description = "最近审核时间（UTC）")
    private LocalDateTime reviewedAt;

    @Schema(description = "创建时间（UTC）")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间（UTC）")
    private LocalDateTime updatedAt;
}
