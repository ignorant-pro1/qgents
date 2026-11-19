package qg.qgent.sandbox.execution.handler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import qg.qgent.sandbox.exception.SandboxException;
import qg.qgent.sandbox.execution.dto.ToolArguments;
import qg.qgent.sandbox.execution.dto.ToolContext;
import qg.qgent.sandbox.execution.dto.ToolResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.springframework.http.HttpStatus.CONFLICT;

/**
 * 唯一匹配的文本替换；不要求模型生成 Diff 行号，保持原始行尾。
 */
@Component
@RequiredArgsConstructor
public class FileEditTool implements SandboxTool {
    private final RepositoryFileResolver files;

    public String name() {
        return "file.edit";
    }

    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        String path = ToolArguments.string(arguments, "path", 1024);
        String hash = ToolArguments.string(arguments, "expectedHash", 64);
        String oldText = ToolArguments.optionalString(arguments, "oldText", "", 256 * 1024);
        if (oldText.isEmpty()) throw new IllegalArgumentException("oldText 不能为空");
        String newText = ToolArguments.optionalString(arguments, "newText", "", 256 * 1024);
        Object replace = arguments.getOrDefault("replaceAll", false);
        if (!(replace instanceof Boolean replaceAll))
            throw new IllegalArgumentException("replaceAll 必须是布尔值");
        var target = files.resolveExisting(context.getLocalWorkspace(), path);
        try {
            if (Files.size(target) > 2 * 1024 * 1024)
                throw new IllegalArgumentException("编辑文件超过 2MiB");
            byte[] bytes = Files.readAllBytes(target);
            if (!FileReadTool.sha256(bytes).equalsIgnoreCase(hash))
                throw new SandboxException(CONFLICT, "FILE_HASH_MISMATCH", "文件已变化，请重新读取后修改");
            String before = Files.readString(target, StandardCharsets.UTF_8);
            // 模型读取窗口中使用 LF，编辑 CRLF 文件时恢复磁盘上的换行。
            if ("CRLF".equals(FileReadTool.detectNewlineStyle(bytes))) {
                oldText = oldText.replace("\r\n", "\n").replace("\n", "\r\n");
                newText = newText.replace("\r\n", "\n").replace("\n", "\r\n");
            }
            int position = before.indexOf(oldText);
            if (position < 0)
                throw new SandboxException(CONFLICT, "EDIT_TEXT_NOT_FOUND", "旧文本未匹配，请重新读取并提供准确文本");
            if (!replaceAll && before.indexOf(oldText, position + oldText.length()) >= 0)
                throw new SandboxException(
                    CONFLICT, "EDIT_TEXT_AMBIGUOUS", "旧文本匹配多处，请增加上下文或显式使用 replaceAll");
            String after =
                replaceAll
                    ? before.replace(oldText, newText)
                    : before.substring(0, position)
                      + newText
                      + before.substring(position + oldText.length());
            byte[] next = after.getBytes(StandardCharsets.UTF_8);
            if (!before.equals(after)) FileWriteTool.atomicReplace(target, next);
            return ToolResult.value(
                Map.of(
                    "path",
                    path,
                    "sha256",
                    FileReadTool.sha256(next),
                    "changed",
                    !before.equals(after),
                    "oldText",
                    oldText,
                    "newText",
                    newText));
        } catch (SandboxException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("编辑文件失败：" + exception.getMessage(), exception);
        }
    }
}
