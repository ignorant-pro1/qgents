package qg.qgent.harness.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import qg.qgent.chat.event.MessageSentEvent;
import qg.qgent.chat.service.MessageService;
import qg.qgent.harness.entity.AgentRunEntity;
import qg.qgent.harness.entity.RunStatus;
import qg.qgent.harness.mapper.AgentRunMapper;
import qg.qgent.project.service.ProjectAccessService;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按触发消息停止执行，执行状态保留在内部运行记录中。
 */
@Service
@RequiredArgsConstructor
public class AgentRunService {
    private static final Logger log = LoggerFactory.getLogger(AgentRunService.class);
    private static final String ROOT_CANCELLED = "Agent 运行已取消";

    private final AgentRunMapper runs;
    private final MessageService messages;
    private final ProjectAccessService access;
    private final Map<Long, Runnable> cancellationSignals = new ConcurrentHashMap<>();

    /**
     * 服务重启后，上一进程留下的运行不可能再继续；收尾为失败，避免前端永远看到 RUNNING。
     */
    public void failInterruptedChildren() {
        int interrupted =
            runs.update(
                null,
                Wrappers.<AgentRunEntity>lambdaUpdate()
                    .set(AgentRunEntity::getStatus, RunStatus.FAILED)
                    .set(AgentRunEntity::getResult, "服务重启，运行已中断")
                    .set(AgentRunEntity::getFinishedAt, LocalDateTime.now(ZoneOffset.UTC))
                    .isNotNull(AgentRunEntity::getParentRunId)
                    .in(
                        AgentRunEntity::getStatus,
                        RunStatus.RUNNING,
                        RunStatus.CANCELLING));
        if (interrupted > 0) {
            log.warn("marked {} interrupted child runs as FAILED after restart", interrupted);
        }
    }

    public void enqueueRoot(Long agentId, MessageSentEvent event, String instruction) {
        AgentRunEntity run = new AgentRunEntity();
        run.setProjectId(event.projectId());
        run.setGroupId(event.groupId());
        run.setAgentId(agentId);
        run.setTriggerMessageId(event.messageId());
        run.setCreatedBy(event.actor());
        run.setInstruction(instruction);
        run.setStatus(RunStatus.QUEUED);
        run.setRounds(0);
        run.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        runs.insert(run);
    }

    public List<Long> queuedRootIds(int limit) {
        return runs.selectList(
                Wrappers.<AgentRunEntity>lambdaQuery()
                    .select(AgentRunEntity::getId)
                    .eq(AgentRunEntity::getStatus, RunStatus.QUEUED)
                    .isNull(AgentRunEntity::getParentRunId)
                    .orderByAsc(AgentRunEntity::getId)
                    .last("LIMIT " + limit))
            .stream().map(AgentRunEntity::getId).toList();
    }

    public List<Long> interruptedRootIds() {
        return runs.selectList(
                Wrappers.<AgentRunEntity>lambdaQuery()
                    .select(AgentRunEntity::getId)
                    .in(AgentRunEntity::getStatus, RunStatus.RUNNING, RunStatus.CANCELLING)
                    .isNull(AgentRunEntity::getParentRunId))
            .stream().map(AgentRunEntity::getId).toList();
    }

    public AgentRunEntity claimRoot(Long id) {
        if (!changeStatus(id, RunStatus.QUEUED, RunStatus.RUNNING)) return null;
        return findRun(id);
    }

    public AgentRunEntity findRun(Long id) {
        return runs.selectById(id);
    }

    private boolean changeStatus(Long id, RunStatus from, RunStatus to) {
        return runs.update(null, Wrappers.<AgentRunEntity>lambdaUpdate()
            .set(AgentRunEntity::getStatus, to)
            .eq(AgentRunEntity::getId, id)
            .eq(AgentRunEntity::getStatus, from)) > 0;
    }

    public AgentRunEntity startChild(AgentRunEntity root, String instruction, Long workspaceId) {
        AgentRunEntity run = new AgentRunEntity();
        run.setProjectId(root.getProjectId());
        run.setGroupId(root.getGroupId());
        run.setAgentId(root.getAgentId());
        run.setTriggerMessageId(root.getTriggerMessageId());
        run.setParentRunId(root.getId());
        run.setWorkspaceId(workspaceId);
        run.setCreatedBy(root.getCreatedBy());
        run.setInstruction(instruction);
        run.setStatus(RunStatus.RUNNING);
        run.setRounds(0);
        run.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        runs.insert(run);
        return run;
    }

    public void save(AgentRunEntity run) {
        runs.updateById(run);
    }

    public void finish(AgentRunEntity run, RunStatus status, String result, int rounds) {
        run.setStatus(status);
        run.setResult(result);
        run.setRounds((run.getRounds() == null ? 0 : run.getRounds()) + rounds);
        run.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
        save(run);
    }

