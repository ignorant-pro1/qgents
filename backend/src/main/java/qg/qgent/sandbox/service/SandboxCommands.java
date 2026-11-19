package qg.qgent.sandbox.service;

import org.springframework.stereotype.Service;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.sandbox.execution.dto.ToolExecutionResponse;
import qg.qgent.sandbox.execution.entity.ExecutionStatus;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 短等待返回执行句柄；后续按句柄读取或取消，内部 Git 操作仍等待终态。
 */
@Service
public class SandboxCommands {
    private final SandboxToolService tools;

    public SandboxCommands(SandboxToolService tools) {
        this.tools = tools;
    }

    /**
     * 内部等待型调用只需要退出码和 stdout，不构造模型可见的 Map。
     */
    public record CommandResult(int exitCode, String stdout) {
    }

    /**
     * 在 Workspace 根目录同步执行一条命令，返回终态退出码与 stdout 尾部。
     */
    public CommandResult git(Long id, List<String> command, Map<String, String> environment) {
        ToolExecutionResponse execution = await(id, ".", command, environment);
        Object stdout = execution.getResult().get("stdout");
        String text = stdout instanceof Map<?, ?> value ? String.valueOf(value.get("text")) : "";
        return new CommandResult(
            execution.getExitCode() == null ? -1 : execution.getExitCode(), text);
    }

    public ToolResult run(
        Long id, String directory, List<String> command, Map<String, String> environment) {
        return result(await(id, directory, command, environment));
    }

    public ToolResult start(
        Long id, String directory, List<String> command, int timeoutSeconds, int waitSeconds) {
        var execution =
            tools.start(
                id,
                arguments(directory, command),
                Duration.ofSeconds(timeoutSeconds),
                Map.of());
        return result(tools.await(execution.getId(), Duration.ofSeconds(waitSeconds)));
    }

    public ToolResult output(
        Long id,
        Long executionId,
        long stdoutOffset,
        long stderrOffset,
        int count,
        int waitSeconds) {
        tools.requireCommand(id, executionId);
        var execution = tools.await(executionId, Duration.ofSeconds(waitSeconds));
        ToolResult value = result(execution);
        value.putAll(tools.output(executionId, stdoutOffset, stderrOffset, count));
        return value;
    }

    public ToolResult cancel(Long id, Long executionId) {
        var execution = tools.requireCommand(id, executionId);
        return result(execution.getStatus().terminal() ? execution : tools.cancel(executionId));
    }

    private ToolExecutionResponse await(
        Long id, String directory, List<String> command, Map<String, String> environment) {
        var execution =
            tools.start(id, arguments(directory, command), Duration.ofMinutes(10), environment);
        execution = tools.await(execution.getId(), Duration.ofMinutes(15));
        if (!execution.getStatus().terminal()) throw new IllegalStateException("命令等待超时");
        return execution;
    }

    private static Map<String, Object> arguments(String directory, List<String> command) {
        if (command == null || command.isEmpty()) throw new IllegalArgumentException("命令不能为空");
        return Map.of("directory", directory == null ? "." : directory, "command", command);
    }

    private static ToolResult result(ToolExecutionResponse execution) {
        boolean terminal = execution.getStatus().terminal();
        boolean failed = terminal && execution.getStatus() != ExecutionStatus.SUCCEEDED;
        ToolResult value =
            failed
                ? ToolResult.error(
                execution.getFailureCode() == null
                ? "COMMAND_FAILED"
                : execution.getFailureCode(),
                execution.getFailureReason() == null
                ? "命令执行失败"
                : execution.getFailureReason())
                : ToolResult.ok();
        value.putAll(execution.getResult());
        value.put("executionId", execution.getId().toString());
        value.put("status", execution.getStatus().name());
        value.put("running", !terminal);
        if (execution.getExitCode() != null) value.put("exitCode", execution.getExitCode());
        return value;
    }
}
