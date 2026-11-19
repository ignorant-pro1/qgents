package qg.qgent.sandbox.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qg.qgent.sandbox.execution.dto.ToolExecutionResponse;
import qg.qgent.sandbox.execution.entity.ExecutionStatus;
import qg.qgent.sandbox.execution.service.ToolExecutionService;
import qg.qgent.support.TestIds;

class SandboxCommandsTest {
    private final Long id = TestIds.next(), execution = TestIds.next();
    private final java.util.UUID sandbox = java.util.UUID.randomUUID();
    private final ToolExecutionService executions = mock(ToolExecutionService.class);
    private final SandboxSessionManager sessions = mock(SandboxSessionManager.class);
    private final SandboxCommands commands =
            new SandboxCommands(new SandboxToolService(sessions, executions));

    private ToolExecutionResponse execution(ExecutionStatus status, Integer exitCode) {
        when(sessions.require(id))
                .thenReturn(new SandboxSession(TestIds.next(), id, sandbox, "key"));
        var value = new ToolExecutionResponse();
        value.setId(execution);
        value.setSandboxId(sandbox);
        value.setTool("command.run");
        value.setStatus(status);
        value.setExitCode(exitCode);
        value.setResult(Map.of());
        when(executions.submit(eq(sandbox), any())).thenReturn(value);
        when(executions.find(execution)).thenReturn(value);
        when(executions.await(eq(execution), any())).thenReturn(value);
        return value;
    }

    @Test
    void immediateWaitReturnsRunningHandleWithoutStartingAgain() {
        execution(ExecutionStatus.RUNNING, null);
        assertThat(commands.start(id, ".", List.of("npm", "test"), 600, 0))
                .containsEntry("running", true)
                .containsEntry("executionId", Long.toString(execution));
        verify(executions, times(1)).submit(any(), any());
    }

    @Test
    void nonzeroExitIsReturnedAsCompletedCommand() {
        execution(ExecutionStatus.SUCCEEDED, 5);
        assertThat(commands.start(id, ".", List.of("npm", "test"), 600, 0))
                .containsEntry("ok", true)
                .containsEntry("exitCode", 5)
                .containsEntry("running", false);
    }

    @Test
    void outputUsesByteCursorsAndRenewsEnvironment() {
        execution(ExecutionStatus.RUNNING, null);
        when(executions.output(execution, 30, 40, 16000))
                .thenReturn(Map.of("stdout", Map.of("text", "next", "nextOffset", 34L)));
        assertThat(commands.output(id, execution, 30, 40, 16000, 0))
                .containsEntry("running", true)
                .containsKey("stdout");
        verify(executions).output(execution, 30, 40, 16000);
        verify(sessions).renewWriteLease(id);
        verify(executions, never()).submit(any(), any());
    }

    @Test
    void foreignExecutionCannotBeReadOrCancelled() {
        var value = execution(ExecutionStatus.RUNNING, null);
        value.setSandboxId(java.util.UUID.randomUUID());
        assertThatThrownBy(() -> commands.output(id, execution, 0, 0, 16000, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> commands.cancel(id, execution))
                .isInstanceOf(IllegalArgumentException.class);
        verify(executions, never()).cancel(any());
        verify(executions, never()).output(any(), anyLong(), anyLong(), anyInt());
    }

    @Test
    void runForwardsTheRequestedDirectory() {
        execution(ExecutionStatus.SUCCEEDED, 0);
        commands.run(id, "backend", List.of("git", "push"), Map.of());
        verify(executions)
                .submit(
                        eq(sandbox),
                        argThat(request ->
                                "backend".equals(request.getArguments().get("directory"))));
    }

    @Test
    void gitRunsFromWorkspaceRoot() {
        execution(ExecutionStatus.SUCCEEDED, 0);
        assertThat(commands.git(id, List.of("git", "fetch"), Map.of()).exitCode()).isZero();
        verify(executions)
                .submit(
                        eq(sandbox),
                        argThat(request -> ".".equals(request.getArguments().get("directory"))));
    }

    @Test
    void cancelActiveCommandAndRepeatedCancelTerminalCommand() {
        execution(ExecutionStatus.RUNNING, null);
        var cancelled = new ToolExecutionResponse();
        cancelled.setId(execution);
        cancelled.setStatus(ExecutionStatus.CANCELLED);
        cancelled.setResult(Map.of());
        when(executions.cancel(execution)).thenReturn(cancelled);
        assertThat(commands.cancel(id, execution))
                .containsEntry("status", "CANCELLED")
                .containsEntry("running", false);
        execution(ExecutionStatus.SUCCEEDED, 0);
        assertThat(commands.cancel(id, execution)).containsEntry("exitCode", 0);
        verify(executions, times(1)).cancel(execution);
    }
}
