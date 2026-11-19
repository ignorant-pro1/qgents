package qg.qgent.workspace.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.common.web.ApiException;
import qg.qgent.workspace.mapper.WorkspaceMapper;

/**
 * Sandbox 工作区的写入租约：领取、续租和释放均通过数据库条件更新完成。
 */
@Service
@RequiredArgsConstructor
public class WorkspaceWriteLeaseService {
    private final WorkspaceMapper workspaces;

    /**
     * 领取一把新的写入租约。若另一个 AgentRun 正在写入，返回稳定冲突码而不修改对方状态。
     */
    public WorkspaceWriteLease acquire(Long projectId, Long workspaceId, Long agentRunId) {
        String token = java.util.UUID.randomUUID().toString();
        if (workspaces.claimWriteLease(projectId, workspaceId, agentRunId, token) != 1) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "WORKSPACE_WRITE_LEASE_HELD",
                "Workspace is currently being modified by another AgentRun");
        }
        return new WorkspaceWriteLease(projectId, workspaceId, agentRunId, token);
    }

    /**
     * 在实际 Sandbox 写入前续租。租约已丢失时立即阻断外部写入，不能继续使用过期执行现场。
     */
    public void renew(WorkspaceWriteLease lease) {
        if (workspaces.renewWriteLease(
            lease.getProjectId(),
            lease.getWorkspaceId(),
            lease.getAgentRunId(),
            lease.token())
            != 1) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "WORKSPACE_WRITE_LEASE_LOST",
                "Workspace write lease is no longer active");
        }
    }

    /**
     * 释放当前租约。令牌已失效说明已有恢复执行器接管，保持其状态即可。
     */
    public void release(WorkspaceWriteLease lease) {
        if (lease == null) {
            return;
        }
        workspaces.releaseWriteLease(
            lease.getProjectId(), lease.getWorkspaceId(), lease.getAgentRunId(), lease.token());
    }
}
