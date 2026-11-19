package qg.qgent.harness.runtime;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import qg.qgent.harness.entity.AgentRunEntity;
import qg.qgent.harness.entity.RunStatus;
import qg.qgent.harness.service.AgentRunService;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.workspace.service.WorkspaceSelection;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * 根运行派发的子 Agent。
 * <p>
 * 容量、排队、续接、停止和完成通知全部在这一个状态机里；收尾顺序由 {@link RunLifecycle} 统一。
 * 根运行只提供取消信号、子 Agent 的工具集和对话工厂。
 */
final class ChildRuns {
    private final Long rootRunId;
    private final int limit;
    private final Executor executor;
    private final AgentRunService runs;
    private final RunLifecycle lifecycle;
    private final BooleanSupplier rootCancelled;
    private final AgentLoop loop;
    private final Function<Execution, List<ToolCallback>> tools;
    private final Function<AgentRunEntity, RunStreamSink> sinks;

    private final Map<Long, Execution> children = new ConcurrentHashMap<>();
    private final Set<Long> cancelledCommands = ConcurrentHashMap.newKeySet();

    private final Object lock = new Object();
    private final Map<Long, Message> notifications = new LinkedHashMap<>();
    // 与 notifications 同一把锁，保证最终通知与“没有活动子 Agent”同时可见。
    private int active;
    private volatile boolean closing;

    ChildRuns(
        Long rootRunId,
        int limit,
        Executor executor,
        AgentRunService runs,
        RunLifecycle lifecycle,
        BooleanSupplier rootCancelled,
        AgentLoop loop,
        Function<Execution, List<ToolCallback>> tools,
        Function<AgentRunEntity, RunStreamSink> sinks) {
        this.rootRunId = rootRunId;
        this.limit = limit;
        this.executor = executor;
        this.runs = runs;
        this.lifecycle = lifecycle;
        this.rootCancelled = rootCancelled;
        this.loop = loop;
        this.tools = tools;
        this.sinks = sinks;
    }

    ToolResult start(
        AgentRunEntity parent,
        String instruction,
        Long workspaceId,
        String directory,
        Function<String, AgentLoop.Conversation> conversation) {
        if (!hasCapacity()) {
            return ToolResult.error("SUBAGENT_LIMIT_REACHED", "同时运行的子 Agent 已达到上限，请等待或复用现有子对话");
        }
        AgentRunEntity run = runs.startChild(parent, instruction, workspaceId);
        Execution child =
            new Execution(run, conversation.apply(instruction), workspaceId, directory);
        if (!reserve(child)) {
            runs.finish(run, RunStatus.FAILED, "同时运行的子 Agent 已达到上限", 0);
            return ToolResult.error("SUBAGENT_LIMIT_REACHED", "同时运行的子 Agent 已达到上限，请等待或复用现有子对话");
        }
        try {
            submit(child);
        } catch (RejectedExecutionException exception) {
            finish(child, RunStatus.FAILED, "子 Agent 执行队列已满，请稍后重试", 0, null);
            return ToolResult.error("SUBAGENT_QUEUE_FULL", "子 Agent 执行队列已满，请稍后重试");
        }
        ToolResult result = ToolResult.ok();
        result.put("runId", run.getId().toString());
        return result;
    }

    /**
     * 忙碌时排入同一对话，已结束时续接原对话。
     */
    ToolResult handoff(String runId, String message) {
        if (message == null || message.isBlank())
            return ToolResult.error("INVALID_ARGUMENT", "补充内容不能为空");
        if (rootCancelled.getAsBoolean())
            return ToolResult.error("RUN_CANCELLED", "根 Agent 正在停止，无法续接子 Agent");
        Execution child = require(runId);
        synchronized (child) {
            if (child.isFinishing()) return ToolResult.error("SUBAGENT_BUSY", "子 Agent 正在结算，请稍后续接");
            if (child.isBusy()) {
                if (child.isStopRequested())
                    return ToolResult.error("SUBAGENT_BUSY", "子 Agent 正在停止，完成后再续接");
                child.enqueueMessage(message);
                return accepted(runId, true);
            }
            if (!reserveContinuation(child))
                return ToolResult.error("SUBAGENT_LIMIT_REACHED", "同时运行的子 Agent 已达到上限");
            child.resumeConversation(message);
            runs.resume(child.run, message);
            try {
                submit(child);
            } catch (RejectedExecutionException exception) {
                finish(child, RunStatus.FAILED, "子 Agent 执行队列已满，请稍后重试", 0, null);
                return ToolResult.error("SUBAGENT_QUEUE_FULL", "子 Agent 执行队列已满，请稍后重试");
            }
            return accepted(runId, false);
        }
    }

    ToolResult waitFor(String runId, int waitSeconds) {
        return status(runId, require(runId).await(waitSeconds, TimeUnit.SECONDS) == null);
    }

    /**
     * 读取状态；非等待读取同时清掉该子 Agent 的完成通知。
     */
    ToolResult status(String runId, boolean waiting) {
        Execution child = require(runId);
        if (!waiting) consumeNotification(child.run.getId());
        ToolResult result = ToolResult.ok();
        result.put("runId", child.run.getId().toString());
        result.put("status", child.run.getStatus().name());
        result.put("result", child.run.getResult() == null ? "" : child.run.getResult());
        result.put("waiting", waiting);
        return result;
    }

