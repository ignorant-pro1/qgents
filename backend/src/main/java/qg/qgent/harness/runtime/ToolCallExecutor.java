package qg.qgent.harness.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.tool.ToolCallback;
import qg.qgent.harness.tool.ParallelToolCallback;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.sandbox.dto.SandboxExecutionContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/**
 * 连续的并行工具使用有界执行池；其他调用形成屏障，结果保持模型顺序。
 */
final class ToolCallExecutor {
    private final ObjectMapper json;
    private final int maxParallel;

    ToolCallExecutor(ObjectMapper json, int maxParallel) {
        if (maxParallel < 1 || maxParallel > 16)
            throw new IllegalArgumentException("max-parallel-tool-calls 必须为 1～16");
        this.json = json;
        this.maxParallel = maxParallel;
    }

    Result execute(
        Long runId,
        List<ToolCall> calls,
        Map<String, ToolCallback> callbacks,
        BooleanSupplier cancelled) {
        return execute(runId, calls, callbacks, cancelled, RunStreamSink.NONE);
    }

    Result execute(
        Long runId,
        List<ToolCall> calls,
        Map<String, ToolCallback> callbacks,
        BooleanSupplier cancelled,
        RunStreamSink sink) {
        return SandboxExecutionContext.within(
            runId, () -> dispatch(runId, calls, callbacks, cancelled, sink));
    }

    private Result dispatch(
        Long runId,
        List<ToolCall> calls,
        Map<String, ToolCallback> callbacks,
        BooleanSupplier cancelled,
        RunStreamSink sink) {
        List<ToolResponse> results = new ArrayList<>(Collections.nCopies(calls.size(), null));
        Map<String, String> context = MDC.getCopyOfContextMap();
        try (ExecutorService pool =
                 Executors.newFixedThreadPool(
                     maxParallel, Thread.ofVirtual().name("agent-tool-", 0).factory())) {
            for (int next = 0; next < calls.size(); ) {
                if (stopped(cancelled)) return new Result(results, true);
                if (!(callbacks.get(calls.get(next).name()) instanceof ParallelToolCallback)) {
                    results.set(next, invoke(calls.get(next), callbacks, sink));
                    next++;
                    continue;
                }
                int end = next;
                while (end < calls.size()
                    && callbacks.get(calls.get(end).name()) instanceof ParallelToolCallback)
                    end++;
                if (parallel(
                    pool, calls, callbacks, next, end, results, cancelled, runId, context, sink))
                    return new Result(results, true);
                next = end;
            }
        }
        return new Result(results, stopped(cancelled));
    }

    private boolean parallel(
        ExecutorService pool,
        List<ToolCall> calls,
        Map<String, ToolCallback> callbacks,
        int start,
        int end,
        List<ToolResponse> results,
        BooleanSupplier cancelled,
        Long runId,
        Map<String, String> context,
        RunStreamSink sink) {
        var completed = new ExecutorCompletionService<CompletedCall>(pool);
        int next = start, active = 0;
        boolean stopped = false;
        while (next < end || active > 0) {
            stopped |= stopped(cancelled);
            while (!stopped && next < end && active < maxParallel) {
                int index = next++;
                completed.submit(() -> {
                    Map<String, String> previousContext = MDC.getCopyOfContextMap();
                    setMdc(context);
                    try {
                        return SandboxExecutionContext.within(
                            runId,
                            () ->
                                new CompletedCall(
                                    index,
                                    invoke(calls.get(index), callbacks, sink)));
                    } finally {
                        setMdc(previousContext);
                    }
                });
                active++;
                stopped |= stopped(cancelled);
            }
            if (active == 0) break;
            try {
                var future = completed.poll(100, TimeUnit.MILLISECONDS);
                if (future == null) continue;
                CompletedCall result = future.get();
                results.set(result.index(), result.response());
                active--;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return true;
            } catch (ExecutionException exception) {
                throw new IllegalStateException("工具调度失败", exception.getCause());
            }
        }
        // 取消后不补入新调用，已启动的调用全部结算后才释放根运行环境。
        return stopped;
    }

    private ToolResponse invoke(
        ToolCall call, Map<String, ToolCallback> callbacks, RunStreamSink sink) {
        sink.toolStarted(call.id(), call.name(), call.arguments());
        // 未知工具与异常一定是失败；正常返回时只信 ToolResult 的 ok 字段，其他结果不声称成功。
        Boolean ok = false;
        // 声明在 try 之外：finally 要用到最后的结果，未正常返回时按空结果上报。
        String result = null;
        try {
            ToolCallback callback = callbacks.get(call.name());
            if (callback == null) result = error("TOOL_NOT_FOUND", "本轮没有工具：" + call.name());
            else {
                try {
                    result = callback.call(call.arguments());
                    ok = resultOk(result);
                } catch (RuntimeException exception) {
                    // 已知业务错误保留稳定错误码，便于模型按代码而不是文案判断处置方式
                    String code =
                        exception instanceof qg.qgent.common.web.ApiException api
                            ? api.code()
                            : "TOOL_FAILED";
                    result =
                        error(
                            code,
                            exception.getMessage() == null
                                ? "工具执行失败"
                                : exception.getMessage());
                }
            }
        } finally {
            sink.toolFinished(call.id(), call.name(), ok, result);
        }
        return new ToolResponse(call.id(), call.name(), result);
    }

    /**
     * 工具结果里显式的 ok 字段；不是 ToolResult 形状时返回 null，由前端中性展示。
     */
    private Boolean resultOk(String result) {
        if (result == null) return null;
        try {
            var node = json.readTree(result);
            var ok = node == null ? null : node.get("ok");
            return ok != null && ok.isBoolean() ? ok.booleanValue() : null;
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private String error(String code, String message) {
        try {
            return json.writeValueAsString(ToolResult.error(code, message));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法编码工具结果", exception);
        }
    }

    private static boolean stopped(BooleanSupplier cancelled) {
        return Thread.currentThread().isInterrupted() || cancelled.getAsBoolean();
    }

    private static void setMdc(Map<String, String> context) {
        if (context == null) MDC.clear();
        else MDC.setContextMap(context);
    }

    record Result(List<ToolResponse> responses, boolean cancelled) {
    }

    private record CompletedCall(int index, ToolResponse response) {
    }
}
