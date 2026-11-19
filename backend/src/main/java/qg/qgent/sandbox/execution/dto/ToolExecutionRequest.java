package qg.qgent.sandbox.execution.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 调用沙箱工具时提交的结构化请求。
 */
@Data
public class ToolExecutionRequest {
    // 实际调用的 AgentRun 和 Workspace；子 Agent 与根运行共用沙箱，但保留自己的执行身份。
    private Long agentRunId;
    private Long workspaceId;

    @lombok.ToString.Exclude
    private Map<String, String> environment = Map.of();

    @NotBlank
    @Size(max = 64)
    // Tool names are namespaced (for example file.read and command.run).
    // Keep validation aligned with the registered tool names; the registry remains the
    // authoritative allow-list after the request passes shape validation.
    @Pattern(regexp = "[a-z]+\\.[a-z_]+")
    private String tool;

    @NotNull
    @Size(max = 64)
    private Map<String, Object> arguments = new LinkedHashMap<>();

    @Min(1)
    @Max(3600)
    private Long timeoutSeconds;
}
