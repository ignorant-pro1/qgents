package qg.qgent.sandbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.sandbox.dto.SandboxExecutionContext;
import qg.qgent.sandbox.execution.dto.ToolExecutionRequest;
import qg.qgent.sandbox.execution.service.ToolExecutionService;
import qg.qgent.support.TestIds;

class SandboxToolServiceTest {
    @Test
    void childRunAndActualWorkspaceArePassedDirectlyToExecution() {
        Long root = TestIds.next(), child = TestIds.next(), workspace = TestIds.next();
        java.util.UUID sandbox = java.util.UUID.randomUUID();
        SandboxSessionManager sessions = mock(SandboxSessionManager.class);
        ToolExecutionService executions = mock(ToolExecutionService.class);
        when(sessions.require(workspace))
                .thenReturn(new SandboxSession(root, workspace, sandbox, "key"));
        SandboxExecutionContext.within(
                child,
                () ->
                        new SandboxToolService(sessions, executions)
                                .execute(
                                        workspace,
                                        "file.read",
                                        Map.of("path", "README.md"),
                                        Duration.ofSeconds(30)));

        var request = ArgumentCaptor.forClass(ToolExecutionRequest.class);
        verify(executions).execute(eq(sandbox), request.capture());
        assertThat(request.getValue().getAgentRunId()).isEqualTo(child);
        assertThat(request.getValue().getWorkspaceId()).isEqualTo(workspace);
        verify(sessions).renewWriteLease(workspace);
        verifyNoMoreInteractions(executions);
    }
}
