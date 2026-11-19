package qg.qgent.sandbox.execution.handler;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandbox.exception.SandboxException;
import qg.qgent.sandbox.execution.dto.ToolContext;
import qg.qgent.sandbox.execution.dto.ToolResult;

class FilePatchToolTest {
    @TempDir Path repository;

    @Test
    void appliesSingleHunkPatchLeavingOtherSectionsUntouched() throws Exception {
        Path file = repository.resolve("README.md");
        Files.writeString(file, "line1\nline2\nline3\nline4\n", StandardCharsets.UTF_8);
        String patch =
                """
                --- a/README.md
                +++ b/README.md
                @@ -2,3 +2,3 @@
                 line2
                -line3
                +line3 changed
                 line4
                """;

        ToolResult result =
                tool().execute(
                                context(),
                                Map.of(
                                        "path",
                                        "README.md",
                                        "expectedHash",
                                        FileReadTool.sha256(Files.readAllBytes(file)).toUpperCase(),
                                        "patch",
                                        patch));

        assertEquals("line1\nline2\nline3 changed\nline4\n", Files.readString(file));
        assertEquals(
                FileReadTool.sha256(Files.readAllBytes(file)), result.getResult().get("sha256"));
        assertEquals(Files.readAllBytes(file).length, result.getResult().get("bytes"));
        assertEquals(Boolean.TRUE, result.getResult().get("changed"));
        assertEquals("README.md", result.getResult().get("path"));
    }

    @Test
    void appliesMultipleHunksPrecisely() throws Exception {
        Path file = repository.resolve("multi.txt");
        Files.writeString(file, "a\nb\nc\nd\ne\nf\n", StandardCharsets.UTF_8);
        String patch =
                """
                @@ -1,2 +1,2 @@
                 a
                -b
                +B
                @@ -5,2 +5,2 @@
                 e
                -f
                +F
                """;

        tool().execute(
                        context(),
                        Map.of(
                                "path",
                                "multi.txt",
                                "expectedHash",
                                FileReadTool.sha256(Files.readAllBytes(file)),
                                "patch",
                                patch));

        assertEquals("a\nB\nc\nd\ne\nF\n", Files.readString(file));
    }

    @Test
    void insertsAfterLineNamedInZeroCountHunk() throws Exception {
        Path file = repository.resolve("edit.txt");
        Files.writeString(file, "x\ny\nz\n", StandardCharsets.UTF_8);
        // 旧侧行数为 0 时，行号是插入点之前的那一行：@@ -1,0 @@ 表示插在第 1 行之后。
        String insertPatch =
                """
                @@ -1,0 +2,1 @@
                +inserted
                """;
        tool().execute(
                        context(),
                        Map.of(
                                "path",
                                "edit.txt",
                                "expectedHash",
                                FileReadTool.sha256(Files.readAllBytes(file)),
                                "patch",
                                insertPatch));
        assertEquals("x\ninserted\ny\nz\n", Files.readString(file));

        String deletePatch =
                """
                @@ -2,1 +2,0 @@
                -inserted
                """;
        tool().execute(
                        context(),
                        Map.of(
                                "path",
                                "edit.txt",
                                "expectedHash",
                                FileReadTool.sha256(Files.readAllBytes(file)),
                                "patch",
                                deletePatch));
        assertEquals("x\ny\nz\n", Files.readString(file));
    }

    @Test
    void insertsAtStartAndAfterLastLine() throws Exception {
        Path file = repository.resolve("edges.txt");
        Files.writeString(file, "b\n", StandardCharsets.UTF_8);
        String atStart =
                """
                @@ -0,0 +1,1 @@
                +a
                """;
        tool().execute(
                        context(),
                        Map.of(
                                "path",
                                "edges.txt",
                                "expectedHash",
                                FileReadTool.sha256(Files.readAllBytes(file)),
                                "patch",
                                atStart));
        assertEquals("a\nb\n", Files.readString(file));

        String atEnd =
                """
                @@ -2,0 +3,1 @@
                +c
                """;
        tool().execute(
                        context(),
                        Map.of(
                                "path",
                                "edges.txt",
                                "expectedHash",
                                FileReadTool.sha256(Files.readAllBytes(file)),
                                "patch",
                                atEnd));
        assertEquals("a\nb\nc\n", Files.readString(file));
    }

