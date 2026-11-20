package qg.qgent.harness.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import qg.qgent.common.web.ApiException;
import qg.qgent.harness.config.AgentExecutorProperties;
import qg.qgent.harness.entity.RunStatus;
import qg.qgent.harness.runtime.AgentChatService;
import qg.qgent.harness.service.AgentRunCompletion;
import qg.qgent.harness.service.AgentRunService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** MySQL 保留等待中的根运行，线程池仅负责当前进程内的执行。 */
@Component
public class AgentRunDispatcher {
    private static final Logger log = LoggerFactory.getLogger(AgentRunDispatcher.class);

    private final AgentRunService runs;
    private final AgentRunCompletion completion;
    private final AgentChatService chat;
    private final Executor executor;
    private final int maxInFlight;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private volatile boolean ready;

    public AgentRunDispatcher(AgentRunService runs, AgentRunCompletion completion,
        AgentChatService chat, @Qualifier("agentExecutor") Executor executor,
        AgentExecutorProperties properties) {
        this.runs = runs;
        this.completion = completion;
        this.chat = chat;
        this.executor = executor;
        this.maxInFlight = properties.getMaxSize();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        runs.failInterruptedChildren();
        // 保留已排队的任务。执行过工具的运行可能有外部副作用，重启时只收尾。
        for (Long id : runs.interruptedRootIds()) {
            completion.complete(id, RunStatus.FAILED, "服务重启，运行已中断", 0);
        }
        ready = true;
    }

    @Scheduled(initialDelayString = "${app.agent.dispatch-interval:500ms}",
        fixedDelayString = "${app.agent.dispatch-interval:500ms}")
    public void dispatch() {
        if (!ready || inFlight.size() >= maxInFlight) return;
        for (Long id : runs.queuedRootIds(maxInFlight)) {
            if (inFlight.size() >= maxInFlight) return;
            if (!inFlight.add(id)) continue;
            try {
                executor.execute(() -> execute(id));
            } catch (RejectedExecutionException rejected) {
                inFlight.remove(id);
                // Worker 尚未领取，下一次轮询会看到同一条 QUEUED 记录。
                return;
            }
        }
    }

    private void execute(Long id) {
        try {
            var run = runs.claimRoot(id);
            if (run == null) return;
            long queueMs = Duration.between(run.getCreatedAt(), LocalDateTime.now(ZoneOffset.UTC)).toMillis();
            long started = System.nanoTime();
            chat.executeRoot(run);
            log.info("Agent run completed runId={} status={} rounds={} queueMs={} workMs={}",
                id, run.getStatus(), run.getRounds(), Math.max(0, queueMs),
                Duration.ofNanos(System.nanoTime() - started).toMillis());
        } catch (RuntimeException error) {
            String code = error instanceof ApiException api ? api.code() : error.getClass().getSimpleName();
            log.warn("Agent run execution or completion failed runId={} code={}", id, code);
            String message = error instanceof ApiException api && "AGENT_NOT_ACTIVE".equals(api.code())
                ? api.getMessage() : "Agent 执行或保存结果失败，请稍后重试";
            try {
                completion.complete(id, RunStatus.FAILED, message, 0);
            } catch (TransientDataAccessException unavailable) {
                log.error("Agent run finalization unavailable runId={} type={}",
                    id, unavailable.getClass().getSimpleName());
            }
        } finally {
            inFlight.remove(id);
        }
    }
}
