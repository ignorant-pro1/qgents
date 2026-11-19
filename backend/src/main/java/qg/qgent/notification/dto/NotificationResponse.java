package qg.qgent.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通知列表响应，包含通知内容、已读状态与跳转资源。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationResponse {

    @Schema(description = "通知 ID")
    private String id;

    @Schema(description = "通知类型：MESSAGE_MENTION/INVITED/TEAM_JOINED/PROJECT_ADDED")
    private String kind;

    @Schema(description = "一行标题")
    private String title;

    @Schema(description = "补充说明")
    private String description;

    @Schema(description = "是否已读")
    private Boolean isRead;

    @Schema(description = "产生时间（ISO8601 UTC）")
    private String createdAt;

    @Schema(description = "所属项目 ID")
    private String projectId;

    @Schema(description = "来源项目群聊 ID")
    private String groupId;

    @Schema(description = "关联资源 ID，用于定位消息、邀请或成员")
    private String resourceId;
}
