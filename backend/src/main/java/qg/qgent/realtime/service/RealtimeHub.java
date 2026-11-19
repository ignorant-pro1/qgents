package qg.qgent.realtime.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import qg.qgent.realtime.websocket.RealtimeFrame;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 保存每个用户的在线连接并推送事件；断线后由客户端重新查询数据。
 */
@Service
public class RealtimeHub {
    private static final Logger log = LoggerFactory.getLogger(RealtimeHub.class);

    private final ObjectMapper mapper;
    private final ConcurrentHashMap<Long, Set<WebSocketSession>> sessionsByUser =
        new ConcurrentHashMap<>();

    public RealtimeHub(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 注册一个用户的新在线连接（多设备/多标签各自独立 session）。
     */
    public void register(Long userId, WebSocketSession session) {
        sessionsByUser.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    /**
     * 注销一个连接（连接关闭时由 Handler 调用）。
     */
    public void unregister(Long userId, WebSocketSession session) {
        Set<WebSocketSession> sessions = sessionsByUser.get(userId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                sessionsByUser.remove(userId, sessions);
            }
        }
    }

    /**
     * 向一组用户的全部在线连接推送一条实时帧。 逐个 session 发送；发送失败/连接失效的连接被关闭并清理。串行化帧若失败仅记错误， 不向调用方（业务事务）抛出，保证实时推送失败不影响业务写入。
     */
    public void broadcastToUsers(Set<Long> userIds, RealtimeFrame frame) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        String json;
        try {
            json = mapper.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            log.warn(
                "realtime frame serialization failed, type={}: {}",
                frame.type(),
                e.getMessage());
            return;
        }
        TextMessage message = new TextMessage(json);
        for (Long userId : userIds) {
            Set<WebSocketSession> sessions = sessionsByUser.get(userId);
            if (sessions == null || sessions.isEmpty()) {
                continue;
            }
            for (WebSocketSession session : sessions) {
                if (!session.isOpen()) {
                    sessions.remove(session);
                    continue;
                }
                try {
                    synchronized (session) {
                        session.sendMessage(message);
                    }
                } catch (IOException | IllegalStateException e) {
                    log.debug("realtime send failed, userId={}: {}", userId, e.getMessage());
                    closeQuietly(session);
                    sessions.remove(session);
                }
            }
        }
    }

    /**
     * 遍历当前所有在线连接（供心跳/统计使用，不修改注册表）。
     */
    public void forEachSession(Consumer<WebSocketSession> visitor) {
        sessionsByUser
            .values()
            .forEach(sessions ->
                sessions.forEach(session -> {
                    if (session.isOpen()) {
                        visitor.accept(session);
                    }
                }));
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close();
        } catch (Exception ignored) {
            // 连接已失效时 close 自身也可能抛错，忽略
        }
    }
}
