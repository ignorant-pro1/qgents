package qg.qgent.sandbox.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import qg.qgent.sandbox.dto.SandboxExecutionContext;
import qg.qgent.sandbox.execution.dto.ToolExecutionRequest;
import qg.qgent.sandbox.execution.dto.ToolExecutionResponse;
import qg.qgent.sandbox.execution.service.ToolExecutionService;

import java.time.Duration;
import java.util.Map;

/**
 * 为 Workspace 工具绑定当前沙箱、执行身份和写租约，直接调用执行服务。
 */
@Service
@RequiredArgsConstructor
public class SandboxToolService {
    private final SandboxSessionManager sessions;
    private final ToolExecutionService executions;

    public ToolExecutionResponse execute(
        Long workspaceId, String tool, Map<String, Object> arguments, Duration timeout) {
        return executions.execute(
            sessions.require(workspaceId).sandboxId(),
            request(workspaceId, tool, arguments, timeout, Map.of()));
    }

    public ToolExecutionResponse start(
        Long workspaceId,
        Map<String, Object> arguments,
        Duration timeout,
        Map<String, String> environment) {
        return executions.submit(
            sessions.require(workspaceId).sandboxId(),
            request(workspaceId, "command.run", arguments, timeout, environment));
    }

    public ToolExecutionResponse await(Long executionId, Duration wait) {
        return executions.await(executionId, wait);
    }

    public ToolExecutionResponse requireCommand(Long workspaceId, Long executionId) {
        sessions.renewWriteLease(workspaceId);
        ToolExecutionResponse execution = executions.find(executionId);
        if (!sessions.require(workspaceId).sandboxId().equals(execution.getSandboxId())
            || !"command.run".equals(execution.getTool())) {
            throw new IllegalArgumentException("执行句柄不属于当前工作区的命令");
        }
        return execution;
    }

    public Map<String, Object> output(
        Long executionId, long stdoutOffset, long stderrOffset, int count) {
        return executions.output(executionId, stdoutOffset, stderrOffset, count);
    }

    public ToolExecutionResponse cancel(Long executionId) {
        return executions.cancel(executionId);
    }

    private ToolExecutionRequest request(
        Long workspaceId,
        String tool,
        Map<String, Object> arguments,
        Duration timeout,
        Map<String, String> environment) {
        sessions.renewWriteLease(workspaceId);
        ToolExecutionRequest request = new ToolExecutionRequest();
        request.setAgentRunId(SandboxExecutionContext.currentAgentRunId());
        request.setWorkspaceId(workspaceId);
        request.setTool(tool);
        request.setArguments(arguments);
        request.setEnvironment(environment);
        request.setTimeoutSeconds(timeout == null ? null : Math.max(1, timeout.toSeconds()));
        return request;
    }
}
