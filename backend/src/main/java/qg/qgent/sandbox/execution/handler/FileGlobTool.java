package qg.qgent.sandbox.execution.handler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import qg.qgent.sandbox.execution.dto.ToolArguments;
import qg.qgent.sandbox.execution.dto.ToolContext;
import qg.qgent.sandbox.execution.dto.ToolResult;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一次遍历发现文件；包含隐藏配置，排除版本库元数据、构建产物和工具输出。
 */
@Component
@RequiredArgsConstructor
public class FileGlobTool implements SandboxTool {
    private final RepositoryFileResolver files;

    public String name() {
        return "file.glob";
    }

    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        String path = ToolArguments.optionalString(arguments, "path", ".", 1024);
        String glob = ToolArguments.optionalString(arguments, "glob", "**", 512);
        int limit = ToolArguments.integer(arguments, "limit", 200, 1, 1000);
        var target = files.resolveExisting(context.getLocalWorkspace(), path);
        var matcher = target.getFileSystem().getPathMatcher("glob:" + glob);
        List<String> matches = new ArrayList<>();
        try {
            Files.walkFileTree(
                target,
                new java.nio.file.SimpleFileVisitor<>() {
                    public java.nio.file.FileVisitResult preVisitDirectory(
                        java.nio.file.Path dir,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                        String name = dir.getFileName().toString();
                        return List.of(
                                ".git",
                                ".svn",
                                ".hg",
                                "node_modules",
                                "target",
                                "build",
                                ".qgents-output")
                            .contains(name)
                            ? java.nio.file.FileVisitResult.SKIP_SUBTREE
                            : java.nio.file.FileVisitResult.CONTINUE;
                    }

                    public java.nio.file.FileVisitResult visitFile(
                        java.nio.file.Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                        if (Thread.currentThread().isInterrupted())
                            throw new IllegalStateException("文件发现已取消");
                        var relative =
                            target.equals(file)
                                ? file.getFileName()
                                : target.relativize(file);
                        if (attrs.isRegularFile()
                            && (matcher.matches(relative)
                            || (glob.startsWith("**/")
                            && target.getFileSystem()
                            .getPathMatcher(
                                "glob:" + glob.substring(3))
                            .matches(relative)))) {
                            matches.add(
                                context.getLocalWorkspace()
                                    .relativize(file)
                                    .toString()
                                    .replace('\\', '/'));
                        }
                        return matches.size() > limit
                            ? java.nio.file.FileVisitResult.TERMINATE
                            : java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
            boolean truncated = matches.size() > limit;
            return ToolResult.value(
                Map.of(
                    "files",
                    matches.stream().limit(limit).sorted().toList(),
                    "truncated",
                    truncated));
        } catch (Exception exception) {
            throw new IllegalStateException("文件发现失败：" + exception.getMessage(), exception);
        }
    }
}
