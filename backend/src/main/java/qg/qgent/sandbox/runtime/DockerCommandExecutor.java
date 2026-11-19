package qg.qgent.sandbox.runtime;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import qg.qgent.sandbox.config.SandboxProperties;
import qg.qgent.sandbox.exception.SandboxException;
import qg.qgent.sandbox.execution.output.CommandOutput.LimitedOutputStream;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 使用 Docker Exec API 在既有沙箱容器内执行参数数组命令。
 * 命令不会经过 shell 拼接；输出受到本地字节上限保护。
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "sandbox.runtime", havingValue = "docker")
public class DockerCommandExecutor implements CommandExecutor {
    private final DockerClient docker;
    private final SandboxProperties properties;
    private final ConcurrentMap<String, ReentrantLock> sandboxCommandLocks =
        new ConcurrentHashMap<>();

    /**
     * 在沙箱内执行单条命令。超时或线程中断时会重启容器以终止残留进程，Workspace 挂载内容保持不变。
     */
    @Override
    public CommandExecutionResult execute(
        SandboxAllocation sandbox,
        String workingDirectory,
        List<String> command,
        Duration timeout)
        throws InterruptedException {
        return execute(sandbox, workingDirectory, command, timeout, java.util.Map.of());
    }

    @Override
    public CommandExecutionResult execute(
        SandboxAllocation sandbox,
        String workingDirectory,
        List<String> command,
        Duration timeout,
        java.util.Map<String, String> environment)
        throws InterruptedException {
        return execute(sandbox, workingDirectory, command, timeout, environment, null);
    }

    @Override
    public CommandExecutionResult execute(
        SandboxAllocation sandbox,
        String workingDirectory,
        List<String> command,
        Duration timeout,
        java.util.Map<String, String> environment,
        java.nio.file.Path outputDirectory)
        throws InterruptedException {
        if (sandbox.getRuntimeHandle() == null) {
            throw new IllegalStateException("沙箱缺少底层容器编号");
        }
        ReentrantLock lock =
            sandboxCommandLocks.computeIfAbsent(
                sandbox.getRuntimeHandle(), ignored -> new ReentrantLock());
        lock.lockInterruptibly();
        try {
            return executeLocked(
                sandbox, workingDirectory, command, timeout, environment, outputDirectory);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 在同一容器命令锁内执行 Docker Exec。
     * 取消或超时需要重启容器，因此同一 Sandbox 内的容器命令必须串行，避免误杀其他正在运行的命令。
     */
    private CommandExecutionResult executeLocked(
        SandboxAllocation sandbox,
        String workingDirectory,
        List<String> command,
        Duration timeout,
        java.util.Map<String, String> environment,
        java.nio.file.Path outputDirectory)
        throws InterruptedException {
        String execId;
        try {
            execId =
                docker.execCreateCmd(sandbox.getRuntimeHandle())
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withWorkingDir(workingDirectory)
                    .withEnv(
                        environment.entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .toList())
                    .withCmd(command.toArray(String[]::new))
                    .exec()
                    .getId();
        } catch (RuntimeException exception) {
            throw dockerFailure("create", sandbox, exception);
        }
        LimitedOutputStream stdout =
            new LimitedOutputStream(
                properties.getMaxOutputBytes(),
                outputDirectory == null ? null : outputDirectory.resolve("stdout"));
        LimitedOutputStream stderr =
            new LimitedOutputStream(
                properties.getMaxOutputBytes(),
                outputDirectory == null ? null : outputDirectory.resolve("stderr"));
        try (stdout;
             stderr;
             ExecStartResultCallback callback = new ExecStartResultCallback(stdout, stderr)) {
            boolean completed =
                docker.execStartCmd(execId)
                    .exec(callback)
                    .awaitCompletion(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                throw new InterruptedException("Docker Exec 执行超时");
            }
        } catch (InterruptedException exception) {
            restartContainer(sandbox.getRuntimeHandle());
            throw exception;
        } catch (Exception exception) {
            throw dockerFailure("start", sandbox, exception);
        }
        Long exitCode;
        try {
            exitCode = docker.inspectExecCmd(execId).exec().getExitCodeLong();
        } catch (RuntimeException exception) {
            throw dockerFailure("inspect", sandbox, exception);
        }
        return new CommandExecutionResult(
            exitCode == null ? -1 : exitCode.intValue(), stdout.lines(), stderr.lines());
    }

    /**
     * Docker 异常只记录内部类别，接口统一返回稳定错误码，避免把引擎详情或宿主路径暴露给用户。
     */
    private SandboxException dockerFailure(
        String phase, SandboxAllocation sandbox, Throwable exception) {
        log.warn(
            "docker exec failed phase={} sandboxId={} category={}",
            phase,
            sandbox == null ? null : sandbox.getId(),
            exception.getClass().getSimpleName());
        return new SandboxException(
            org.springframework.http.HttpStatus.BAD_GATEWAY,
            "DOCKER_EXEC_FAILED",
            "Docker Exec 执行失败");
    }

    private void restartContainer(String containerId) {
        try {
            docker.killContainerCmd(containerId).exec();
            docker.startContainerCmd(containerId).exec();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("终止超时命令后无法恢复沙箱容器", exception);
        }
    }
}
