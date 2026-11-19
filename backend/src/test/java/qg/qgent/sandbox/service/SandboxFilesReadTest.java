package qg.qgent.sandbox.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qg.qgent.sandbox.execution.dto.ToolExecutionResponse;
import qg.qgent.sandbox.execution.entity.ExecutionStatus;
import qg.qgent.support.TestIds;

class SandboxFilesReadTest {
    private final Long id = TestIds.next(), executionId = TestIds.next();
    private final SandboxToolService tools = mock(SandboxToolService.class);
    private final SandboxFiles access = new SandboxFiles(tools);

    private void execution(ExecutionStatus status, Map<String, Object> result) {
        var execution = new ToolExecutionResponse();
        execution.setId(executionId);
        execution.setStatus(status);
        execution.setResult(result);
        execution.setFailureCode("PROCESS_EXIT_NONZERO");
        execution.setFailureReason("invalid regex");
        when(tools.execute(any(), anyString(), anyMap(), any())).thenReturn(execution);
    }

    @Test
    void preservesRequestedWindowAndDoesNotFetchRemainingPages() {
        execution(
                ExecutionStatus.SUCCEEDED,
                Map.of(
                        "lines",
                        List.of(Map.of("number", 301, "text", "code")),
                        "truncated",
                        true,
                        "nextLine",
                        302));
        assertThat(access.readFile(id, "x.java", 301, 1))
                .containsEntry("nextLine", 302)
                .containsEntry("truncated", true);
        var request = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(tools).execute(eq(id), anyString(), request.capture(), any());
        assertThat(request.getValue())
                .containsEntry("startLine", 301)
                .containsEntry("lineCount", 1);
    }

    @Test
    void discoveryIsOneSandboxExecution() {
        execution(
                ExecutionStatus.SUCCEEDED,
                Map.of("files", List.of(".gitignore", "src/x.java"), "truncated", false));
        assertThat(access.listFiles(id, ".", "**", 200))
                .containsEntry("files", List.of(".gitignore", "src/x.java"));
        verify(tools, times(1)).execute(any(), anyString(), anyMap(), any());
    }

    @Test
    void failedSearchPreservesError() {
        execution(ExecutionStatus.FAILED, Map.of());
        assertThat(access.searchCode(id, "[", ".", "**", 100))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "PROCESS_EXIT_NONZERO");
    }
}
