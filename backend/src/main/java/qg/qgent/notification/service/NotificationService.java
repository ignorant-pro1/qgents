package qg.qgent.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.common.web.ApiException;
import qg.qgent.notification.dto.NotificationResponse;
import qg.qgent.notification.entity.NotificationEntity;
import qg.qgent.notification.mapper.NotificationMapper;
import qg.qgent.realtime.service.EventService;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 通知持久化、已读状态和实时推送。
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationMapper notificationMapper;
    private final EventService eventService;
    private final PushNotificationService pushNotificationService;

    /**
     * 查询当前用户的通知列表，按产生时间倒序，不分页。
     */
    public List<NotificationResponse> list(Long userId) {
        return notificationMapper.listByRecipient(userId).stream().map(this::toResponse).toList();
    }

    /**
     * 标记单条通知已读；通知不存在或不属于当前用户时返回 404。
     */
    @Transactional
    public void markRead(Long userId, Long notificationId) {
        NotificationEntity entity = notificationMapper.selectById(notificationId);
        if (entity == null || !entity.getRecipientUserId().equals(userId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "通知不存在或无权访问");
        }
        notificationMapper.markRead(notificationId, userId, LocalDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 将当前用户的全部未读通知标记为已读（幂等）。
     */
    @Transactional
    public void markAllRead(Long userId) {
        notificationMapper.markAllRead(userId, LocalDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 为指定用户保存通知、发布实时事件，并加入手机推送队列。
     */
    @Transactional
    public void notify(
        Long recipientUserId,
        Long projectId,
        Long groupId,
        String kind,
        String title,
        String description,
        String resourceId) {
        NotificationEntity entity = new NotificationEntity();
        entity.setRecipientUserId(recipientUserId);
        entity.setProjectId(projectId);
        entity.setGroupId(groupId);
        entity.setKind(kind);
        entity.setTitle(title);
        entity.setDescription(description);
        entity.setResourceId(resourceId);
        entity.setIsRead(false);
        entity.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        notificationMapper.insert(entity);
        // 保存实时事件，在线客户端通过 SSE 或 WebSocket 接收。
        eventService.publishNotification(
            recipientUserId,
            entity.getId(),
            "notification.created",
            Map.of("notificationId", entity.getId().toString(), "kind", kind));
        pushNotificationService.enqueue(entity);
    }

    private NotificationResponse toResponse(NotificationEntity n) {
        return new NotificationResponse(
            n.getId().toString(),
            n.getKind(),
            n.getTitle(),
            n.getDescription(),
            n.getIsRead(),
            iso(n.getCreatedAt()),
            id(n.getProjectId()),
            id(n.getGroupId()),
            n.getResourceId());
    }

    private String id(Long value) {
        return value == null ? null : value.toString();
    }

    private String iso(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
    }
}
