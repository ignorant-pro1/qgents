package qg.qgent.skill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 项目 Skill 的内容、可见范围和审核状态；已发布正文由 Agent 通过 activate_skill 按需读取。
 */
@Data
@TableName(value = "skills", autoResultMap = true)
public class SkillEntity {

    // Skill ID。
    @TableId(type = IdType.AUTO)
    private Long id;

    // 所属项目 ID。
    private Long projectId;

    // 创建用户 ID。
    private Long createdBy;

    // Skill 名称。
    private String name;

    private String description;

    // 可复用操作规范正文（MEDIUMTEXT）。
    private String content;

    // 标签 JSON 数组。
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tags;

    // 可见性枚举：PRIVATE / PROJECT_SHARED。
    private String visibility;

    // 状态枚举：DRAFT / PENDING_REVIEW / PUBLISHED / REJECTED / ARCHIVED。
    private String status;

    // 最近提交审核用户 ID；从未提交审核时为空。
    private Long submittedBy;

    // 最近提交审核时间（UTC）；从未提交审核时为空。
    private LocalDateTime submittedAt;

    // 最近审核用户 ID；可为空。
    private Long reviewerId;

    // 最近驳回原因；可为空。
    private String rejectionReason;

    // 最近审核时间（UTC）；可为空。
    private LocalDateTime reviewedAt;

    // 创建时间（UTC）。
    private LocalDateTime createdAt;

    // 更新时间（UTC）。
    private LocalDateTime updatedAt;
}
