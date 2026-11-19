package qg.qgent.skill.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上下文中的一条可激活 Skill 目录记录。
 * <p>
 * 默认上下文携带标识、名称和描述；完整正文由运行时工具显式激活，避免未使用的 Skill
 * 占用模型上下文。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContextSkill {

    // Skill ID，用于调用 activate_skill。
    @Schema(description = "Skill ID，可用于 activate_skill")
    private Long id;

    // Skill 名称。
    @Schema(description = "Skill 名称")
    private String name;

    @Schema(description = "Skill 用途与适用场景")
    private String description;
}