    @Test
    void appliesStandardDiffOutputWithContextAndAdditions() throws Exception {
        // 标准 `diff -u` 输出：上下文行、插入行与替换行混在同一个 hunk 里。
        Path file = repository.resolve("sample.txt");
        Files.writeString(
                file, "one\ntwo\nthree\nfour\nfive\nsix\nseven\n", StandardCharsets.UTF_8);
        String patch =
                """
                --- a/sample.txt
                +++ b/sample.txt
                @@ -1,7 +1,8 @@
                 one
                 two
                +inserted
                 three
                 four
                -five
                +FIVE
                 six
                 seven
                """;

        tool().execute(
                        context(),
                        Map.of(
                                "path",
                                "sample.txt",
                                "expectedHash",
                                FileReadTool.sha256(Files.readAllBytes(file)),
                                "patch",
                                patch));

        assertEquals("one\ntwo\ninserted\nthree\nfour\nFIVE\nsix\nseven\n", Files.readString(file));
    }

    @Test
    void keepsMissingTrailingNewlineWhenPatchingMiddle() throws Exception {
        Path file = repository.resolve("no-eol.txt");
        Files.writeString(file, "a\nb\nc", StandardCharsets.UTF_8);
        String patch =
                """
                @@ -1,1 +1,1 @@
                -a
                +A
                """;

        tool().execute(
                        context(),
                        Map.of(
                                "path",
                                "no-eol.txt",
                                "expectedHash",
                                FileReadTool.sha256(Files.readAllBytes(file)),
                                "patch",
                                patch));

        assertEquals("A\nb\nc", Files.readString(file));
    }

    @Test
    void keepsMissingTrailingNewlineWhenPatchReachesEndOfFile() throws Exception {
        // 真实 `diff -u` 对无尾换行文件的输出：标记跟在上下文行后，表示两侧最后一行都无换行。
        Path file = repository.resolve("eol.txt");
        Files.writeString(file, "a\nb\nc", StandardCharsets.UTF_8);
        String patch =
                """
                --- a/eol.txt
                +++ b/eol.txt
                @@ -1,3 +1,3 @@
                -a
                +A
                 b
                 c
                \\ No newline at end of file
                """;

        tool().execute(
                        context(),
                        Map.of(
                                "path",
                                "eol.txt",
                                "expectedHash",
                                FileReadTool.sha256(Files.readAllBytes(file)),
                                "patch",
                                patch));

        assertEquals("A\nb\nc", Files.readString(file));
    }

    @Test
    void rejectsPatchWhenBaseHashMismatches() throws Exception {
        Path file = repository.resolve("example.txt");
        Files.writeString(file, "current\n", StandardCharsets.UTF_8);
        String patch =
                """
                @@ -1,1 +1,1 @@
                -current
                +changed
                """;

        SandboxException exception =
                assertThrows(
                        SandboxException.class,
                        () ->
                                tool().execute(
                                                context(),
                                                Map.of(
                                                        "path",
                                                        "example.txt",
                                                        "expectedHash",
                                                        FileReadTool.sha256(
                                                                "stale"
                                                                        .getBytes(
                                                                                StandardCharsets
                                                                                        .UTF_8)),
                                                        "patch",
                                                        patch)));

        assertEquals("FILE_HASH_MISMATCH", exception.getCode());
        assertEquals("current\n", Files.readString(file));
    }

    @Test
    void failsWithoutPartialWriteWhenPatchContextDoesNotMatch() throws Exception {
        Path file = repository.resolve("mismatch.txt");
        Files.writeString(file, "aaa\nbbb\nccc\n", StandardCharsets.UTF_8);
        String patch =
                """
                @@ -1,3 +1,3 @@
                 aaa
                -xxx
                +yyy
                 ccc
                """;

        SandboxException exception =
                assertThrows(
                        SandboxException.class,
                        () ->
                                tool().execute(
                                                context(),
                                                Map.of(
                                                        "path",
                                                        "mismatch.txt",
                                                        "expectedHash",
                                                        FileReadTool.sha256(
                                                                Files.readAllBytes(file)),
                                                        "patch",
                                                        patch)));

        assertEquals("FILE_PATCH_FAILED", exception.getCode());
        assertEquals("aaa\nbbb\nccc\n", Files.readString(file));
    }

    @Test
    void rejectsHunkWithInconsistentDeclaredLineCount() throws Exception {
        Path file = repository.resolve("counts.txt");
        Files.writeString(file, "a\nb\nc\n", StandardCharsets.UTF_8);
        String patch =
                """
                @@ -1,3 +1,3 @@
                -a
                """;

        SandboxException exception =
                assertThrows(
                        SandboxException.class,
                        () ->
                                tool().execute(
                                                context(),
                                                Map.of(
                                                        "path",
                                                        "counts.txt",
                                                        "expectedHash",
                                                        FileReadTool.sha256(
                                                                Files.readAllBytes(file)),
                                                        "patch",
                                                        patch)));

        assertEquals("FILE_PATCH_FAILED", exception.getCode());
        assertEquals("a\nb\nc\n", Files.readString(file));
    }

