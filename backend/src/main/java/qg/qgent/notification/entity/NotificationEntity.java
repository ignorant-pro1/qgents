package qg.qgent.notification.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 按用户持久化的通知，包含消息提及、团队邀请及项目成员变更。
 */
@Data
@TableName("notifications")
public class NotificationEntity {

    // 通知 ID。
    @TableId(type = IdType.AUTO)
    private Long id;

    // 接收通知的用户 ID，外键指向 users(id)。
    private Long recipientUserId;

    // 关联项目 ID，点击跳转用；系统级通知为空。
    private Long projectId;

    // 关联项目群聊 ID；非群来源的通知为空。
    private Long groupId;

    // 通知类型：MESSAGE_MENTION/INVITED/TEAM_JOINED/PROJECT_ADDED。
    private String kind;

    // 一行通知标题（≤255 字符）。
    private String title;

    // 通知描述正文，补充说明；可为空。
    private String description;

    // 关联消息、邀请或成员的 ID，跳转定位用；可为空。
    private String resourceId;

    // 是否已读：true 已读，false 未读（TINYINT(1)）。
    private Boolean isRead;

    // 产生时间（UTC）。
    private LocalDateTime createdAt;

    // 已读时间（UTC），未读为空。
    private LocalDateTime readAt;
}
