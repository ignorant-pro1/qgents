package qg.qgent.sandbox.execution.handler;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandbox.exception.SandboxException;
import qg.qgent.sandbox.execution.dto.ToolContext;
import qg.qgent.sandbox.runtime.CommandExecutionResult;
import qg.qgent.sandbox.runtime.CommandExecutor;

class FileToolsTest {
    @TempDir Path root;

    private ToolContext context() {
        return new ToolContext(null, root, "/workspace", Duration.ofSeconds(30));
    }

    @Test
    void readsWindowFromFileLargerThanOld64KbLimit() throws Exception {
        String content = "a".repeat(80) + "\r\n";
        Files.writeString(root.resolve("large.txt"), content.repeat(2000));
        var result =
                new FileReadTool(new RepositoryFileResolver())
                        .execute(
                                context(),
                                Map.of("path", "large.txt", "startLine", 1500, "lineCount", 2))
                        .getResult();
        assertThat(result)
                .containsEntry("totalLines", 2000)
                .containsEntry("nextLine", 1502)
                .containsEntry("newlineStyle", "CRLF");
        assertThat((List<Map<String, Object>>) result.get("lines"))
                .hasSize(2)
                .first()
                .satisfies(line -> assertThat(line).containsEntry("number", 1500));
        assertThat(result.get("sha256"))
                .isEqualTo(FileReadTool.sha256(Files.readAllBytes(root.resolve("large.txt"))));
    }

    @Test
    void emptyFileAndNewlineOnlyFileKeepActualLineCount() throws Exception {
        Files.writeString(root.resolve("empty"), "");
        Files.writeString(root.resolve("newline"), "\n");
        var tool = new FileReadTool(new RepositoryFileResolver());
        assertThat(tool.execute(context(), Map.of("path", "empty")).getResult())
                .containsEntry("totalLines", 0)
                .containsEntry("lines", List.of());
        assertThat(tool.execute(context(), Map.of("path", "newline")).getResult())
                .containsEntry("totalLines", 1)
                .containsEntry("endsWithNewline", true);
    }

    @Test
    void capsLargeLineAndSignalsTruncation() throws Exception {
        Files.writeString(root.resolve("big"), "x".repeat(200000));
        var result =
                new FileReadTool(new RepositoryFileResolver())
                        .execute(context(), Map.of("path", "big"))
                        .getResult();
        assertThat(result).containsEntry("truncated", true);
        assertThat(((Map<?, ?>) ((List<?>) result.get("lines")).getFirst()).get("text").toString())
                .hasSize(4000);
    }

    @Test
    void discoversHiddenFilesAndReportsCap() throws Exception {
        Files.writeString(root.resolve(".gitignore"), "build");
        Files.createDirectories(root.resolve(".github/workflows"));
        Files.writeString(root.resolve(".github/workflows/build.yml"), "on: push");
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/config"), "internal");
        var tool = new FileGlobTool(new RepositoryFileResolver());
        assertThat((List<String>) tool.execute(context(), Map.of()).getResult().get("files"))
                .containsExactly(".github/workflows/build.yml", ".gitignore");
        assertThat(tool.execute(context(), Map.of("limit", 1)).getResult())
                .containsEntry("truncated", true);
    }

    @Test
    void globPatternMatchesRootAndNestedFiles() throws Exception {
        Files.writeString(root.resolve("Root.java"), "");
        Files.createDirectory(root.resolve("src"));
        Files.writeString(root.resolve("src/Nested.java"), "");
        assertThat(
                        (List<String>)
                                new FileGlobTool(new RepositoryFileResolver())
                                        .execute(context(), Map.of("glob", "**/*.java"))
                                        .getResult()
                                        .get("files"))
                .containsExactly("Root.java", "src/Nested.java");
    }

    @Test
    void creationRejectsExistingHiddenFileAndReplacementRejectsMissingEmptyFile() throws Exception {
        Files.writeString(root.resolve(".gitignore"), "old");
        var tool = new FileWriteTool(new RepositoryFileResolver());
        assertThatThrownBy(() ->
                        tool.execute(
                                context(),
                                Map.of(
                                        "path",
                                        ".gitignore",
                                        "mode",
                                        "create",
                                        "expectedHash",
                                        FileReadTool.sha256("old".getBytes()),
                                        "content",
                                        "new")))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("文件已存在");
        assertThat(Files.readString(root.resolve(".gitignore"))).isEqualTo("old");
        assertThatThrownBy(() ->
                        tool.execute(
                                context(),
                                Map.of(
                                        "path",
                                        "missing",
                                        "mode",
                                        "replace",
                                        "expectedHash",
                                        FileReadTool.sha256(new byte[0]),
                                        "content",
                                        "new")))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("文件不存在");
        assertThat(root.resolve("missing")).doesNotExist();
    }

