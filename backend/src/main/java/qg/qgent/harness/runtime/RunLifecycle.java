package qg.qgent.harness.runtime;

import qg.qgent.harness.entity.AgentRunEntity;
import qg.qgent.harness.entity.RunStatus;
import qg.qgent.harness.service.AgentRunService;
import qg.qgent.sandbox.service.SandboxSessionManager;

/**
 * 一次根运行及其子运行共用的取消与收尾。
 * <p>
 * 差异只看 run 是否有 parentRunId：根运行先等子运行停下，再停自己的命令，最后释放它持有的沙箱；
 * 子运行共享根运行的沙箱，只停自己的命令、写状态并通知根运行。
 */
final class RunLifecycle {
    private static final String ROOT_CANCELLED = "Agent 运行已取消";
    private static final String CHILD_CANCELLED = "子 Agent 已停止";

    private final AgentRunService runs;
    private final SandboxSessionManager sessions;
    private final WorkContext work;
    private final Thread rootWorker;

    RunLifecycle(AgentRunService runs, SandboxSessionManager sessions, WorkContext work) {
        this.runs = runs;
        this.sessions = sessions;
        this.work = work;
        this.rootWorker = Thread.currentThread();
    }

    /**
     * 取消：仍在跑的命令立即取消，工作线程被打断；根运行还先停止全部子运行。
     */
    void cancelled(AgentRunEntity run) {
        if (isRoot(run)) {
            work.cancel();
            work.children.cancelAll();
            sessions.cancelExecutionsByAgentRun(run.getId());
            if (rootWorker != Thread.currentThread()) rootWorker.interrupt();
            return;
        }
        ChildRuns.Execution child = work.children.execution(run.getId());
        if (work.children.requestStop(child))
            runs.requestChildCancellation(work.rootRunId, run.getId());
        // 每轮只需取消一次命令；续接后允许再次取消。
        if (work.children.markCommandsCancelled(run.getId()))
            sessions.cancelExecutionsByAgentRun(run.getId());
    }

    /**
     * 收尾：失败或取消时先等命令停下，再写状态。
     * 根运行进入 CANCELLING 时取消优先；子运行写状态期间被取消则改写为 CANCELLED 重写。
     */
    RunStatus finish(AgentRunEntity run, RunStatus status, String result, int rounds) {
        if (isRoot(run)) awaitChildren(status);
        boolean commandsStopped = false;
        while (true) {
            if (isCancelled(run)) {
                status = RunStatus.CANCELLED;
                result = cancelledResult(run);
            }
            if (status != RunStatus.SUCCEEDED && !commandsStopped) {
                sessions.stopExecutionsByAgentRun(run.getId());
                commandsStopped = true;
                continue;
            }
            if (!isRoot(run)) {
                runs.finish(run, status, result, rounds);
                rounds = 0;
                if (work.children.publishFinish(work.children.execution(run.getId())))
                    return status;
                status = RunStatus.CANCELLED;
                result = cancelledResult(run);
                continue;
            }
            work.openedWorkspaces.forEach(
                workspaceId -> sessions.release(workspaceId, work.rootRunId));
            return status;
        }
    }

    private void awaitChildren(RunStatus status) {
        work.children.close();
        if (status != RunStatus.SUCCEEDED || work.isCancelled()) work.children.cancelAll();
        work.children.awaitIdle();
    }

    private boolean isCancelled(AgentRunEntity run) {
        return isRoot(run) ? work.isCancelled() : work.children.cancelled(run.getId());
    }

    private String cancelledResult(AgentRunEntity run) {
        return isRoot(run) ? ROOT_CANCELLED : CHILD_CANCELLED;
    }

    private static boolean isRoot(AgentRunEntity run) {
        return run.getParentRunId() == null;
    }
}
