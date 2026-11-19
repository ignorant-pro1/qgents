package qg.qgent.project.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectDirectoryServiceTest {

    @TempDir Path root;

    @Test
    void deleteRemovesProjectDirectoryAndFiles() throws IOException {
        ProjectDirectoryService directories =
                new ProjectDirectoryService(root.toString(), root.toString());
        Path directory = directories.projectRoot(7L);
        Files.writeString(directory.resolve("memory.md"), "# 约定");
        Files.createDirectories(directory.resolve("workspace/repo"));

        directories.delete(7L);

        assertThat(directory).doesNotExist();
        assertThat(root).isEmptyDirectory();
    }

    @Test
    void deleteIgnoresProjectWithoutDirectory() {
        ProjectDirectoryService directories =
                new ProjectDirectoryService(root.toString(), root.toString());

        directories.delete(8L);

        assertThat(root).isEmptyDirectory();
    }
}
