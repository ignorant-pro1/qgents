package qg.qgent.harness.runtime;

import qg.qgent.harness.service.AgentRunService;
import qg.qgent.sandbox.service.SandboxSessionManager;
import qg.qgent.workspace.service.WorkSession;
import qg.qgent.workspace.service.WorkspaceService;

/**
 * 一次 Agent 对话的工作区会话：选择保存在对话上，沙箱会话由根运行持有、跨子 Agent 复用。
 * <p>
 * 首次调用代码工具时才建立目录与沙箱；运行取消时不再新建环境。
 */
final class WorkspaceSession implements WorkSession {
    private final WorkContext work;
    private final ChildRuns.Execution execution;
    private final WorkspaceService workspaces;
    private final SandboxSessionManager sessions;
    private final AgentRunService runs;

    WorkspaceSession(
        WorkContext work,
        ChildRuns.Execution execution,
        WorkspaceService workspaces,
        SandboxSessionManager sessions,
        AgentRunService runs) {
        this.work = work;
        this.execution = execution;
        this.workspaces = workspaces;
        this.sessions = sessions;
        this.runs = runs;
    }

    @Override
    public Long workspaceId() {
        return execution.workspace.workspaceId();
    }

    @Override
    public String directory() {
        return execution.workspace.directory();
    }

    @Override
    public Long ensure() {
        return ensure(null);
    }

    @Override
    public Long ensure(Long workspaceId) {
        synchronized (work.environmentLock) {
            if (work.isCancelled() || execution.isStopRequested())
                throw new IllegalStateException("Agent 运行已取消");
            if (workspaceId == null) {
                workspaceId = execution.workspace.workspaceId();
            }
            if (workspaceId == null) {
                if (work.defaultWorkspaceId == null) {
                    work.defaultWorkspaceId =
                        workspaces.defaultWorkspace(work.event.projectId()).getId();
                }
                workspaceId = work.defaultWorkspaceId;
                execution.workspace.bindDefault(workspaceId);
            }
            workspaces.require(work.event.projectId(), workspaceId);
            if (!workspaceId.equals(execution.run.getWorkspaceId())) {
                execution.run.setWorkspaceId(workspaceId);
                runs.save(execution.run);
            }
            if (work.openedWorkspaces.add(workspaceId)) {
                try {
                    sessions.acquire(work.rootRunId, work.event.projectId(), workspaceId);
                } catch (RuntimeException exception) {
                    work.openedWorkspaces.remove(workspaceId);
                    throw exception;
                }
            }
            return workspaceId;
        }
    }

    @Override
    public Long requireInitialized() {
        Long workspace = execution.workspace.workspaceId();
        if (workspace == null || !work.openedWorkspaces.contains(workspace)) {
            throw new IllegalArgumentException("请先通过代码工具或 run_command 初始化当前 Workspace");
        }
        return ensure();
    }

    @Override
    public void select(Long workspaceId) {
        execution.workspace.select(workspaceId);
        execution.run.setWorkspaceId(workspaceId);
        runs.save(execution.run);
    }

    @Override
    public void setDirectory(String directory) {
        execution.workspace.setDirectory(directory);
    }
}
