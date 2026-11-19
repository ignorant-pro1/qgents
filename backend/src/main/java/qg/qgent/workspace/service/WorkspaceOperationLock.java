package qg.qgent.workspace.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import qg.qgent.sandbox.config.SandboxProperties;
import qg.qgent.sandbox.exception.SandboxException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 使用 JVM keyed lock 与共享文件锁串行化 Workspace 或仓库操作。
 */
@Component
@RequiredArgsConstructor
public class WorkspaceOperationLock {

    private final SandboxProperties properties;
    private final ConcurrentMap<String, ReentrantLock> localLocks = new ConcurrentHashMap<>();

    /**
     * 同 JVM 先串行，再获取跨进程文件锁，避免 OverlappingFileLockException。
     */
    public <T> T execute(String key, LockedOperation<T> operation) {
        String lockName = hash(key);
        ReentrantLock localLock =
            localLocks.computeIfAbsent(lockName, ignored -> new ReentrantLock());
        localLock.lock();
        try {
            Path directory =
                Path.of(properties.getWorkspaceMetadataRoot())
                    .toAbsolutePath()
                    .normalize()
                    .resolve("locks");
            Files.createDirectories(directory);
            try (FileChannel channel =
                     FileChannel.open(
                         directory.resolve(lockName + ".lock"),
                         StandardOpenOption.CREATE,
                         StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return operation.run();
            }
        } catch (RuntimeException exception) {
            // 业务异常按原样向上传播，避免被包装成锁失败
            throw exception;
        } catch (IOException exception) {
            throw new SandboxException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "WORKSPACE_LOCK_FAILED",
                "无法获取 Workspace 操作锁",
                exception);
        } finally {
            localLock.unlock();
        }
    }

    private String hash(String key) {
        if (key == null || key.isBlank())
            throw new SandboxException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "WORKSPACE_STORAGE_KEY_INVALID",
                "Workspace 存储标识不合法");
        try {
            return HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(key.getBytes(StandardCharsets.UTF_8)),
                    0,
                    16);
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算操作锁名称", exception);
        }
    }

    @FunctionalInterface
    public interface LockedOperation<T> {
        T run();
    }
}
