package qg.qgent.harness.runtime;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import qg.qgent.harness.tool.ParallelToolCallback;
import qg.qgent.sandbox.dto.SandboxExecutionContext;
import qg.qgent.support.TestIds;

@Timeout(10)
class ToolCallExecutorTest {
    private final ToolCallExecutor executor = new ToolCallExecutor(new ObjectMapper(), 2);

    @Test
    void replenishesAfterAnyCompletionAndReturnsModelOrderWithinLimit() throws Exception {
        var releaseFirst = new CountDownLatch(1);
        var firstStarted = new CountDownLatch(1);
        var thirdStarted = new CountDownLatch(1);
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        ToolCallback read =
                callback(
                        "read",
                        true,
                        input -> {
                            int count = active.incrementAndGet();
                            peak.accumulateAndGet(count, Math::max);
                            try {
                                if (input.equals("0")) {
                                    firstStarted.countDown();
                                    await(releaseFirst);
                                } else await(firstStarted);
                                if (input.equals("2")) thirdStarted.countDown();
                                return input;
                            } finally {
                                active.decrementAndGet();
                            }
                        });
        try (var caller = Executors.newSingleThreadExecutor()) {
            var running =
                    caller.submit(() ->
                            executor.execute(
                                    TestIds.next(),
                                    List.of(
                                            call("0", "read"),
                                            call("1", "read"),
                                            call("2", "read"),
                                            call("3", "read")),
                                    Map.of("read", read),
                                    () -> false));
            try {
                await(thirdStarted);
                assertThat(running.isDone()).isFalse();
                assertThat(peak.get()).isEqualTo(2);
            } finally {
                releaseFirst.countDown();
            }
            var result = running.get(5, TimeUnit.SECONDS);
            assertThat(result.cancelled()).isFalse();
            assertThat(result.responses())
                    .extracting(r -> r.id())
                    .containsExactly("0", "1", "2", "3");
            assertThat(result.responses())
                    .extracting(r -> r.responseData())
                    .containsExactly("0", "1", "2", "3");
            assertThat(peak.get()).isEqualTo(2);
        }
    }

    @Test
    void exclusiveCallsWaitForPriorReadsAndFinishBeforeLaterReads() throws Exception {
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var finished = new AtomicInteger();
        var written = new AtomicBoolean();
        ToolCallback read =
                callback(
                        "read",
                        true,
                        input -> {
                            if (input.equals("after")) {
                                assertThat(written.get()).isTrue();
                                return input;
                            }
                            started.countDown();
                            await(release);
                            assertThat(written.get()).isFalse();
                            finished.incrementAndGet();
                            return input;
                        });
        ToolCallback write =
                callback(
                        "write",
                        false,
                        input -> {
                            assertThat(finished.get()).isEqualTo(2);
                            written.set(true);
                            return "written";
                        });
        try (var caller = Executors.newSingleThreadExecutor()) {
            var running =
                    caller.submit(() ->
                            executor.execute(
                                    TestIds.next(),
                                    List.of(
                                            call("a", "read"),
                                            call("b", "read"),
                                            call("write", "write"),
                                            call("after", "read")),
                                    Map.of("read", read, "write", write),
                                    () -> false));
            try {
                await(started);
                assertThat(written.get()).isFalse();
            } finally {
                release.countDown();
            }
            assertThat(running.get(5, TimeUnit.SECONDS).responses()).hasSize(4);
            assertThat(written.get()).isTrue();
        }
    }

    @Test
    void failedAndUnknownCallsRetainTheirOwnIdsWithoutDroppingOtherResults() {
        ToolCallback broken =
                callback(
                        "broken",
                        true,
                        input -> {
                            throw new IllegalArgumentException("bad arguments");
                        });
        ToolCallback read = callback("read", true, input -> "ok");
        var result =
                executor.execute(
                        TestIds.next(),
                        List.of(call("0", "broken"), call("1", "read"), call("2", "missing")),
                        Map.of("broken", broken, "read", read),
                        () -> false);
        assertThat(result.responses()).extracting(r -> r.id()).containsExactly("0", "1", "2");
        assertThat(result.responses().get(0).responseData())
                .contains("TOOL_FAILED", "bad arguments");
        assertThat(result.responses().get(1).responseData()).isEqualTo("ok");
        assertThat(result.responses().get(2).responseData()).contains("TOOL_NOT_FOUND");
    }

    @Test
    void cancellationStopsReplenishmentAndDrainsAlreadyStartedCalls() throws Exception {
        var started = new CountDownLatch(2);
        var releaseFirst = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        var firstFinished = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        var count = new AtomicInteger();
        ToolCallback read =
                callback(
                        "read",
                        true,
                        input -> {
                            count.incrementAndGet();
                            started.countDown();
                            if (input.equals("0")) {
                                await(releaseFirst);
                                firstFinished.countDown();
                            } else await(releaseSecond);
                            return input;
                        });
        try (var caller = Executors.newSingleThreadExecutor()) {
            var running =
                    caller.submit(() ->
                            executor.execute(
                                    TestIds.next(),
                                    List.of(
                                            call("0", "read"),
                                            call("1", "read"),
                                            call("2", "read")),
                                    Map.of("read", read),
                                    cancelled::get));
            try {
                await(started);
                cancelled.set(true);
                releaseFirst.countDown();
                await(firstFinished);
                assertThat(running.isDone()).isFalse();
            } finally {
                releaseFirst.countDown();
                releaseSecond.countDown();
            }
            assertThat(running.get(5, TimeUnit.SECONDS).cancelled()).isTrue();
            assertThat(count.get()).isEqualTo(2);
        }
    }

