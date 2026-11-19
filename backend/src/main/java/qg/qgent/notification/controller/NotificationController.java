package qg.qgent.notification.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.RequestIdFilter;
import qg.qgent.notification.service.NotificationService;
import qg.qgent.realtime.service.EventService;

import java.util.Map;

/**
 * 通知中心接口；查询历史、订阅实时事件和标记已读。
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final EventService eventService;

    /**
     * 返回当前用户通知列表（含 isRead，按时间倒序）。
     */
    @GetMapping
    public ApiResponse<?> list(@AuthenticationPrincipal Long userId, HttpServletRequest request) {
        return ok(notificationService.list(userId), request);
    }

    /**
     * 当前用户的实时通知事件；支持 Last-Event-ID 续传，游标过期返回 409。
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
        @AuthenticationPrincipal Long userId,
        @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId) {
        return eventService.notificationStream(userId, lastEventId);
    }

    /**
     * 标记单条通知已读（幂等）。
     */
    @PostMapping("/{notificationId}/read")
    public ApiResponse<?> markRead(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long notificationId,
        HttpServletRequest request) {
        notificationService.markRead(userId, notificationId);
        return ok(Map.of(), request);
    }

    /**
     * 全部通知已读（幂等）。
     */
    @PostMapping("/read-all")
    public ApiResponse<?> markAllRead(
        @AuthenticationPrincipal Long userId, HttpServletRequest request) {
        notificationService.markAllRead(userId);
        return ok(Map.of(), request);
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
