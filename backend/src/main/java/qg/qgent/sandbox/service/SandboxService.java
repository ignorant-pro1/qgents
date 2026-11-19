package qg.qgent.sandbox.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.sandbox.config.SandboxProperties;
import qg.qgent.sandbox.dto.CreateSandboxRequest;
import qg.qgent.sandbox.dto.ResourceLimitsRequest;
import qg.qgent.sandbox.dto.SandboxResponse;
import qg.qgent.sandbox.exception.SandboxException;
import qg.qgent.sandbox.runtime.ContainerRuntime;
import qg.qgent.sandbox.runtime.SandboxAllocation;
import qg.qgent.workspace.service.WorkspaceOperationLock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 管理沙箱状态、租约和底层容器生命周期。
 * 资源请求始终受到 Sandbox 本地配置约束，控制层不能通过请求放宽上限。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxService {
    private final ContainerRuntime runtime;
    private final SandboxProperties properties;
    private final Clock clock;
    private final WorkspaceOperationLock workspaceLock;

    /**
     * 创建沙箱并计算实际租约。
     * 相同 Sandbox 编号的重复创建请求：若所属根运行与 Workspace 规格一致则幂等返回已有沙箱，
     * 规格不同返回冲突，避免初始化重试因请求超时造成重复创建。
     */
    public SandboxResponse create(CreateSandboxRequest request) {
        // 仅允许使用 Sandbox 配置中的镜像。
        if (!properties.getImages().containsKey(request.getImageProfile())) {
            throw new SandboxException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "IMAGE_PROFILE_NOT_ALLOWED",
                "镜像配置不在 Sandbox 允许列表中");
        }

        return workspaceLock.execute(
            request.getWorkspaceStorageKey(),
            () -> {
                SandboxAllocation existing = runtime.find(request.getSandboxId()).orElse(null);
                if (existing != null) {
                    SandboxAllocation requested = allocation(request);
                    if (sameSandboxSpec(existing, requested)) {
                        log.info(
                            "sandbox idempotent return sandboxId={} agentRunId={} workspace={}",
                            existing.getId(),
                            existing.getAgentRunId(),
                            existing.getWorkspaceStorageKey());
                        return response(existing);
                    }
                    throw new SandboxException(
                        HttpStatus.CONFLICT, "SANDBOX_ID_CONFLICT", "同名沙箱规格不一致");
                }
                SandboxAllocation allocation = allocation(request);
                SandboxAllocation created = runtime.create(request, allocation);
                log.info(
                    "sandbox created sandboxId={} agentRunId={} workspace={} image={} container={}",
                    created.getId(),
                    created.getAgentRunId(),
                    created.getWorkspaceStorageKey(),
                    created.getImageProfile(),
                    created.getRuntimeHandle());
                return response(created);
            });
    }

    private SandboxAllocation allocation(CreateSandboxRequest request) {
        Instant now = clock.instant();
        Duration idleTtl = requestedIdleTtl(request.getLimits());
        Duration maxLifetime = requestedMaxLifetime(request.getLimits());
        Duration executionTimeout = requestedExecutionTimeout(request.getLimits());
        return new SandboxAllocation(
            request.getSandboxId(),
            request.getAgentRunId(),
            request.getWorkspaceStorageKey(),
            request.getImageProfile(),
            "READY",
            properties.getRuntime().toUpperCase(),
            now,
            now,
            now.plus(idleTtl),
            now.plus(maxLifetime),
            executionTimeout,
            null);
    }

    /**
     * 判断两个沙箱规格是否一致：所属根运行、Workspace 存储键和镜像配置全部相等才算一致。
     */
    private boolean sameSandboxSpec(SandboxAllocation existing, SandboxAllocation requested) {
        return java.util.Objects.equals(existing.getAgentRunId(), requested.getAgentRunId())
            && java.util.Objects.equals(
            existing.getWorkspaceStorageKey(), requested.getWorkspaceStorageKey())
            && java.util.Objects.equals(
            existing.getImageProfile(), requested.getImageProfile());
    }

    /**
     * 查询沙箱；不存在时返回空结果。
     */
    public Optional<SandboxResponse> find(UUID sandboxId) {
        return runtime.find(sandboxId).map(this::response);
    }

    /**
     * 返回仍由 Sandbox 管理的沙箱。
     * 结构化工具当前允许并发执行，因此这里只校验资源存在，不检查 READY 状态。
     */
    public SandboxAllocation findAllocation(UUID sandboxId) {
        return require(sandboxId);
    }

    /**
     * 延长空闲租约，但绝不突破最大生命周期。
     */
    public SandboxResponse renew(UUID sandboxId, Long requestedSeconds) {
        SandboxAllocation allocation = require(sandboxId);
        Duration requested =
            requestedSeconds == null
                ? properties.getDefaultIdleTtl()
                : Duration.ofSeconds(requestedSeconds);
        Duration ttl = min(requested, properties.getMaxIdleTtl());
        Instant now = clock.instant();

        allocation.setLastActiveAt(now);
        allocation.setExpiresAt(min(now.plus(ttl), allocation.getMaxExpiresAt()));
        log.debug(
            "sandbox renewed sandboxId={} expiresAt={} requestedSeconds={}",
            sandboxId,
            allocation.getExpiresAt(),
            requestedSeconds);
        return response(allocation);
    }

    /**
     * 销毁 Sandbox 容器，不删除 Workspace。
     */
    public void destroy(UUID sandboxId) {
        runtime.destroy(sandboxId);
        log.info("sandbox destroyed sandboxId={}", sandboxId);
    }

    /**
     * 判断指定持久 Workspace 是否仍被任一受管 Sandbox 引用。
     */
    public boolean isWorkspaceInUse(String workspaceStorageKey) {
        return runtime.isWorkspaceInUse(workspaceStorageKey);
    }

    /**
     * 返回已经超过空闲期限或最大生命周期的沙箱编号。
     */
    public List<UUID> expiredSandboxIds() {
        Instant now = clock.instant();
        return runtime.findAll().stream()
            .filter(item ->
                !now.isBefore(item.getExpiresAt()) || !now.isBefore(item.getMaxExpiresAt()))
            .map(SandboxAllocation::getId)
            .toList();
    }

    private SandboxAllocation require(UUID sandboxId) {
        return runtime.find(sandboxId)
            .orElseThrow(() ->
                new SandboxException(HttpStatus.NOT_FOUND, "SANDBOX_NOT_FOUND", "沙箱不存在"));
    }

    private Duration requestedIdleTtl(ResourceLimitsRequest limits) {
        Long seconds = limits == null ? null : limits.getIdleTtlSeconds();
        Duration requested =
            seconds == null ? properties.getDefaultIdleTtl() : Duration.ofSeconds(seconds);
        return min(requested, properties.getMaxIdleTtl());
    }

    private Duration requestedMaxLifetime(ResourceLimitsRequest limits) {
        Long seconds = limits == null ? null : limits.getMaxLifetimeSeconds();
        Duration requested =
            seconds == null ? properties.getDefaultMaxLifetime() : Duration.ofSeconds(seconds);
        return min(requested, properties.getMaxLifetime());
    }

    private Duration requestedExecutionTimeout(ResourceLimitsRequest limits) {
        Long seconds = limits == null ? null : limits.getExecutionTimeoutSeconds();
        Duration requested =
            seconds == null
                ? properties.getDefaultExecutionTimeout()
                : Duration.ofSeconds(seconds);
        return min(requested, properties.getMaxExecutionTimeout());
    }

    private Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private Instant min(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private SandboxResponse response(SandboxAllocation allocation) {
        return new SandboxResponse(
            allocation.getId(),
            allocation.getAgentRunId(),
            allocation.getWorkspaceStorageKey(),
            allocation.getImageProfile(),
            allocation.getStatus(),
            allocation.getRuntimeKind(),
            allocation.getCreatedAt(),
            allocation.getLastActiveAt(),
            allocation.getExpiresAt(),
            allocation.getMaxExpiresAt());
    }
}