    @Test
    void propagatesRunIdentityAndLoggingContextToEveryParallelCall() {
        Long runId = TestIds.next();
        Map<String, String> previous = MDC.getCopyOfContextMap();
        MDC.put("requestId", "parallel-request");
        try {
            ToolCallback read =
                    callback(
                            "read",
                            true,
                            input -> {
                                assertThat(SandboxExecutionContext.currentAgentRunId())
                                        .isEqualTo(runId);
                                assertThat(MDC.get("requestId")).isEqualTo("parallel-request");
                                return input;
                            });
            var result =
                    executor.execute(
                            runId,
                            List.of(call("0", "read"), call("1", "read")),
                            Map.of("read", read),
                            () -> false);
            assertThat(result.responses())
                    .extracting(r -> r.responseData())
                    .containsExactly("0", "1");
            assertThat(SandboxExecutionContext.currentAgentRunId()).isNull();
            assertThat(MDC.get("requestId")).isEqualTo("parallel-request");
        } finally {
            if (previous == null) MDC.clear();
            else MDC.setContextMap(previous);
        }
    }

    @Test
    void reportsToolLifecycleWithCallIdsAndFailureOutcome() {
        var events = new java.util.ArrayList<String>();
        var outputs = new java.util.HashMap<String, String>();
        RunStreamSink sink =
                new RunStreamSink() {
                    public void roundStarted() {}

                    public void delta(String text) {}

                    public void toolStarted(String toolCallId, String toolName, String input) {
                        events.add("started:" + toolCallId + ":" + toolName + ":" + input);
                    }

                    public void toolFinished(
                            String toolCallId, String toolName, Boolean ok, String output) {
                        events.add("finished:" + toolCallId + ":" + toolName + ":" + ok);
                        outputs.put(toolCallId, output);
                    }

                    public void finished() {}
                };
        ToolCallback failing =
                callback(
                        "boom",
                        false,
                        input -> {
                            throw new IllegalStateException("炸了");
                        });
        ToolCallback softFailure = callback("soft", false, input -> "{\"ok\":false,\"errorCode\":\"X\"}");
        ToolCallback success = callback("good", false, input -> "{\"ok\":true}");

        var result =
                executor.execute(
                        TestIds.next(),
                        List.of(
                                new ToolCall("call-x", "function", "boom", "{}"),
                                new ToolCall("call-y", "function", "missing", "{}"),
                                new ToolCall("call-z", "function", "soft", "{}"),
                                new ToolCall("call-w", "function", "good", "{}")),
                        Map.of("boom", failing, "soft", softFailure, "good", success),
                        () -> false,
                        sink);

        assertThat(result.responses())
                .extracting(r -> r.id())
                .containsExactly("call-x", "call-y", "call-z", "call-w");
        assertThat(result.responses().get(0).responseData()).contains("TOOL_FAILED");
        assertThat(result.responses().get(1).responseData()).contains("TOOL_NOT_FOUND");
        assertThat(result.responses().get(2).responseData()).contains("\"ok\":false");
        assertThat(result.responses().get(3).responseData()).contains("\"ok\":true");
        assertThat(events)
                .containsExactly(
                        "started:call-x:boom:{}",
                        "finished:call-x:boom:false",
                        "started:call-y:missing:{}",
                        "finished:call-y:missing:false",
                        "started:call-z:soft:{}",
                        "finished:call-z:soft:false",
                        "started:call-w:good:{}",
                        "finished:call-w:good:true");
        assertThat(outputs)
                .containsEntry("call-z", "{\"ok\":false,\"errorCode\":\"X\"}")
                .containsEntry("call-w", "{\"ok\":true}");
        assertThat(outputs.get("call-x")).contains("TOOL_FAILED");
        assertThat(outputs.get("call-y")).contains("TOOL_NOT_FOUND");
    }

    @Test
    void oneSlotAndUnmarkedToolsRemainSerialAndInvalidLimitsAreRejected() {
        var thread = Thread.currentThread();
        ToolCallback serial =
                callback(
                        "serial",
                        false,
                        input -> {
                            assertThat(Thread.currentThread()).isSameAs(thread);
                            return input;
                        });
        var one = new ToolCallExecutor(new ObjectMapper(), 1);
        assertThat(
                        one.execute(
                                        TestIds.next(),
                                        List.of(call("0", "serial")),
                                        Map.of("serial", serial),
                                        () -> false)
                                .cancelled())
                .isFalse();
        assertThatThrownBy(() -> new ToolCallExecutor(new ObjectMapper(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolCallExecutor(new ObjectMapper(), 17))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ToolCall call(String id, String name) {
        return new ToolCall(id, "function", name, id);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(5, TimeUnit.SECONDS)).as("工具执行同步点").isTrue();
    }

    private static ToolCallback callback(String name, boolean parallel, Body body) {
        ToolCallback callback =
                new ToolCallback() {
                    public ToolDefinition getToolDefinition() {
                        return ToolDefinition.builder()
                                .name(name)
                                .description(name)
                                .inputSchema("{}")
                                .build();
                    }

                    public String call(String input) {
                        try {
                            return body.call(input);
                        } catch (RuntimeException exception) {
                            throw exception;
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    }
                };
        return parallel ? new ParallelToolCallback(callback) : callback;
    }

    @FunctionalInterface
    private interface Body {
        String call(String input) throws Exception;
    }
}
