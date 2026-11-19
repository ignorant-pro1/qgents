package qg.qgent.realtime.websocket;

import java.time.Instant;
import java.util.Map;

/**
 * 实时事件帧，包含事件类型、所属资源、内容和发送时间。
 */
public record RealtimeFrame(
    String type,
    String scope,
    String projectId,
    String groupId,
    String teamId,
    String recipientUserId,
    String resourceId,
    Map<String, Object> payload,
    Instant sentAt) {

    /**
     * 构造一条实时推送帧。
     */
    public static RealtimeFrame of(
        String type,
        String scope,
        String projectId,
        String groupId,
        String teamId,
        String recipientUserId,
        String resourceId,
        Map<String, Object> payload) {
        return new RealtimeFrame(
            type,
            scope,
            projectId,
            groupId,
            teamId,
            recipientUserId,
            resourceId,
            payload == null ? Map.of() : payload,
            Instant.now());
    }
}
