package qg.qgent.realtime.transport;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import qg.qgent.common.config.PerformanceMetrics;
import qg.qgent.common.web.ApiException;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;

/**
 * 为项目、团队和通知事件流运行共用的 SSE 连接泵。
 */
@Service
public class SseEventTransport {
    private static final Logger log = LoggerFactory.getLogger(SseEventTransport.class);

    private static final long HEARTBEAT_SECONDS = 15;
    private static final long POLL_INTERVAL_MS = 2000;
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;
    private static final int MAX_SSE_CONNECTIONS = 128;

    private final Executor executor;
    private final SseEmitterFactory emitterFactory;
    private final PerformanceMetrics metrics;
    private final Clock clock;
    private final AtomicInteger activeConnections = new AtomicInteger();

    public SseEventTransport(
        @Qualifier("sseEventPumpExecutor") Executor executor,
        SseEmitterFactory emitterFactory,
        PerformanceMetrics metrics,
        Clock clock) {
        this.executor = executor;
        this.emitterFactory = emitterFactory;
        this.metrics = metrics;
        this.clock = clock;
        Gauge.builder("qgents.sse.connections", activeConnections, AtomicInteger::get)
            .description("当前活跃的项目 SSE 连接数")
            .register(metrics.registry());
    }

    /**
     * 由具体事件范围查询并完成可见性判断后的 SSE 事件。
     */
    public record SseEvent(
        long sequenceNo, String type, Map<String, Object> payload, boolean visible) {
    }

    public SseEmitter open(
        String scope,
        Long ownerId,
        long cursor,
        LongFunction<List<SseEvent>> loadEvents,
        boolean measurePoll) {
        if (!acquireConnectionSlot()) {
            throw new ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "SSE_CONNECTION_LIMIT_EXCEEDED",
                "当前实时连接过多，请稍后重试");
        }
        SseEmitter emitter = emitterFactory.create(SSE_TIMEOUT_MS);
        AtomicBoolean connectionClosed = new AtomicBoolean();
        emitter.onTimeout(() -> {
            closeConnection(connectionClosed);
            log.info("SSE {} timeout, ownerId={}, cursor={}", scope, ownerId, cursor);
        });
        emitter.onError(error -> {
            closeConnection(connectionClosed);
            log.info("SSE {} error, ownerId={}: {}", scope, ownerId, error.getMessage());
        });
        emitter.onCompletion(() -> {
            closeConnection(connectionClosed);
            log.info("SSE {} completed, ownerId={}, cursor={}", scope, ownerId, cursor);
        });
        try {
            executor.execute(
                () -> pump(emitter, cursor, loadEvents, measurePoll, connectionClosed));
        } catch (RuntimeException failure) {
            closeConnection(connectionClosed);
            throw failure;
        }
        return emitter;
    }

    /**
     * Polls incrementally, advances past hidden events, and sends a shared idle heartbeat.
     */
    private void pump(
        SseEmitter emitter,
        long cursor,
        LongFunction<List<SseEvent>> loadEvents,
        boolean measurePoll,
        AtomicBoolean connectionClosed) {
        LocalDateTime lastHeartbeat = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
        try {
            while (!connectionClosed.get()) {
                Timer.Sample pollTimer = measurePoll ? metrics.start() : null;
                List<SseEvent> events = loadEvents.apply(cursor);
                if (measurePoll) {
                    metrics.stop(pollTimer, "qgents.sse.poll.duration", "event_poll", "succeeded");
                }
                for (SseEvent event : events) {
                    if (event.visible()
                        && !send(
                        emitter,
                        SseEmitter.event()
                            .id(String.valueOf(event.sequenceNo()))
                            .name(event.type())
                            .data(event.payload()))) {
                        return;
                    }
                    cursor = event.sequenceNo();
                }
                LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
                if (Duration.between(lastHeartbeat, now).getSeconds() >= HEARTBEAT_SECONDS) {
                    if (!send(emitter, SseEmitter.event().comment("heartbeat"))) return;
                    lastHeartbeat = now;
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeConnection(connectionClosed);
        }
    }

    private boolean send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            metrics.increment("qgents.sse.send.failures", "event_send", "failed");
            // 客户端断开后可从最后收到的事件序号重新连接。
            completeQuietly(emitter);
            return false;
        }
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 连接可能已经关闭。
        }
    }

    private boolean acquireConnectionSlot() {
        while (true) {
            int current = activeConnections.get();
            if (current >= MAX_SSE_CONNECTIONS) {
                log.warn(
                    "SSE connection limit reached, active={} max={}",
                    current,
                    MAX_SSE_CONNECTIONS);
                return false;
            }
            if (activeConnections.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void closeConnection(AtomicBoolean connectionClosed) {
        if (connectionClosed.compareAndSet(false, true)) {
            activeConnections.decrementAndGet();
        }
    }
}
