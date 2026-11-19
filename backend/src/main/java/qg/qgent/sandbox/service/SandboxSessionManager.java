package qg.qgent.sandbox.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.sandbox.config.SandboxProperties;
import qg.qgent.sandbox.dto.CreateSandboxRequest;
import qg.qgent.sandbox.dto.SandboxResponse;
import qg.qgent.sandbox.execution.service.ToolExecutionService;
import qg.qgent.workspace.entity.WorkspaceEntity;
import qg.qgent.workspace.mapper.WorkspaceMapper;
import qg.qgent.workspace.service.WorkspaceService;
import qg.qgent.workspace.service.WorkspaceWriteLease;
import qg.qgent.workspace.service.WorkspaceWriteLeaseService;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 首次代码工具调用时准备 Workspace 和沙箱；会话按工作区复用，由根 AgentRun 持有。
 */
@Slf4j
@Service
public class SandboxSessionManager {

    private final SandboxService sandboxes;
    private final ProjectDirectoryService projectDirectories;
    private final ToolExecutionService executions;
    private final SandboxProperties properties;
    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceWriteLeaseService writeLeases;
    private final Map<Long, SandboxSession> sessions = new ConcurrentHashMap<>();
    private final Map<Long, ReentrantLock> acquireLocks = new ConcurrentHashMap<>();
    private final Map<Long, WorkspaceWriteLease> workspaceLeases = new ConcurrentHashMap<>();

    public SandboxSessionManager(
        SandboxService sandboxes,
        ProjectDirectoryService projectDirectories,
        ToolExecutionService executions,
        SandboxProperties properties,
        WorkspaceMapper workspaceMapper,
        WorkspaceWriteLeaseService writeLeases) {
        this.sandboxes = sandboxes;
        this.projectDirectories = projectDirectories;
        this.executions = executions;
        this.properties = properties;
        this.workspaceMapper = workspaceMapper;
        this.writeLeases = writeLeases;
    }

