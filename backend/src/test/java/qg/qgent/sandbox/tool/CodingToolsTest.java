package qg.qgent.sandbox.tool;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import qg.qgent.harness.tool.ParallelToolCallback;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.sandbox.service.SandboxFiles;
import qg.qgent.support.TestIds;
import qg.qgent.workspace.service.WorkSession;

class CodingToolsTest {
    private final Long id = TestIds.next();
    private final SandboxFiles files = mock(SandboxFiles.class);
    private final WorkSession session = mock(WorkSession.class);
    private final CodingTools tools = new CodingTools(session, files);
    private final String hash = "a".repeat(64);

    @BeforeEach
    void sessionUsesCurrentWorkspace() {
        when(session.ensure()).thenReturn(id);
    }

    private CodingTools nested(String directory) {
        WorkSession nested = mock(WorkSession.class);
        when(nested.ensure()).thenReturn(id);
        when(nested.directory()).thenReturn(directory);
        return new CodingTools(nested, files);
    }

    private ToolResult ok(Map<String, Object> fields) {
        ToolResult result = ToolResult.ok();
        result.putAll(fields);
        return result;
    }

    private void read(String path) {
        when(files.readFile(id, path, 1, 200)).thenReturn(ok(Map.of("sha256", hash)));
        tools.readFile(path, null, null);
    }

    @Test
    void uniqueReplacementReadsTheCurrentVersionAndPreservesConflicts() {
        when(files.readFile(id, "x.txt", 1, 1)).thenReturn(ok(Map.of("sha256", hash)));
        ToolResult conflict = ToolResult.error("EDIT_TEXT_AMBIGUOUS", "multiple matches");
        when(files.editFile(id, "x.txt", hash, "old", "new", false)).thenReturn(conflict);

        assertThat(tools.replaceUniqueText("x.txt", "old", "new")).isSameAs(conflict);

        var order = inOrder(files);
        order.verify(files).readFile(id, "x.txt", 1, 1);
        order.verify(files).editFile(id, "x.txt", hash, "old", "new", false);
        order.verifyNoMoreInteractions();
    }

