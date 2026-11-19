package qg.qgent.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 项目群聊视图，包含消息摘要及当前用户的未读和置顶状态。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupResponse {

    @Schema(description = "群 ID")
    private String id;

    @Schema(description = "所属项目 ID")
    private String projectId;

    @Schema(description = "群标题")
    private String title;

    @Schema(description = "群创建者用户 ID")
    private String createdBy;

    @Schema(description = "最近消息时间（ISO8601 UTC）")
    private String lastMessageAt;

    @Schema(description = "最近活跃时间（ISO8601 UTC）")
    private String latestActivityAt;

    @Schema(description = "最新消息摘要（发送者昵称 + 文本），从未发言为空")
    private GroupLatestMessage latestMessage;

    @Schema(description = "创建时间（ISO8601 UTC）")
    private String createdAt;

    @Schema(description = "群内用户及 Agent 数")
    private Long memberCount;

    @Schema(description = "当前用户在该群的未读消息数（排除本人消息），≥ 0")
    private Long unreadCount;

    @Schema(description = "当前用户在该群被 @ 的未读消息数（排除本人消息），≥ 0")
    private Long mentionedUnread;

    @Schema(description = "当前用户是否置顶该群（个人偏好，默认 false）")
    private Boolean pinned;

    // 默认群随项目创建，用户成员与项目同步。
    private Boolean defaultGroup;
}
