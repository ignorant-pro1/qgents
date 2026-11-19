package qg.qgent.sandbox.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import qg.qgent.sandbox.dto.CreateSandboxRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 模拟沙箱生命周期，供不使用 Docker 的本地联调和测试使用。
 */
@Component
@ConditionalOnProperty(name = "sandbox.runtime", havingValue = "fake", matchIfMissing = true)
public class FakeContainerRuntime implements ContainerRuntime {
    private final ConcurrentMap<UUID, SandboxAllocation> allocations = new ConcurrentHashMap<>();

    @Override
    public SandboxAllocation create(CreateSandboxRequest request, SandboxAllocation allocation) {
        SandboxAllocation existing = allocations.putIfAbsent(request.getSandboxId(), allocation);
        if (existing != null) {
            throw new IllegalStateException("沙箱编号已经存在");
        }
        return allocation;
    }

    @Override
    public Optional<SandboxAllocation> find(UUID sandboxId) {
        return Optional.ofNullable(allocations.get(sandboxId));
    }

    @Override
    public List<SandboxAllocation> findAll() {
        return List.copyOf(allocations.values());
    }

    @Override
    public boolean isWorkspaceInUse(String workspaceStorageKey) {
        return allocations.values().stream()
            .anyMatch(item -> item.getWorkspaceStorageKey().equals(workspaceStorageKey));
    }

    @Override
    public void destroy(UUID sandboxId) {
        allocations.remove(sandboxId);
    }
}
