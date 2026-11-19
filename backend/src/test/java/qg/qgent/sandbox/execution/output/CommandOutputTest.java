package qg.qgent.sandbox.execution.output;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandOutputTest {
    @TempDir Path root;

    @Test
    void readsHugeSingleLineWithoutLosingRemainingBytes() throws Exception {
        Files.writeString(root.resolve("stdout"), "x".repeat(80000));
        StringBuilder combined = new StringBuilder();
        long offset = 0;
        for (int i = 0; i < 5; i++) {
            var result = CommandOutput.read(root, "stdout", offset, 16000);
            combined.append(result.get("text"));
            offset = ((Number) result.get("nextOffset")).longValue();
        }
        assertThat(combined).hasToString("x".repeat(80000));
        assertThat(offset).isEqualTo(80000);
    }

    @Test
    void utf8PageBoundariesAndAppendedPartialLineRemainReadable() throws Exception {
        Files.writeString(root.resolve("stdout"), "汉汉");
        var first = CommandOutput.read(root, "stdout", 0, 4);
        assertThat(first).containsEntry("text", "汉").containsEntry("nextOffset", 3L);
        var second = CommandOutput.read(root, "stdout", 3, 4);
        assertThat(second).containsEntry("text", "汉").containsEntry("nextOffset", 6L);
        Files.writeString(root.resolve("stdout"), "字", java.nio.file.StandardOpenOption.APPEND);
        assertThat(CommandOutput.read(root, "stdout", 6, 4))
                .containsEntry("text", "字")
                .containsEntry("nextOffset", 9L);
    }

    @Test
    void diagnosticTailRetainsFinalErrorAndReportsTruncation() throws Exception {
        Files.writeString(root.resolve("stderr"), "x".repeat(30000) + "\nfinal error");
        var result = CommandOutput.tail(root, "stderr");
        assertThat(result).containsEntry("truncated", true);
        assertThat(result.get("text").toString()).endsWith("final error");
    }
}