    @Test
    void uniqueReplacementDoesNotWriteWhenReadingFails() {
        ToolResult missing = ToolResult.error("FILE_NOT_FOUND", "file missing");
        when(files.readFile(id, "x.txt", 1, 1)).thenReturn(missing);
        assertThat(tools.replaceUniqueText("x.txt", "old", "new")).isSameAs(missing);
        verify(files, never()).editFile(any(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void modelSchemaUsesWindowsAndHidesInternalHashesAndOldTools() {
        var definitions =
                java.util.Arrays.stream(ToolCallbacks.from(tools))
                        .map(t -> t.getToolDefinition())
                        .toList();
        assertThat(definitions)
                .extracting(d -> d.name())
                .containsExactlyInAnyOrder(
                        "read_file",
                        "glob_files",
                        "search_code",
                        "write_file",
                        "edit_file",
                        "apply_patch");
        assertThat(definitions)
                .allSatisfy(d -> assertThat(d.inputSchema()).doesNotContain("expectedHash"));
        assertThat(
                        definitions.stream()
                                .filter(d -> d.name().equals("read_file"))
                                .findFirst()
                                .orElseThrow()
                                .inputSchema())
                .contains("startLine", "lineCount");
    }

    @Test
    void parallelMetadataPreservesSchemaAndLeavesWritesExclusive() {
        var nativeCallbacks = ToolCallbacks.from(tools);
        var classified = ParallelToolCallback.from(tools);
        for (var callback : classified) {
            var original =
                    java.util.Arrays.stream(nativeCallbacks)
                            .filter(c ->
                                    c.getToolDefinition()
                                            .name()
                                            .equals(callback.getToolDefinition().name()))
                            .findFirst()
                            .orElseThrow();
            assertThat(callback.getToolDefinition()).isEqualTo(original.getToolDefinition());
            boolean read =
                    java.util.Set.of("read_file", "glob_files", "search_code")
                            .contains(callback.getToolDefinition().name());
            assertThat(callback instanceof ParallelToolCallback).isEqualTo(read);
        }
    }

    @Test
    void readWindowDoesNotReadMorePages() {
        var result = ok(Map.of("sha256", hash, "truncated", true, "nextLine", 501));
        when(files.readFile(id, "big.java", 301, 200)).thenReturn(result);
        assertThat(tools.readFile("big.java", 301, 200)).isSameAs(result);
        verify(files).readFile(id, "big.java", 301, 200);
        verifyNoMoreInteractions(files);
    }

    @Test
    void searchFailureDoesNotBecomeEmptySuccess() {
        var error = ToolResult.error("PROCESS_EXIT_NONZERO", "invalid regex");
        when(files.searchCode(id, "[", "src", "**/*.java", 100)).thenReturn(error);
        assertThat(tools.searchCode("[", "src", "**/*.java", null)).isSameAs(error);
    }

    @Test
    void unreadHiddenFileIsCreatedAtSandboxWithoutListingRepository() {
        when(files.writeFile(id, ".gitignore", "new"))
                .thenReturn(ToolResult.error("FILE_ALREADY_EXISTS", "已有文件"));
        assertThat(tools.writeFile("./.gitignore", "new"))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "FILE_ALREADY_EXISTS");
        verify(files).writeFile(id, ".gitignore", "new");
    }

    @Test
    void observedFileUsesCachedVersionForWholeFileReplacement() {
        read("x.txt");
        when(files.replaceFile(id, "x.txt", hash, "next"))
                .thenReturn(ok(Map.of("path", "x.txt", "sha256", "b".repeat(64), "changed", true)));
        assertThat(tools.writeFile("x.txt", "next"))
                .containsEntry("ok", true)
                .containsEntry("sha256", "b".repeat(64));
        verify(files).replaceFile(id, "x.txt", hash, "next");
    }

    @Test
    void editRequiresReadAndPreservesConflictCode() {
        assertThat(tools.editFile("x.txt", "old", "", null))
                .containsEntry("errorCode", "FILE_NOT_READ");
        verifyNoInteractions(files);
        read("x.txt");
        when(files.editFile(id, "x.txt", hash, "old", "", false))
                .thenReturn(ToolResult.error("FILE_HASH_MISMATCH", "重新读取"));
        assertThat(tools.editFile("x.txt", "old", "", false))
                .containsEntry("errorCode", "FILE_HASH_MISMATCH");
    }

    @Test
    void switchingWorkspaceInvalidatesObservedVersions() {
        var selected = new AtomicReference<>(id);
        WorkSession switching = mock(WorkSession.class);
        when(switching.ensure()).thenAnswer(invocation -> selected.get());
        var tool = new CodingTools(switching, files);
        when(files.readFile(id, "x.txt", 1, 200)).thenReturn(ok(Map.of("sha256", hash)));
        tool.readFile("x.txt", null, null);
        selected.set(TestIds.next());
        assertThat(tool.applyPatch("x.txt", "patch")).containsEntry("errorCode", "FILE_NOT_READ");
        verify(files, never()).patchFile(any(), anyString(), anyString(), anyString());
    }

    @Test
    void successfulEditUpdatesVersionForNextMutation() {
        read("x.txt");
        when(files.editFile(id, "x.txt", hash, "old", "new", false))
                .thenReturn(ok(Map.of("path", "x.txt", "sha256", "b".repeat(64), "changed", true)));
        tools.editFile("x.txt", "old", "new", null);
        when(files.patchFile(id, "x.txt", "b".repeat(64), "patch"))
                .thenReturn(ok(Map.of("path", "x.txt", "sha256", "c".repeat(64), "changed", true)));
        assertThat(tools.applyPatch("x.txt", "patch")).containsEntry("sha256", "c".repeat(64));
    }

    @Test
    void invalidArgumentsDoNotExecuteTools() {
        assertThatThrownBy(() -> tools.readFile("../x", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.readFile("x", 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.globFiles(null, null, 1001))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(files);
    }

    @Test
    void fileToolsResolvePathsFromTheCurrentDirectoryAndNormalizeWindowsSeparators() {
        var nestedTools = nested("src/main");
        when(files.readFile(id, "src/README.md", 1, 200)).thenReturn(ok(Map.of("sha256", hash)));
        when(files.readFile(id, "src/test/App.java", 1, 200))
                .thenReturn(ok(Map.of("sha256", hash)));

        nestedTools.readFile("../README.md", null, null);
        nestedTools.readFile("..\\test\\App.java", null, null);

        verify(files).readFile(id, "src/README.md", 1, 200);
        verify(files).readFile(id, "src/test/App.java", 1, 200);
    }

    @Test
    void fileToolsRejectPathsThatEscapeTheWorkspaceAndDirectoriesUsedAsFiles() {
        var nestedTools = nested("src/main");

        assertThatThrownBy(() -> nestedTools.readFile("../../../outside.txt", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.readFile(".", null, null))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(files);
    }
}
