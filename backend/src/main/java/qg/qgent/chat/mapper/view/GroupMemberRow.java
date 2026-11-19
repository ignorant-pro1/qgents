package qg.qgent.chat.mapper.view;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 项目或群聊成员与用户资料关联的查询结果，由服务组装为成员响应。
 */
@Data
public class GroupMemberRow {

    // 成员用户 ID。
    @Schema(description = "成员用户 ID")
    private Long userId;

    // 成员昵称。
    @Schema(description = "成员昵称")
    private String displayName;

    // 成员头像 URL，可为空。
    @Schema(description = "成员头像 URL")
    private String avatarUrl;

    // 成员登录邮箱（仅群成员管理场景填充；AGENT 成员为 null）。
    @Schema(description = "成员登录邮箱（仅 USER 群成员；AGENT 为 null）")
    private String email;
}
