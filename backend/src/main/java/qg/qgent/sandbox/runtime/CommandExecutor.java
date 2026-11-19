package qg.qgent.sandbox.runtime;

import qg.qgent.sandbox.execution.output.CommandOutput;

import java.time.Duration;
import java.util.List;

/**
 * 沙箱内参数数组命令执行器。
 * 实现不得通过 shell 拼接命令，并必须遵守调用方给出的超时时间。
 */
public interface CommandExecutor {

    /**
     * @param sandbox          目标沙箱
     * @param workingDirectory 容器内受控工作目录
     * @param command          可执行文件和参数组成的数组
     * @param timeout          最大执行时间
     * @return 退出码以及受大小限制的标准输出和错误输出
     * @throws InterruptedException 命令超时或执行线程被取消时抛出
     */
    CommandExecutionResult execute(
        SandboxAllocation sandbox,
        String workingDirectory,
        List<String> command,
        Duration timeout)
        throws InterruptedException;

    default CommandExecutionResult execute(
        SandboxAllocation sandbox,
        String directory,
        List<String> command,
        Duration timeout,
        java.util.Map<String, String> environment)
        throws InterruptedException {
        if (!environment.isEmpty()) throw new IllegalStateException("此命令运行器不支持凭证环境");
        return execute(sandbox, directory, command, timeout);
    }

    /**
     * 保存完整输出，供长执行查询；不支持流式输出的运行器在结束后保存。
     */
    default CommandExecutionResult execute(
        SandboxAllocation sandbox,
        String directory,
        List<String> command,
        Duration timeout,
        java.util.Map<String, String> environment,
        java.nio.file.Path outputDirectory)
        throws InterruptedException {
        CommandExecutionResult result = execute(sandbox, directory, command, timeout, environment);
        CommandOutput.save(outputDirectory, result, environment);
        return result;
    }
}
