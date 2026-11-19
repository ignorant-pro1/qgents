package qg.qgent.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandbox.config.SandboxProperties;
import qg.qgent.sandbox.exception.SandboxException;

/** 锁只把真正的锁文件 I/O 失败报为 WORKSPACE_LOCK_FAILED，业务异常原样传播。 */
class WorkspaceOperationLockTest {

    @TempDir Path root;

    @Test
    void lockFileFailureKeepsCodeAndCause() throws IOException {
        // 元数据根被一个普通文件占住，创建锁目录必然失败
        Path metadata = root.resolve("metadata");
        Files.writeString(metadata, "not a directory");

        var exception =
                org.assertj.core.api.Assertions.catchThrowable(
                        () -> lock(metadata).execute("key", () -> "ok"));

        assertThat(exception)
                .isInstanceOfSatisfying(
                        SandboxException.class,
                        failure -> {
                            assertThat(failure.code()).isEqualTo("WORKSPACE_LOCK_FAILED");
                            assertThat(failure.getCause()).isNotNull();
                        });
    }

    @Test
    void businessExceptionPropagatesUnchanged() {
        var lock = lock(root.resolve("metadata"));

        assertThatThrownBy(() ->
                        lock.execute(
                                "key",
                                () -> {
                                    throw new IllegalStateException("boom");
                                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }

    private WorkspaceOperationLock lock(Path metadataRoot) {
        var properties = new SandboxProperties();
        properties.setWorkspaceMetadataRoot(metadataRoot.toString());
        return new WorkspaceOperationLock(properties);
    }
}
