package qg.qgent.chat.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建群时可同时邀请项目成员及可用 Agent。创建者自动加入。
 */
@Data
public class GroupCreateRequest {
    @NotBlank
    @Size(max = 255)
    private String title;

    @Size(max = 100)
    @ArraySchema(schema = @Schema(type = "string"))
    private List<Long> userIds = List.of();

    @Size(max = 100)
    @ArraySchema(schema = @Schema(type = "string"))
    private List<Long> agentIds = List.of();
}
