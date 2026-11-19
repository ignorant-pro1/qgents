package qg.qgent.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 设置 / 取消群置顶接口响应：返回该群最新的置顶状态。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupPinResponse {

    // 项目群聊 ID。
    @Schema(description = "项目群聊 ID")
    private String groupId;

    // 设置后的置顶状态：true 置顶，false 取消置顶。
    @Schema(description = "设置后的置顶状态：true 置顶，false 取消置顶")
    private Boolean pinned;
}
