package qg.qgent.workspace.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 单个 Agent 对话的工作区和目录选择；不负责创建或释放执行环境。
 */
public final class WorkspaceSelection {
    private Long workspaceId;
    private String directory;

    public WorkspaceSelection(Long workspaceId, String directory) {
        this.workspaceId = workspaceId;
        this.directory = resolveDirectory(".", directory);
    }

    public synchronized Long workspaceId() {
        return workspaceId;
    }

    public synchronized String directory() {
        return directory;
    }

    public synchronized void bindDefault(Long workspaceId) {
        if (this.workspaceId == null) this.workspaceId = workspaceId;
    }

    public synchronized void select(Long workspaceId) {
        if (!Objects.equals(this.workspaceId, workspaceId)) directory = ".";
        this.workspaceId = workspaceId;
    }

    public synchronized void setDirectory(String directory) {
        this.directory = resolveDirectory(".", directory);
    }

    public static String resolveDirectory(String base, String directory) {
        String input =
            directory == null || directory.isBlank()
                ? "."
                : directory.trim().replace('\\', '/');
        if (input.startsWith("/") || input.matches("^[A-Za-z]:/.*") || input.indexOf('\0') >= 0)
            throw new IllegalArgumentException("工作目录必须位于 Workspace 内");
        List<String> parts = new ArrayList<>();
        for (String item : (base == null ? "." : base).split("/")) {
            if (!item.isBlank() && !".".equals(item)) parts.add(item);
        }
        for (String item : input.split("/")) {
            if (item.isBlank() || ".".equals(item)) continue;
            if ("..".equals(item)) {
                if (parts.isEmpty()) throw new IllegalArgumentException("工作目录必须位于 Workspace 内");
                parts.remove(parts.size() - 1);
            } else parts.add(item);
        }
        return parts.isEmpty() ? "." : String.join("/", parts);
    }
}