    @Test
    void rejectsPatchWithoutHunks() throws Exception {
        Path file = repository.resolve("empty.txt");
        Files.writeString(file, "a\n", StandardCharsets.UTF_8);

        SandboxException exception =
                assertThrows(
                        SandboxException.class,
                        () ->
                                tool().execute(
                                                context(),
                                                Map.of(
                                                        "path",
                                                        "empty.txt",
                                                        "expectedHash",
                                                        FileReadTool.sha256(
                                                                Files.readAllBytes(file)),
                                                        "patch",
                                                        "--- a/empty.txt\n+++ b/empty.txt\n")));

        assertEquals("FILE_PATCH_FAILED", exception.getCode());
    }

    @Test
    void rejectsPathTraversalAndAbsolutePaths() throws Exception {
        Files.writeString(repository.resolve("safe.txt"), "a\n", StandardCharsets.UTF_8);
        FilePatchTool tool = tool();

        SandboxException traversal =
                assertThrows(
                        SandboxException.class,
                        () ->
                                tool.execute(
                                        context(),
                                        Map.of(
                                                "path",
                                                "../escape.txt",
                                                "expectedHash",
                                                "0".repeat(64),
                                                "patch",
                                                "@@ -1,1 +1,1 @@\n-a\n+b\n")));
        assertEquals("TOOL_PATH_INVALID", traversal.getCode());

        SandboxException absolute =
                assertThrows(
                        SandboxException.class,
                        () ->
                                tool.execute(
                                        context(),
                                        Map.of(
                                                "path",
                                                "/etc/passwd",
                                                "expectedHash",
                                                "0".repeat(64),
                                                "patch",
                                                "@@ -1,1 +1,1 @@\n-a\n+b\n")));
        assertEquals("TOOL_PATH_INVALID", absolute.getCode());
    }

    @Test
    void rejectsEmptyPatchOverlongPatchAndMissingPatch() throws Exception {
        Path file = repository.resolve("limits.txt");
        Files.writeString(file, "a\n", StandardCharsets.UTF_8);
        FilePatchTool tool = tool();
        String hash = FileReadTool.sha256(Files.readAllBytes(file));

        SandboxException empty =
                assertThrows(
                        SandboxException.class,
                        () ->
                                tool.execute(
                                        context(),
                                        Map.of(
                                                "path",
                                                "limits.txt",
                                                "expectedHash",
                                                hash,
                                                "patch",
                                                "")));
        assertEquals("TOOL_ARGUMENT_INVALID", empty.getCode());

        SandboxException missing =
                assertThrows(
                        SandboxException.class,
                        () ->
                                tool.execute(
                                        context(),
                                        Map.of("path", "limits.txt", "expectedHash", hash)));
        assertEquals("TOOL_ARGUMENT_INVALID", missing.getCode());

        String overlong = "x".repeat(1024 * 1024 + 1);
        SandboxException tooLong =
                assertThrows(
                        SandboxException.class,
                        () ->
                                tool.execute(
                                        context(),
                                        Map.of(
                                                "path",
                                                "limits.txt",
                                                "expectedHash",
                                                hash,
                                                "patch",
                                                overlong)));
        assertEquals("TOOL_ARGUMENT_INVALID", tooLong.getCode());
    }

    @Test
    void rejectsTargetFileLargerThan256KbBeforeApplyingPatch() throws Exception {
        // 按文件修改工具的 256KB 上限：超限目标拒绝打补丁并保持原文件不变，
        // 避免整读整写超大文件拖过执行超时后被误判为基础设施失败。
        Path file = repository.resolve("large.txt");
        String content = "line\n".repeat(60_000);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        String patch =
                """
                @@ -1,1 +1,1 @@
                -line
                +LINE
                """;

        SandboxException exception =
                assertThrows(
                        SandboxException.class,
                        () ->
                                tool().execute(
                                                context(),
                                                Map.of(
                                                        "path",
                                                        "large.txt",
                                                        "expectedHash",
                                                        FileReadTool.sha256(
                                                                Files.readAllBytes(file)),
                                                        "patch",
                                                        patch)));

        assertEquals("TOOL_PATH_INVALID", exception.getCode());
        assertEquals(content, Files.readString(file));
    }

