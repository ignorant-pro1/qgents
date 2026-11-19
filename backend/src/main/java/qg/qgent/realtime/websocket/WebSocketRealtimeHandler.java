package qg.qgent.realtime.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import qg.qgent.realtime.service.RealtimeHub;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 注册在线连接、发送连接确认和心跳，断线时移除连接。
 */
@Component
public class WebSocketRealtimeHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(WebSocketRealtimeHandler.class);

    // 心跳间隔（毫秒），对齐 SSE 每 15 秒心跳。
    private static final long HEARTBEAT_MS = 15_000L;

    private final RealtimeHub hub;

    public WebSocketRealtimeHandler(RealtimeHub hub) {
        this.hub = hub;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Object raw = session.getAttributes().get(RealtimeAuthInterceptor.USER_ID_ATTR);
        if (!(raw instanceof Long userId)) {
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        hub.register(userId, session);
        // 鉴权成功确认帧：客户端据此判定连接可用
        session.sendMessage(new TextMessage("{\"type\":\"hello\",\"userId\":\"" + userId + "\"}"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = sessionUserId(session);
        if (userId != null) {
            hub.unregister(userId, session);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        Long userId = sessionUserId(session);
        if (userId != null) {
            hub.unregister(userId, session);
        }
        log.debug("realtime transport error, userId={}: {}", userId, exception.getMessage());
    }

    /**
     * 实时通道只做服务端推送；收到客户端文本时记录 debug 日志并忽略。
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 当前协议不处理客户端文本。
        log.debug("realtime unsolicited client frame ignored, session={}", session.getId());
    }

    /**
     * 定时心跳：对每条在线连接发送 PingMessage 保活；发送失败则关闭并注销该连接。 依赖 Qgents 全局 {@code @EnableScheduling} 生效。
     */
    @Scheduled(fixedRate = HEARTBEAT_MS)
    public void heartbeat() {
        hub.forEachSession(session -> {
            try {
                session.sendMessage(new PingMessage(ByteBuffer.wrap(new byte[0])));
            } catch (IOException | IllegalStateException e) {
                Long userId = sessionUserId(session);
                if (userId != null) {
                    hub.unregister(userId, session);
                }
                try {
                    session.close();
                } catch (Exception ignored) {
                    // 连接已失效时 close 本身也可能抛错，忽略
                }
            }
        });
    }

    private Long sessionUserId(WebSocketSession session) {
        Object raw = session.getAttributes().get(RealtimeAuthInterceptor.USER_ID_ATTR);
        return raw instanceof Long userId ? userId : null;
    }
}
