package qg.qgent.realtime.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import qg.qgent.common.config.PerformanceMetrics;
import qg.qgent.common.web.ApiException;
import qg.qgent.support.TestIds;

class SseEventTransportTest {

    @Test
    void sendsVisibleEventsWithTheirCursorAndReleasesTheConnectionAfterSendFailure()
            throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueuedExecutor executor = new QueuedExecutor();
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("disconnected"))
                .when(emitter)
                .send(any(SseEmitter.SseEventBuilder.class));
        SseEventTransport transport =
                transport(
                        executor,
                        timeout -> emitter,
                        new PerformanceMetrics(registry),
                        Clock.systemUTC());
        AtomicReference<Long> requestedCursor = new AtomicReference<>();

        transport.open(
                "project",
                TestIds.next(),
                0,
                cursor -> {
                    requestedCursor.set(cursor);
                    return List.of(
                            new SseEventTransport.SseEvent(1, "message.created", Map.of(), false),
                            new SseEventTransport.SseEvent(
                                    2, "task.updated", Map.of("status", "DONE"), true));
                },
                false);
        executor.runNext();

        var sent = org.mockito.ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(sent.capture());
        String frame =
                sent.getValue().build().stream()
                        .map(item -> String.valueOf(item.getData()))
                        .collect(Collectors.joining());
        assertTrue(frame.contains("id:2"));
        assertTrue(frame.contains("event:task.updated"));
        assertEquals(0L, requestedCursor.get());
        verify(emitter).complete();
        assertEquals(0.0, registry.get("qgents.sse.connections").gauge().value());
        assertEquals(
                1.0,
                registry.get("qgents.sse.send.failures")
                        .tag("operation", "event_send")
                        .tag("outcome", "failed")
                        .counter()
                        .count());
    }

    @Test
    void sendsHeartbeatAfterTheSharedIdleInterval() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueuedExecutor executor = new QueuedExecutor();
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("disconnected"))
                .when(emitter)
                .send(any(SseEmitter.SseEventBuilder.class));
        SseEventTransport transport =
                transport(
                        executor,
                        timeout -> emitter,
                        new PerformanceMetrics(registry),
                        new AdvancingClock());

        transport.open("notification", TestIds.next(), 8, cursor -> List.of(), false);
        executor.runNext();

        var sent = org.mockito.ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(sent.capture());
        String frame =
                sent.getValue().build().stream()
                        .map(item -> String.valueOf(item.getData()))
                        .collect(Collectors.joining());
        assertTrue(frame.contains("heartbeat"));
        assertEquals(0.0, registry.get("qgents.sse.connections").gauge().value());
    }

    @Test
    void enforcesTheSharedConnectionLimitAndReleasesEachSlotOnlyOnce() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueuedExecutor executor = new QueuedExecutor();
        SseEmitter firstEmitter = mock(SseEmitter.class);
        AtomicReference<Runnable> completion = new AtomicReference<>();
        doAnswer(
                        invocation -> {
                            completion.set(invocation.getArgument(0));
                            return null;
                        })
                .when(firstEmitter)
                .onCompletion(any());
        AtomicInteger created = new AtomicInteger();
        SseEventTransport transport =
                transport(
                        executor,
                        timeout ->
                                created.getAndIncrement() == 0
                                        ? firstEmitter
                                        : mock(SseEmitter.class),
                        new PerformanceMetrics(registry),
                        Clock.systemUTC());
        var eventLoader =
                (java.util.function.LongFunction<List<SseEventTransport.SseEvent>>)
                        cursor -> List.of();

        for (int i = 0; i < 128; i++) {
            transport.open("team", TestIds.next(), 0, eventLoader, false);
        }

        assertEquals(128.0, registry.get("qgents.sse.connections").gauge().value());
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> transport.open("team", TestIds.next(), 0, eventLoader, false));
        assertEquals("SSE_CONNECTION_LIMIT_EXCEEDED", error.code());

        completion.get().run();
        completion.get().run();
        executor.runNext();
        assertEquals(127.0, registry.get("qgents.sse.connections").gauge().value());
    }

    private SseEventTransport transport(
            Executor executor,
            SseEmitterFactory emitterFactory,
            PerformanceMetrics metrics,
            Clock clock) {
        return new SseEventTransport(executor, emitterFactory, metrics, clock);
    }

    private static final class QueuedExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runNext() {
            tasks.remove().run();
        }
    }

    private static final class AdvancingClock extends Clock {
        private final AtomicInteger reads = new AtomicInteger();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.EPOCH.plusSeconds(reads.getAndIncrement() == 0 ? 0 : 16);
        }
    }
}
