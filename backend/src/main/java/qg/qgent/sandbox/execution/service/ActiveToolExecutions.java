package qg.qgent.sandbox.execution.service;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import qg.qgent.sandbox.dto.SandboxExecutionContext;
import qg.qgent.sandbox.execution.dto.ToolExecutionResponse;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 进程内执行归属、线程及完成通知；取消通知与工作线程实际退出分别管理。
 */
@Slf4j
final class ActiveToolExecutions {
    private final Map<Long, Execution> executions = new ConcurrentHashMap<>();

    ToolExecutionResponse execute(
        Long id, UUID sandboxId, Long agentRunId, Supplier<ToolExecutionResponse> body) {
        Execution execution = new Execution(sandboxId, agentRunId);
        execution.worker = Thread.currentThread();
        executions.put(id, execution);
        try {
            return body.get();
        } finally {
            executions.remove(id, execution);
        }
    }

    void submit(
        Long id,
        UUID sandboxId,
        Long agentRunId,
        ExecutorService pool,
        Supplier<ToolExecutionResponse> body) {
        Execution execution = new Execution(sandboxId, agentRunId);
        Map<String, String> logContext = MDC.getCopyOfContextMap();
        execution.task =
            new FutureTask<>(() ->
                SandboxExecutionContext.within(
                    agentRunId,
                    () -> {
                        Map<String, String> previousLog = MDC.getCopyOfContextMap();
                        execution.worker = Thread.currentThread();
                        setLogContext(logContext);
                        try {
                            execution.completion.complete(body.get());
                        } catch (RuntimeException exception) {
                            execution.completion.completeExceptionally(exception);
                            log.error(
                                "sandbox execution failed executionId={}",
                                id,
                                exception);
                        } finally {
                            execution.worker = null;
                            setLogContext(previousLog);
                            executions.remove(id, execution);
                        }
                        return null;
                    }));
        executions.put(id, execution);
        try {
            pool.execute(execution.task);
        } catch (RejectedExecutionException exception) {
            executions.remove(id, execution);
            execution.completion.completeExceptionally(exception);
            throw exception;
        }
    }

    CompletableFuture<ToolExecutionResponse> completion(Long id) {
        Execution execution = executions.get(id);
        return execution == null || execution.task == null ? null : execution.completion;
    }

    void cancel(Long id, ToolExecutionResponse result) {
        Execution execution = executions.get(id);
        if (execution == null) return;
        Thread worker = execution.worker;
        if (worker != null) worker.interrupt();
        execution.completion.complete(result);
        // 不能移除活动执行：数据库已取消时，释放仍须等待其线程完成收尾。
    }

    Set<Long> idsBySandbox(UUID sandboxId) {
        return executions.entrySet().stream()
            .filter(entry -> sandboxId.equals(entry.getValue().sandboxId))
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    }

    Set<Long> idsByAgentRun(Long agentRunId) {
        return executions.entrySet().stream()
            .filter(entry -> agentRunId.equals(entry.getValue().agentRunId))
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    }

    void awaitWorkers(Collection<Long> ids) {
        boolean interrupted = false;
        for (Long id : ids) {
            Execution execution = executions.get(id);
            if (execution == null || execution.task == null) continue;
            while (true) {
                try {
                    execution.task.get();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                } catch (java.util.concurrent.ExecutionException
                         | java.util.concurrent.CancellationException exception) {
                    break;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void setLogContext(Map<String, String> context) {
        if (context == null) MDC.clear();
        else MDC.setContextMap(context);
    }

    private static final class Execution {
        private final UUID sandboxId;
        private final Long agentRunId;
        private final CompletableFuture<ToolExecutionResponse> completion =
            new CompletableFuture<>();
        private volatile Thread worker;
        private FutureTask<Void> task;

        private Execution(UUID sandboxId, Long agentRunId) {
            this.sandboxId = sandboxId;
            this.agentRunId = agentRunId;
        }
    }
}