    /**
     * 仅在运行仍为 RUNNING 时结束根运行；已开始的取消优先转为 CANCELLED。
     */
    public boolean finishRoot(AgentRunEntity run, RunStatus status, String result, int rounds) {
        int totalRounds = (run.getRounds() == null ? 0 : run.getRounds()) + rounds;
        LocalDateTime finishedAt = LocalDateTime.now(ZoneOffset.UTC);
        int updated =
            runs.update(
                null,
                Wrappers.<AgentRunEntity>lambdaUpdate()
                    .set(AgentRunEntity::getStatus, status)
                    .set(AgentRunEntity::getResult, result)
                    .set(AgentRunEntity::getRounds, totalRounds)
                    .set(AgentRunEntity::getFinishedAt, finishedAt)
                    .eq(AgentRunEntity::getId, run.getId())
                    .eq(AgentRunEntity::getStatus, RunStatus.RUNNING));
        if (updated > 0) {
            run.setStatus(status);
            run.setResult(result);
            run.setRounds(totalRounds);
            run.setFinishedAt(finishedAt);
            return true;
        }

        int cancelled =
            runs.update(
                null,
                Wrappers.<AgentRunEntity>lambdaUpdate()
                    .set(AgentRunEntity::getStatus, RunStatus.CANCELLED)
                    .set(AgentRunEntity::getResult, ROOT_CANCELLED)
                    .set(AgentRunEntity::getRounds, totalRounds)
                    .set(AgentRunEntity::getFinishedAt, finishedAt)
                    .eq(AgentRunEntity::getId, run.getId())
                    .eq(AgentRunEntity::getStatus, RunStatus.CANCELLING));
        if (cancelled > 0) {
            run.setStatus(RunStatus.CANCELLED);
            run.setResult(ROOT_CANCELLED);
            run.setRounds(totalRounds);
            run.setFinishedAt(finishedAt);
            return true;
        }

        AgentRunEntity current = runs.selectById(run.getId());
        if (current != null) {
            run.setStatus(current.getStatus());
            run.setResult(current.getResult());
            run.setRounds(current.getRounds());
            run.setFinishedAt(current.getFinishedAt());
        }
        return false;
    }

    public void resume(AgentRunEntity run, String instruction) {
        run.setStatus(RunStatus.RUNNING);
        run.setFinishedAt(null);
        if (instruction != null && !instruction.isBlank()) {
            run.setInstruction(run.getInstruction() + "\n\n继续要求：" + instruction);
        }
        save(run);
    }

    public void registerCancellationSignal(Long rootRunId, Runnable signal) {
        cancellationSignals.put(rootRunId, signal);
        if (isCancelled(rootRunId)) signal.run();
    }

    public void unregisterCancellationSignal(Long rootRunId) {
        cancellationSignals.remove(rootRunId);
    }

    public boolean isCancelled(Long rootRunId) {
        AgentRunEntity root = runs.selectById(rootRunId);
        return root != null && root.getStatus().cancelled();
    }

    public boolean requestChildCancellation(Long rootRunId, Long childRunId) {
        return runs.update(
            null,
            Wrappers.<AgentRunEntity>lambdaUpdate()
                .set(AgentRunEntity::getStatus, RunStatus.CANCELLING)
                .eq(AgentRunEntity::getId, childRunId)
                .eq(AgentRunEntity::getParentRunId, rootRunId)
                .eq(AgentRunEntity::getStatus, RunStatus.RUNNING))
            > 0;
    }

    @Transactional
    public int cancel(Long actor, Long projectId, Long groupId, Long messageId) {
        var message = messages.getMessage(actor, projectId, groupId, messageId);
        if (!actor.toString().equals(message.getSenderId()))
            access.requireProjectAdmin(projectId, actor);
        List<AgentRunEntity> activeRoots =
            runs.selectList(
                Wrappers.<AgentRunEntity>lambdaQuery()
                    .eq(AgentRunEntity::getProjectId, projectId)
                    .eq(AgentRunEntity::getGroupId, groupId)
                    .eq(AgentRunEntity::getTriggerMessageId, messageId)
                    .isNull(AgentRunEntity::getParentRunId)
                    .in(AgentRunEntity::getStatus, RunStatus.QUEUED, RunStatus.RUNNING));
        int cancelled = 0;
        for (AgentRunEntity run : activeRoots) {
            if (run.getStatus() == RunStatus.QUEUED && cancelQueued(run)) {
                cancelled++;
            } else if (changeStatus(run.getId(), RunStatus.RUNNING, RunStatus.CANCELLING)) {
                cancelled++;
                Long runId = run.getId();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        Runnable signal = cancellationSignals.get(runId);
                        if (signal != null) signal.run();
                    }
                });
            }
        }
        return cancelled;
    }

    private boolean cancelQueued(AgentRunEntity run) {
        int updated = runs.update(null, Wrappers.<AgentRunEntity>lambdaUpdate()
            .set(AgentRunEntity::getStatus, RunStatus.CANCELLED)
            .set(AgentRunEntity::getResult, ROOT_CANCELLED)
            .set(AgentRunEntity::getFinishedAt, LocalDateTime.now(ZoneOffset.UTC))
            .eq(AgentRunEntity::getId, run.getId())
            .eq(AgentRunEntity::getStatus, RunStatus.QUEUED));
        if (updated == 0) return false;
        messages.sendAgentRunReply(run.getAgentId(), run.getGroupId(), run.getTriggerMessageId(),
            run.getId(), ROOT_CANCELLED);
        return true;
    }
}
