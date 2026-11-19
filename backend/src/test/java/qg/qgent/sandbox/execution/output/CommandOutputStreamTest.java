package qg.qgent.sandbox.execution.output;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandOutputStreamTest {
    @TempDir Path root;

    @Test
    void preservesFullOutputOnDiskWhileBoundingMemory() throws Exception {
        Path file = root.resolve("stdout");
        var stream = new CommandOutput.LimitedOutputStream(8, file);
        try (stream) {
            stream.write("first chunk".getBytes(StandardCharsets.UTF_8));
            assertThat(Files.readString(file)).isEqualTo("first chunk");
            stream.write(" then final diagnostic".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(stream.value()).isEqualTo("first ch");
        assertThat(stream.isTruncated()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("first chunk then final diagnostic");
    }
}
