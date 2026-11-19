package qg.qgent.sandbox.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

/**
 * 控制层申请创建沙箱时提交的请求。
 * 请求只包含资源标识和受控配置，不允许携带宿主机路径、Docker 参数或凭证。
 */
@Data
public class CreateSandboxRequest {

    // 控制层生成的沙箱编号；同编号且规格相同的创建请求可幂等重试。
    @NotNull
    private UUID sandboxId;

    // Sandbox 所属 AgentRun 编号。
    @NotNull
    private Long agentRunId;

    // Workspace 的不透明存储键，由 Sandbox 解析为受控根目录下的实际路径。
    @NotBlank
    @Size(max = 512)
    private String workspaceStorageKey;

    // Sandbox 白名单中的镜像配置名称，例如 dev-tools。
    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "[a-z0-9][a-z0-9-]*")
    private String imageProfile;

    // 可选的资源限制；Sandbox 会再次按本地上限收紧。
    @Valid
    private ResourceLimitsRequest limits;
}