    /**
     * 为根 AgentRun 准备指定工作区的沙箱会话；同一持有者复用已有会话。
     */
    public SandboxSession acquire(Long agentRunId, Long projectId, Long workspaceId) {
        ReentrantLock acquireLock =
            acquireLocks.computeIfAbsent(workspaceId, ignored -> new ReentrantLock());
        // 初始化或释放已由其他线程处理时返回冲突，避免等待 沙箱初始化。
        if (!acquireLock.tryLock()) {
            throw new ApiException(
                org.springframework.http.HttpStatus.CONFLICT,
                "WORKSPACE_WRITE_LEASE_HELD",
                "Workspace is currently being initialized by another AgentRun");
        }
        try {
            SandboxSession existing = sessions.get(workspaceId);
            if (existing != null) {

                if (!existing.agentRunId().equals(agentRunId)) {
                    throw new qg.qgent.common.web.ApiException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "WORKSPACE_WRITE_LEASE_HELD",
                        "Workspace is currently being modified by another AgentRun");
                }
                return existing;
            }
            WorkspaceWriteLease lease = writeLeases.acquire(projectId, workspaceId, agentRunId);
            try {
                SandboxSession created = doAcquire(agentRunId, projectId, workspaceId);
                sessions.put(workspaceId, created);
                workspaceLeases.put(workspaceId, lease);
                return created;
            } catch (RuntimeException failure) {
                writeLeases.release(lease);
                throw failure;
            }
        } finally {
            acquireLock.unlock();
        }
    }

    /**
     * 返回指定 Workspace 的当前会话；不存在时抛错，供 Sandbox 工具在调用工具前断言。
     */
    public SandboxSession require(Long workspaceId) {
        SandboxSession session = sessions.get(workspaceId);
        if (session == null) {
            throw new IllegalStateException("no sandbox session for workspace " + workspaceId);
        }
        return session;
    }

    /**
     * Stops only commands recorded against the given AgentRun and waits for their worker bodies to exit.
     */
    public void stopExecutionsByAgentRun(Long agentRunId) {
        if (agentRunId != null) executions.stopAndAwaitByAgentRun(agentRunId);
    }

    /**
     * Requests cancellation for one AgentRun without blocking its caller on command completion.
     */
    public void cancelExecutionsByAgentRun(Long agentRunId) {
        if (agentRunId != null) executions.cancelByAgentRun(agentRunId);
    }

    /**
     * 销毁会话对应的 Sandbox 并移除记录；Workspace 保留。销毁失败交给 Sandbox 后续清理，不阻断执行收尾。
     */
    public void release(Long workspaceId, Long agentRunId) {
        if (workspaceId == null || agentRunId == null) {
            return;
        }
        ReentrantLock acquireLock =
            acquireLocks.computeIfAbsent(workspaceId, ignored -> new ReentrantLock());
        // acquire() 失败后编排器仍会进入 finally；释放路径不能再次阻塞在同一把卡死的锁上。
        // 持锁者完成后会负责自己的会话/租约清理，当前 AgentRun 没有可安全移除的会话时直接返回。
        if (!acquireLock.tryLock()) {
            return;
        }
        try {
            SandboxSession session = sessions.get(workspaceId);
            if (session == null || !agentRunId.equals(session.agentRunId())) {
                // acquire 可能因其他 AgentRun 已持有会话而失败；只能清理自己的会话。
                return;
            }
            WorkspaceWriteLease lease = workspaceLeases.get(workspaceId);
            if (lease != null && !agentRunId.equals(lease.getAgentRunId())) {
                // 缓存不一致时宁可保留可能有效的租约，也不能释放其他 AgentRun 的租约。
                return;
            }
            if (!sessions.remove(workspaceId, session)) {
                return;
            }
            if (lease != null) {
                workspaceLeases.remove(workspaceId, lease);
            }
            try {
                executions.stopAndAwaitBySandbox(session.sandboxId());
                sandboxes.destroy(session.sandboxId());
            } catch (RuntimeException ignored) {
                // 销毁失败由 Sandbox 的清理任务兜底，不阻断执行结果返回。
            } finally {
                writeLeases.release(lease);
            }
        } finally {
            acquireLock.unlock();
        }
    }

    /**
     * 每次 Sandbox 工具提交前验证并续租持久化写入权。租约已丢失时不能再向同一 Workspace
     * 发起命令、文件写入或测试，避免过期 AgentRun 与后来者并发修改文件。
     */
    void renewWriteLease(Long workspaceId) {
        WorkspaceWriteLease lease = workspaceLeases.get(workspaceId);
        SandboxSession session = sessions.get(workspaceId);
        if (lease == null
            || session == null
            || !session.agentRunId().equals(lease.getAgentRunId())) {
            throw new qg.qgent.common.web.ApiException(
                org.springframework.http.HttpStatus.CONFLICT,
                "WORKSPACE_WRITE_LEASE_LOST",
                "Workspace write lease is no longer active");
        }

        writeLeases.renew(lease);
    }

    /**
     * 在整个 AgentRun 会话存活期间维持 Sandbox 租约，覆盖 LLM 推理等没有工具调用的空档。
     * 单个 Sandbox 的续租失败不影响其他会话；实际工具调用会继续得到 Sandbox 的明确错误。
     */
    @Scheduled(fixedDelayString = "${sandbox.lease-renew-interval:10s}")
    void renewActiveLeases() {
        sessions.forEach((workspaceId, session) -> {
            if (sessions.get(workspaceId) != session) {
                return;
            }
            try {
                renewWriteLease(workspaceId);
                sandboxes.renew(session.sandboxId(), null);
            } catch (RuntimeException exception) {
                // 当前工具调用会传播 Sandbox 错误；心跳不应阻塞其他活跃 Sandbox 的续租。
                log.debug(
                    "sandbox lease renew failed workspaceId={}: {}",
                    workspaceId,
                    exception.getMessage());
            }
        });
    }

    private SandboxSession doAcquire(Long agentRunId, Long projectId, Long workspaceId) {
        WorkspaceEntity workspace = workspaceMapper.selectById(workspaceId);
        if (workspace == null || !projectId.equals(workspace.getProjectId()))
            throw new IllegalArgumentException("工作区不存在");
        // 目录按需建立；同一项目同一仓库的 Git 数据在首次准备 worktree 时创建。
        projectDirectories.workspace(projectId, WorkspaceService.directoryName(workspace));
        String storageKey = WorkspaceService.storageKey(workspace);
        CreateSandboxRequest request = new CreateSandboxRequest();
        request.setSandboxId(UUID.randomUUID());
        request.setAgentRunId(agentRunId);
        request.setWorkspaceStorageKey(storageKey);
        request.setImageProfile(properties.getImageProfile());
        SandboxResponse sandbox = sandboxes.create(request);
        workspace.setStatus("READY");
        workspaceMapper.updateById(workspace);
        return new SandboxSession(agentRunId, workspaceId, sandbox.getId(), storageKey);
    }
}
