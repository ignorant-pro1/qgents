package qg.qgent.sandbox.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * 沙箱的内部服务响应，不包含宿主机路径或凭证。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SandboxResponse {
    private UUID id;
    private Long agentRunId;
    // Sandbox 使用的持久 Workspace 存储标识。
    private String workspaceStorageKey;
    // Sandbox 使用的 Sandbox 镜像配置名称。
    private String imageProfile;
    // 沙箱生命周期状态，例如 READY 或 BUSY。
    private String status;
    private String runtimeKind;
    private Instant createdAt;
    private Instant lastActiveAt;
    private Instant expiresAt;
    private Instant maxExpiresAt;
}
