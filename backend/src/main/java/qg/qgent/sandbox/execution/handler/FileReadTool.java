package qg.qgent.sandbox.execution.handler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import qg.qgent.sandbox.execution.dto.ToolArguments;
import qg.qgent.sandbox.execution.dto.ToolContext;
import qg.qgent.sandbox.execution.dto.ToolResult;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 流式读取 UTF-8 文件，返回有行号的有界窗口和原始字节哈希。
 */
@Component
@RequiredArgsConstructor
public class FileReadTool implements SandboxTool {
    private static final int MAX_WINDOW_BYTES = 64 * 1024;
    private static final int MAX_LINE_CHARS = 4000;
    private final RepositoryFileResolver files;

    public String name() {
        return "file.read";
    }

    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        String path = ToolArguments.string(arguments, "path", 1024);
        int start = ToolArguments.integer(arguments, "startLine", 1, 1, Integer.MAX_VALUE);
        int count = ToolArguments.integer(arguments, "lineCount", 200, 1, 1000);
        Path file = files.resolveExisting(context.getLocalWorkspace(), path);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            boolean hasLf = false, hasCrlf = false;
            int previous = -1;
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int size;
                while ((size = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, size);
                    for (int i = 0; i < size; i++) {
                        int value = buffer[i] & 255;
                        if (value == 10) {
                            hasLf = true;
                            if (previous == 13) hasCrlf = true;
                        }
                        previous = value;
                    }
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                }
            }
            List<Map<String, Object>> lines = new ArrayList<>();
            int total = 0, bytes = 0;
            boolean clipped = false, windowFull = false;
            // 逐字符读取，避免单个超长行先占用无界内存。
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                StringBuilder line = new StringBuilder();
                boolean any = false, lastCr = false, lineClipped = false;
                int value;
                while ((value = reader.read()) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if (value == '\n' && lastCr) {
                        lastCr = false;
                        continue;
                    }
                    lastCr = value == '\r';
                    if (value == '\r' || value == '\n') {
                        total++;
                        if (total >= start && lines.size() < count && !windowFull) {
                            String text = line.toString();
                            int size = text.getBytes(StandardCharsets.UTF_8).length;
                            if (bytes + size > MAX_WINDOW_BYTES) windowFull = true;
                            else {
                                lines.add(
                                    Map.of(
                                        "number",
                                        total,
                                        "text",
                                        text,
                                        "truncated",
                                        lineClipped));
                                bytes += size;
                                clipped |= lineClipped;
                            }
                        }
                        line.setLength(0);
                        any = false;
                        lineClipped = false;
                    } else {
                        any = true;
                        if (line.length() < MAX_LINE_CHARS) line.append((char) value);
                        else lineClipped = true;
                    }
                }
                if (any) {
                    total++;
                    if (total >= start && lines.size() < count && !windowFull) {
                        String text = line.toString();
                        if (bytes + text.getBytes(StandardCharsets.UTF_8).length
                            <= MAX_WINDOW_BYTES) {
                            lines.add(
                                Map.of(
                                    "number",
                                    total,
                                    "text",
                                    text,
                                    "truncated",
                                    lineClipped));
                            clipped |= lineClipped;
                        }
                    }
                }
            }
            int next = start + lines.size();
            return ToolResult.value(
                Map.of(
                    "path",
                    path,
                    "sha256",
                    HexFormat.of().formatHex(digest.digest()),
                    "startLine",
                    start,
                    "totalLines",
                    total,
                    "lines",
                    lines,
                    "truncated",
                    clipped || next <= total,
                    "nextLine",
                    next,
                    "endsWithNewline",
                    previous == 10,
                    "newlineStyle",
                    hasCrlf ? "CRLF" : hasLf ? "LF" : "NONE"));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("读取已取消", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("读取 UTF-8 文件失败：" + exception.getMessage(), exception);
        }
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException("计算文件哈希失败", exception);
        }
    }

    static String detectNewlineStyle(byte[] bytes) {
        boolean lf = false;
        for (int i = 0; i < bytes.length; i++)
            if (bytes[i] == 10) {
                if (i > 0 && bytes[i - 1] == 13) return "CRLF";
                lf = true;
            }
        return lf ? "LF" : "NONE";
    }
}
