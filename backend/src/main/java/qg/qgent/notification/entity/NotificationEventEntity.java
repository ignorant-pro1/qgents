package qg.qgent.notification.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 按用户保存的实时通知事件；sequenceNo 用于 SSE 断线续传。
 */
@Data
@TableName(value = "notification_events", autoResultMap = true)
public class NotificationEventEntity {

    // 事件 ID。
    @TableId(type = IdType.AUTO)
    private Long id;

    // 接收通知的用户 ID。
    private Long recipientUserId;

    // 用户内单调递增事件序号（SSE 游标）。
    private Long sequenceNo;

    // 关联通知 ID；可为空。
    private Long notificationId;

    // 事件名称，例如 notification.created。
    private String kind;

    // 事件内容，包含通知 ID 和通知类型。
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;

    // 产生时间（UTC）。
    private LocalDateTime createdAt;
}