    ToolResult stop(String runId) {
        Execution child = require(runId);
        lifecycle.cancelled(child.run);
        ToolResult result = ToolResult.ok();
        result.put("runId", runId);
        result.put("stopping", child.isBusy());
        return result;
    }

    /**
     * 根运行取消或失败时停止全部子 Agent，并取消它们仍在运行的命令。
     */
    void cancelAll() {
        children.values().forEach(child -> lifecycle.cancelled(child.run));
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    Execution require(String runId) {
        Execution child = children.get(parseId(runId));
        if (child == null) throw new IllegalArgumentException("该子 Agent 不属于当前根运行");
        return child;
    }

    Execution execution(Long runId) {
        return children.get(runId);
    }

    boolean cancelled(Long runId) {
        return rootCancelled.getAsBoolean() || execution(runId).isStopRequested();
    }

    boolean consumeNotification(Long childRunId) {
        synchronized (lock) {
            return notifications.remove(childRunId) != null;
        }
    }

    /**
     * 根 loop 接收子 Agent 完成通知，等待期间不轮询存储。
     */
    AgentLoop.UpdateSource updates() {
        return new AgentLoop.UpdateSource() {
            public List<Message> drain() {
                return drainNotifications();
            }

            public List<Message> awaitNext() {
                synchronized (lock) {
                    while (notifications.isEmpty()
                        && active > 0
                        && !closing
                        && !rootCancelled.getAsBoolean()) {
                        try {
                            lock.wait();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            return List.of();
                        }
                    }
                }
                return drainNotifications();
            }
        };
    }

    AgentLoop.UpdateSource updates(Execution child) {
        return new AgentLoop.UpdateSource() {
            public List<Message> drain() {
                return child.drainMessages().stream()
                    .map(UserMessage::new)
                    .map(Message.class::cast)
                    .toList();
            }

            public List<Message> awaitNext() {
                return List.of();
            }
        };
    }

    /**
     * 根运行收尾：等待全部子 Agent 结算。
     */
    void awaitIdle() {
        boolean interrupted = false;
        synchronized (lock) {
            while (active > 0) {
                try {
                    lock.wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    void close() {
        closing = true;
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    boolean reserve(Execution child) {
        synchronized (child) {
            synchronized (lock) {
                if (!hasCapacity()) return false;
                active++;
                child.holdReservation();
                children.put(child.run.getId(), child);
                return true;
            }
        }
    }

    boolean reserveContinuation(Execution child) {
        synchronized (child) {
            synchronized (lock) {
                if (!hasCapacity()) return false;
                active++;
                child.holdReservation();
                cancelledCommands.remove(child.run.getId());
                return true;
            }
        }
    }

    private boolean hasCapacity() {
        synchronized (lock) {
            return !closing && !rootCancelled.getAsBoolean() && active < limit;
        }
    }

    boolean requestStop(Execution child) {
        synchronized (lock) {
            if (!child.isBusy() || child.isStopRequested()) return false;
            child.requestStop();
            return true;
        }
    }

    boolean markCommandsCancelled(Long runId) {
        return cancelledCommands.add(runId);
    }

    private void submit(Execution child) {
        java.util.concurrent.FutureTask<Void> task =
            new java.util.concurrent.FutureTask<>(() -> {
                runChild(child);
                return null;
            });
        child.schedule();
        executor.execute(task);
    }

    private void runChild(Execution child) {
        if (!child.beginWorker()) {
            finish(child, RunStatus.CANCELLED, "子 Agent 已停止", 0, null);
            return;
        }
        RunStreamSink sink = sinks.apply(child.run);
        int totalRounds = 0;
        try {
            while (true) {
                AgentLoop.Result result = null;
                RuntimeException failure = null;
                try {
                    result =
                        loop.run(
                            child.run.getId(),
                            child.conversation,
                            () -> tools.apply(child),
                            () -> rootCancelled.getAsBoolean() || child.isStopRequested(),
                            updates(child),
                            sink);
                    totalRounds += result.rounds();
                } catch (RuntimeException exception) {
                    failure = exception;
                }
                RunStatus finishStatus;
                String finishResult;
                synchronized (child) {
                    String pending =
                        child.drainMessages().stream()
                            .reduce((first, next) -> first + "\n\n" + next)
                            .orElse(null);
                    if (!rootCancelled.getAsBoolean()
                        && !child.isStopRequested()
                        && pending != null) {
                        child.conversation.addUserMessage(pending);
                        runs.resume(child.run, pending);
                        continue;
                    }
                    finishStatus =
                        rootCancelled.getAsBoolean() || child.isStopRequested()
                            ? RunStatus.CANCELLED
                            : failure == null && result.success()
                              ? RunStatus.SUCCEEDED
                              : RunStatus.FAILED;
                    finishResult = failure == null ? result.text() : failure(failure);
                    if (!child.beginFinishing()) break;
                }
                lifecycle.finish(child.run, finishStatus, finishResult, totalRounds);
                sink.finished();
                return;
            }
        } catch (RuntimeException exception) {
            finish(
                child,
                rootCancelled.getAsBoolean() || child.isStopRequested()
                    ? RunStatus.CANCELLED
                    : RunStatus.FAILED,
                failure(exception),
                totalRounds,
                sink);
        }
    }

    private void finish(
        Execution child,
        RunStatus status,
        String result,
        int rounds,
        RunStreamSink sink) {
        synchronized (child) {
            if (!child.beginFinishing()) return;
        }
        lifecycle.finish(child.run, status, result, rounds);
        if (sink != null) sink.finished();
    }

    boolean publishFinish(Execution child) {
        synchronized (child) {
            synchronized (lock) {
                if ((rootCancelled.getAsBoolean() || child.isStopRequested())
                    && child.run.getStatus() != RunStatus.CANCELLED) {
                    return false;
                }
                notifications.put(
                    child.run.getId(),
                    new UserMessage(
                        "子 Agent "
                            + child.run.getId()
                            + " 已结束，状态："
                            + child.run.getStatus()
                            + "。执行结果：\n"
                            + (child.run.getResult() == null
                            ? ""
                            : child.run.getResult())));
                if (child.releaseReservation()) active--;
                child.finishTurn(child.run);
                lock.notifyAll();
                return true;
            }
        }
    }

    private List<Message> drainNotifications() {
        synchronized (lock) {
            if (notifications.isEmpty()) return List.of();
            List<Message> result = new ArrayList<>(notifications.values());
            notifications.clear();
            return result;
        }
    }

    private static ToolResult accepted(String runId, boolean queued) {
        ToolResult result = ToolResult.ok();
        result.put("runId", runId);
        result.put("queued", queued);
        return result;
    }

    private static Long parseId(String value) {
        try {
            return Long.valueOf(value == null ? "" : value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("子 Agent runId 无效");
        }
    }

    private static String failure(RuntimeException exception) {
        String message = exception.getMessage();
        return "Agent 执行失败：" + (message == null || message.isBlank() ? "请稍后重试" : message);
    }

    /**
     * 子 Agent 的对话、工具集与停止状态；由本模块独占。
     */
    static final class Execution {
        final AgentRunEntity run;
        final AgentLoop.Conversation conversation;
        final WorkspaceSelection workspace;
        private final java.util.ArrayDeque<String> messages = new java.util.ArrayDeque<>();
        private CompletableFuture<AgentRunEntity> completion = new CompletableFuture<>();
        private volatile Thread worker;
        private volatile boolean busy;
        private volatile boolean stopRequested;
        private boolean reservationHeld;
        private boolean finishing;
        private boolean turnFinished;

        Execution(
            AgentRunEntity run,
            AgentLoop.Conversation conversation,
            Long selectedWorkspace,
            String currentDirectory) {
            this.run = run;
            this.conversation = conversation;
            this.workspace = new WorkspaceSelection(selectedWorkspace, currentDirectory);
        }

        boolean isBusy() {
            return busy;
        }

        boolean isStopRequested() {
            return stopRequested;
        }

        synchronized boolean isFinishing() {
            return finishing;
        }

        synchronized void schedule() {
            busy = true;
            stopRequested = false;
            finishing = false;
            turnFinished = false;
        }

        synchronized boolean beginWorker() {
            if (!busy || stopRequested || Thread.currentThread().isInterrupted()) return false;
            worker = Thread.currentThread();
            return true;
        }

        synchronized void enqueueMessage(String message) {
            if (message != null && !message.isBlank()) messages.addLast(message);
        }

        synchronized List<String> drainMessages() {
            if (messages.isEmpty()) return List.of();
            List<String> result = new ArrayList<>(messages);
            messages.clear();
            return result;
        }

        synchronized void resumeConversation(String message) {
            stopRequested = false;
            if (message != null && !message.isBlank()) conversation.addUserMessage(message);
            completion = new CompletableFuture<>();
        }

        void requestStop() {
            stopRequested = true;
            Thread active = worker;
            if (active != null) active.interrupt();
        }

        synchronized void holdReservation() {
            reservationHeld = true;
        }

        synchronized boolean releaseReservation() {
            if (!reservationHeld) return false;
            reservationHeld = false;
            return true;
        }

        synchronized boolean beginFinishing() {
            if (turnFinished || finishing) return false;
            finishing = true;
            return true;
        }

        synchronized void finishTurn(AgentRunEntity result) {
            finishing = false;
            turnFinished = true;
            busy = false;
            worker = null;
            completion.complete(result);
            notifyAll();
        }

        AgentRunEntity await(long timeout, TimeUnit unit) {
            try {
                return completion().get(timeout, unit);
            } catch (java.util.concurrent.TimeoutException exception) {
                return null;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return null;
            } catch (java.util.concurrent.ExecutionException exception) {
                throw new IllegalStateException("等待子 Agent 失败", exception.getCause());
            }
        }

        private synchronized CompletableFuture<AgentRunEntity> completion() {
            return completion;
        }
    }
}
