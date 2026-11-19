package qg.qgent.sandbox.execution.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import qg.qgent.sandbox.runtime.SandboxAllocation;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 工具执行时使用的受控上下文。
 */
@Data
@AllArgsConstructor
public class ToolContext {
    private SandboxAllocation sandbox;
    private Path localWorkspace;
    private String containerWorkspace;
    private Duration timeout;
    private Long executionId;
    @lombok.Setter
    private java.util.Map<String, String> environment = java.util.Map.of();

    public ToolContext(
        SandboxAllocation sandbox,
        Path localWorkspace,
        String containerWorkspace,
        Duration timeout) {
        this.sandbox = sandbox;
        this.localWorkspace = localWorkspace;
        this.containerWorkspace = containerWorkspace;
        this.timeout = timeout;
    }
}
