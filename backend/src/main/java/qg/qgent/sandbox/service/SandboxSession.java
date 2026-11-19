package qg.qgent.sandbox.service;

import java.util.UUID;

public record SandboxSession(
    Long agentRunId, Long workspaceId, UUID sandboxId, String storageKey) {
}
