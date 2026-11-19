package qg.qgent.skill.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 修改 Skill 内容，未传字段不修改。
 */
@Data
public class SkillUpdateRequest {

    @Schema(description = "新正文，null 表示不修改")
    private String content;

    @Schema(description = "新标签列表，null 表示不修改")
    private List<String> tags;
}
