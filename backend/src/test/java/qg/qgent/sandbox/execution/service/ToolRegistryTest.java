package qg.qgent.sandbox.execution.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandbox.exception.SandboxException;
import qg.qgent.sandbox.execution.dto.ToolContext;
import qg.qgent.sandbox.execution.dto.ToolResult;
import qg.qgent.sandbox.execution.handler.FilePatchTool;
import qg.qgent.sandbox.execution.handler.RepositoryFileResolver;

class ToolRegistryTest {
    @TempDir Path repository;

    @Test
    void discoversAndExecutesFilePatch() throws Exception {
        Path file = repository.resolve("note.txt");
        Files.writeString(file, "one\ntwo\nthree\n", StandardCharsets.UTF_8);
        ToolRegistry registry =
                new ToolRegistry(List.of(new FilePatchTool(new RepositoryFileResolver())));
        String patch =
                """
                @@ -2,1 +2,1 @@
                -two
                +TWO
                """;

        ToolResult result =
                registry.execute(
                        "file.patch",
                        context(),
                        Map.of(
                                "path",
                                "note.txt",
                                "expectedHash",
                                HexFormat.of()
                                        .formatHex(
                                                MessageDigest.getInstance("SHA-256")
                                                        .digest(Files.readAllBytes(file))),
                                "patch",
                                patch));

        assertEquals("one\nTWO\nthree\n", Files.readString(file));
        assertEquals(0, result.getExitCode());
    }

    @Test
    void unknownToolStillReturnsToolNotSupported() {
        ToolRegistry registry =
                new ToolRegistry(List.of(new FilePatchTool(new RepositoryFileResolver())));

        SandboxException exception =
                assertThrows(
                        SandboxException.class,
                        () -> registry.execute("file.nope", context(), Map.of()));

        assertEquals("TOOL_NOT_SUPPORTED", exception.getCode());
    }

    private ToolContext context() {
        return new ToolContext(null, repository, "/workspace/note", null);
    }
}