    @Test
    void rejectsNonexistentFileAndDirectoryTargets() throws Exception {
        Files.createDirectories(repository.resolve("docs"));
        FilePatchTool tool = tool();

        SandboxException missing =
                assertThrows(
                        SandboxException.class,
                        () ->
                                tool.execute(
                                        context(),
                                        Map.of(
                                                "path",
                                                "missing.txt",
                                                "expectedHash",
                                                "0".repeat(64),
                                                "patch",
                                                "@@ -1,1 +1,1 @@\n-a\n+b\n")));
        assertEquals("TOOL_PATH_INVALID", missing.getCode());

        SandboxException directory =
                assertThrows(
                        SandboxException.class,
                        () ->
                                tool.execute(
                                        context(),
                                        Map.of(
                                                "path",
                                                "docs",
                                                "expectedHash",
                                                "0".repeat(64),
                                                "patch",
                                                "@@ -1,1 +1,1 @@\n-a\n+b\n")));
        assertEquals("TOOL_PATH_INVALID", directory.getCode());
    }

    @Test
    void rejectsBinaryNonUtf8Content() throws Exception {
        Path file = repository.resolve("binary.dat");
        byte[] bytes = {(byte) 0xFF, (byte) 0xFE, 'a', '\n'};
        Files.write(file, bytes);
        String patch =
                """
                @@ -1,1 +1,1 @@
                -a
                +b
                """;

        SandboxException exception =
                assertThrows(
                        SandboxException.class,
                        () ->
                                tool().execute(
                                                context(),
                                                Map.of(
                                                        "path",
                                                        "binary.dat",
                                                        "expectedHash",
                                                        FileReadTool.sha256(
                                                                Files.readAllBytes(file)),
                                                        "patch",
                                                        patch)));

        assertEquals("TOOL_PATH_INVALID", exception.getCode());
        assertArrayEquals(bytes, Files.readAllBytes(file), "原文件字节保持不变");
    }

    @Test
    void preservesSandboxOwnershipAndPermissionsAfterAtomicReplacementWhenRunningAsRoot()
            throws Exception {
        assumeTrue(Files.getFileAttributeView(repository, PosixFileAttributeView.class) != null);
        assumeTrue(((Number) Files.getAttribute(repository, "unix:uid")).longValue() == 0);

        Path file = repository.resolve("owned.txt");
        Files.writeString(file, "old\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(
                file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        String patch =
                """
                @@ -1,1 +1,1 @@
                -old
                +new
                """;

        tool().execute(
                        context(),
                        Map.of(
                                "path",
                                "owned.txt",
                                "expectedHash",
                                FileReadTool.sha256(Files.readAllBytes(file)),
                                "patch",
                                patch));

        assertEquals("new\n", Files.readString(file));
        assertEquals(10001L, ((Number) Files.getAttribute(file, "unix:uid")).longValue());
        assertEquals(10001L, ((Number) Files.getAttribute(file, "unix:gid")).longValue());
        assertEquals(
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file));
    }

    private FilePatchTool tool() {
        return new FilePatchTool(new RepositoryFileResolver());
    }

    @Test
    void newFileWithoutNoNewlineMarkerKeepsTrailingNewline() throws Exception {
        // 旧文件最后一行无换行，但 patch 在 + 行后没有 "\ No newline" 标记 → 新文件应有换行。
        Path file = repository.resolve("no-nl.txt");
        Files.writeString(file, "first\nlast", StandardCharsets.UTF_8);
        String patch =
                """
                --- a/no-nl.txt
                +++ b/no-nl.txt
                @@ -2 +2 @@
                -last
                +last changed
                """;

        ToolResult result =
                tool().execute(
                                context(),
                                Map.of(
                                        "path",
                                        "no-nl.txt",
                                        "expectedHash",
                                        FileReadTool.sha256(Files.readAllBytes(file)),
                                        "patch",
                                        patch));

        // 新文件必须以换行结尾：只有新侧明确标记 no-newline 才会写成无换行。
        assertEquals("first\nlast changed\n", Files.readString(file));
        assertEquals(Boolean.TRUE, result.getResult().get("changed"));
    }

    @Test
    void noNewlineMarkerOnNewSideSuppressesTrailingNewline() throws Exception {
        // + 行后带 "\ No newline at end of file" → 新文件最后一行确实无换行。
        Path file = repository.resolve("nl.txt");
        Files.writeString(file, "first\nlast\n", StandardCharsets.UTF_8);
        String patch =
                """
                --- a/nl.txt
                +++ b/nl.txt
                @@ -2 +2 @@
                -last
                +last
                \\ No newline at end of file
                """;

        tool().execute(
                        context(),
                        Map.of(
                                "path",
                                "nl.txt",
                                "expectedHash",
                                FileReadTool.sha256(Files.readAllBytes(file)),
                                "patch",
                                patch));

        assertEquals("first\nlast", Files.readString(file));
    }

    private ToolContext context() {
        return new ToolContext(null, repository, "/workspace/example", null);
    }
}
