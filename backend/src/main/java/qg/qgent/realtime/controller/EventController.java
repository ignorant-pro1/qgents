package qg.qgent.realtime.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import qg.qgent.common.web.ApiException;
import qg.qgent.realtime.service.EventService;

/**
 * 项目级实时事件流接口 返回 text/event-stream；可用 Last-Event-ID 断线续传，续传点过期返回 409。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/events")
public class EventController {
    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    /**
     * 订阅项目实时事件，每 15 秒发送心跳；支持保留期内的游标续传。
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
        @PathVariable Long projectId,
        @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId,
        Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "需要登录");
        }
        return eventService.stream(projectId, userId, lastEventId);
    }
}
