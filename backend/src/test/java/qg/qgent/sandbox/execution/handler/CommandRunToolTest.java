package qg.qgent.sandbox.execution.handler;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandbox.execution.dto.ToolContext;
import qg.qgent.sandbox.execution.output.CommandOutput;
import qg.qgent.sandbox.runtime.CommandExecutionResult;
import qg.qgent.sandbox.runtime.CommandExecutor;
import qg.qgent.sandbox.runtime.FakeCommandExecutor;
import qg.qgent.support.TestIds;

class CommandRunToolTest {
    @TempDir Path root;

    @Test
    void selectedDirectoryOutputAndNonzeroExitAreReturnedWithTemporaryAuthentication()
            throws Exception {
        Files.createDirectory(root.resolve("selected"));
        var executor = mock(CommandExecutor.class);
        var context = new ToolContext(null, root, "/workspace", Duration.ofSeconds(10));
        context.setExecutionId(TestIds.next());
        context.setEnvironment(
                Map.of("GIT_CONFIG_COUNT", "1", "GIT_CONFIG_VALUE_0", "very-secret-value"));
        when(executor.execute(any(), anyString(), anyList(), any(), anyMap()))
                .thenReturn(
                        new CommandExecutionResult(
                                17, List.of("101 results"), List.of("failed very-secret-value")));
        var result =
                new CommandRunTool(executor)
                        .execute(
                                context,
                                Map.of(
                                        "directory",
                                        "selected",
                                        "command",
                                        List.of("git", "status")));
        assertThat(result.getExitCode()).isEqualTo(17);
        assertThat((Map<String, Object>) result.getResult().get("stdout"))
                .containsEntry("text", "101 results");
        assertThat((Map<String, Object>) result.getResult().get("stderr"))
                .containsEntry("text", "failed [redacted]");
        Path output = CommandOutput.directory(root, context.getExecutionId());
        assertThat(Files.readString(output.resolve("stderr")))
                .isEqualTo("failed [redacted]")
                .doesNotContain("very-secret-value");
        verify(executor)
                .execute(
                        null,
                        "/workspace/selected",
                        List.of("git", "status"),
                        context.getTimeout(),
                        context.getEnvironment());
        assertThatThrownBy(() ->
                        new CommandRunTool(executor)
                                .execute(
                                        context,
                                        Map.of(
                                                "directory",
                                                "..",
                                                "command",
                                                List.of("git", "status"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonStreamingRuntimeUsesTheSameOutputWindows() throws Exception {
        var context = new ToolContext(null, root, "/workspace", Duration.ofSeconds(10));
        context.setExecutionId(TestIds.next());
        var result =
                new CommandRunTool(new FakeCommandExecutor())
                        .execute(context, Map.of("command", List.of("echo", "hello")));

        Path output = CommandOutput.directory(root, context.getExecutionId());
        String stdout = Files.readString(output.resolve("stdout"));
        assertThat(stdout).contains("echo hello");
        assertThat(CommandOutput.read(output, "stdout", 0, 16000)).containsEntry("text", stdout);
        assertThat(result.getResult()).containsEntry("outputAvailable", true);
    }
}