    @Test
    void textEditPreservesCrLfAndSupportsDeletion() throws Exception {
        Files.writeString(root.resolve("x"), "one\r\ntwo\r\nthree\r\n");
        var tool = new FileEditTool(new RepositoryFileResolver());
        tool.execute(
                context(),
                Map.of(
                        "path",
                        "x",
                        "expectedHash",
                        FileReadTool.sha256(Files.readAllBytes(root.resolve("x"))),
                        "oldText",
                        "one\ntwo",
                        "newText",
                        "ONE\nTWO"));
        assertThat(Files.readString(root.resolve("x"))).isEqualTo("ONE\r\nTWO\r\nthree\r\n");
        tool.execute(
                context(),
                Map.of(
                        "path",
                        "x",
                        "expectedHash",
                        FileReadTool.sha256(Files.readAllBytes(root.resolve("x"))),
                        "oldText",
                        "three\n",
                        "newText",
                        ""));
        assertThat(Files.readString(root.resolve("x"))).isEqualTo("ONE\r\nTWO\r\n");
    }

    @Test
    void ambiguousOrStaleEditDoesNotModifyFile() throws Exception {
        Files.writeString(root.resolve("x"), "old old");
        var tool = new FileEditTool(new RepositoryFileResolver());
        var arguments =
                Map.<String, Object>of(
                        "path",
                        "x",
                        "expectedHash",
                        FileReadTool.sha256(Files.readAllBytes(root.resolve("x"))),
                        "oldText",
                        "old",
                        "newText",
                        "new");
        assertThatThrownBy(() -> tool.execute(context(), arguments))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("匹配多处");
        assertThatThrownBy(() ->
                        tool.execute(
                                context(),
                                Map.of(
                                        "path",
                                        "x",
                                        "expectedHash",
                                        "a".repeat(64),
                                        "oldText",
                                        "old",
                                        "newText",
                                        "new")))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("文件已变化");
        assertThat(Files.readString(root.resolve("x"))).isEqualTo("old old");
        var all = new java.util.HashMap<>(arguments);
        all.put("replaceAll", true);
        tool.execute(context(), all);
        assertThat(Files.readString(root.resolve("x"))).isEqualTo("new new");
    }

    @Test
    void searchPreservesColonInFilenameAndMatchingLines() throws Exception {
        var executor = mock(CommandExecutor.class);
        String match =
                new ObjectMapper()
                        .writeValueAsString(
                                Map.of(
                                        "type",
                                        "match",
                                        "data",
                                        Map.of(
                                                "path",
                                                Map.of("text", "src/name:part.java"),
                                                "line_number",
                                                13,
                                                "lines",
                                                Map.of("text", "hello\n"))));
        when(executor.execute(any(), anyString(), anyList(), any()))
                .thenReturn(new CommandExecutionResult(0, List.of(match), List.of()));
        var result =
                new FileSearchTool(executor, new ObjectMapper(), new RepositoryFileResolver())
                        .execute(context(), Map.of("query", "hello"))
                        .getResult();
        assertThat((List<Map<String, Object>>) result.get("matches"))
                .containsExactly(
                        Map.of(
                                "path",
                                "src/name:part.java",
                                "lineNumber",
                                13,
                                "text",
                                "hello",
                                "truncated",
                                false));
    }

    @Test
    void truncatedOutputKeepsCompleteMatchesAndReportsTruncation() throws Exception {
        var executor = mock(CommandExecutor.class);
        var mapper = new ObjectMapper();
        String complete =
                mapper.writeValueAsString(
                        Map.of(
                                "type",
                                "match",
                                "data",
                                Map.of(
                                        "path",
                                        Map.of("text", "a.txt"),
                                        "line_number",
                                        1,
                                        "lines",
                                        Map.of("text", "hello\n"))));
        // 输出按字节截断会把最后一条 JSON 切成半条，截断标记单独成行
        when(executor.execute(any(), anyString(), anyList(), any()))
                .thenReturn(
                        new CommandExecutionResult(
                                0,
                                List.of(
                                        complete,
                                        complete.substring(0, complete.length() / 2),
                                        "[输出超过限制，已截断]"),
                                List.of()));
        var result =
                new FileSearchTool(executor, mapper, new RepositoryFileResolver())
                        .execute(context(), Map.of("query", "hello"))
                        .getResult();
        assertThat((List<Map<String, Object>>) result.get("matches"))
                .containsExactly(
                        Map.of(
                                "path",
                                "a.txt",
                                "lineNumber",
                                1,
                                "text",
                                "hello",
                                "truncated",
                                false));
        assertThat(result.get("truncated")).isEqualTo(true);
    }

    @Test
    void noMatchesIsSuccessAndBadRegexIsFailure() throws Exception {
        var executor = mock(CommandExecutor.class);
        var tool = new FileSearchTool(executor, new ObjectMapper(), new RepositoryFileResolver());
        when(executor.execute(any(), anyString(), anyList(), any()))
                .thenReturn(new CommandExecutionResult(1, List.of(), List.of()));
        assertThat(tool.execute(context(), Map.of("query", "missing")).getResult())
                .containsEntry("matches", List.of());
        when(executor.execute(any(), anyString(), anyList(), any()))
                .thenReturn(new CommandExecutionResult(2, List.of(), List.of("regex parse error")));
        assertThat(tool.execute(context(), Map.of("query", "[")).getExitCode()).isEqualTo(2);
    }

    @Test
    void integerArgumentsRejectFractions() {
        assertThatThrownBy(() ->
                        qg.qgent.sandbox.execution.dto.ToolArguments.integer(
                                Map.of("lineCount", 1.5), "lineCount", 200, 1, 1000))
                .isInstanceOf(SandboxException.class);
    }
}
