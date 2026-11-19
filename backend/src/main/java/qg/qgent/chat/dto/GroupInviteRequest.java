package qg.qgent.chat.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 向已有群聊追加成员，不替换已加入的成员。
 */
@Data
public class GroupInviteRequest {
    @Size(max = 100)
    @ArraySchema(schema = @Schema(type = "string"))
    private List<Long> userIds = List.of();

    @Size(max = 100)
    @ArraySchema(schema = @Schema(type = "string"))
    private List<Long> agentIds = List.of();
}
