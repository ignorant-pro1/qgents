package qg.qgent.sandbox.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.sandbox.execution.entity.ExecutionStatus;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Workspace 文件操作入口，统一保留读取窗口、写入版本、执行结果和失败码。
 */
@Service
@RequiredArgsConstructor
public class SandboxFiles {
    private static final String EMPTY_HASH =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private final SandboxToolService tools;

    public ToolResult listFiles(Long id, String path, String glob, int limit) {
        return execute(id, "file.glob", Map.of("path", path, "glob", glob, "limit", limit));
    }

    public ToolResult readFile(Long id, String path, int startLine, int lineCount) {
        return execute(
            id,
            "file.read",
            Map.of("path", path, "startLine", startLine, "lineCount", lineCount));
    }

    public ToolResult searchCode(Long id, String pattern, String path, String glob, int limit) {
        return execute(
            id,
            "file.search",
            Map.of("query", pattern, "path", path, "glob", glob, "limit", limit));
    }

    public ToolResult writeFile(Long id, String path, String content) {
        return write(id, path, EMPTY_HASH, content, "create");
    }

    public ToolResult replaceFile(Long id, String path, String hash, String content) {
        return write(id, path, hash, content, "replace");
    }

    private ToolResult write(Long id, String path, String hash, String content, String mode) {
        if (content == null || content.getBytes(StandardCharsets.UTF_8).length > 256 * 1024) {
            return ToolResult.error("TOOL_ARGUMENT_INVALID", "内容不能为空引用或超过 256KB");
        }
        return mutate(
            id,
            "file.write",
            Map.of("path", path, "expectedHash", hash, "content", content, "mode", mode));
    }

    public ToolResult patchFile(Long id, String path, String hash, String patch) {
        if (patch == null || patch.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) {
            return ToolResult.error("TOOL_ARGUMENT_INVALID", "补丁不能为空引用或超过 1MiB");
        }
        return mutate(id, "file.patch", Map.of("path", path, "expectedHash", hash, "patch", patch));
    }

    public ToolResult editFile(
        Long id, String path, String hash, String oldText, String newText, boolean replaceAll) {
        return mutate(
            id,
            "file.edit",
            Map.of(
                "path",
                path,
                "expectedHash",
                hash,
                "oldText",
                oldText,
                "newText",
                newText,
                "replaceAll",
                replaceAll));
    }

    private ToolResult mutate(Long id, String tool, Map<String, Object> arguments) {
        try {
            return execute(id, tool, arguments);
        } catch (RuntimeException exception) {
            return ToolResult.error(
                "TOOL_FAILED",
                exception.getMessage() == null ? "Sandbox 执行失败" : exception.getMessage());
        }
    }

    private ToolResult execute(Long id, String tool, Map<String, Object> arguments) {
        var execution = tools.execute(id, tool, arguments, Duration.ofSeconds(30));
        ToolResult result =
            execution.getStatus() == ExecutionStatus.SUCCEEDED
                ? ToolResult.ok()
                : ToolResult.error(
                execution.getFailureCode() == null
                ? "TOOL_FAILED"
                : execution.getFailureCode(),
                execution.getFailureReason() == null
                ? "文件操作失败"
                : execution.getFailureReason());
        result.putAll(execution.getResult());
        return result;
    }
}
