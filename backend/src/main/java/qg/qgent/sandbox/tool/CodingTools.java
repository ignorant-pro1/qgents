package qg.qgent.sandbox.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import qg.qgent.harness.tool.ParallelTool;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.sandbox.service.SandboxFiles;
import qg.qgent.workspace.service.WorkSession;
import qg.qgent.workspace.service.WorkspaceSelection;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 面向模型的文件工具；读取版本留在内部，错误与真实结果回给 loop。
 */
public class CodingTools {
    private final WorkSession session;
    private final SandboxFiles files;
    private final Map<String, String> hashes = new ConcurrentHashMap<>();
    private Long selected;

    public CodingTools(WorkSession session, SandboxFiles files) {
        this.session = session;
        this.files = files;
    }

    @ParallelTool
    @Tool(
        name = "glob_files",
        description =
            "按 glob 发现工作区文件，包含隐藏配置，排除版本库元数据和常见构建目录。结果有上限；truncated=true 时缩小 path 或 glob。")
    public ToolResult globFiles(
        @ToolParam(description = "工作区相对路径，默认根目录", required = false) String path,
        @ToolParam(description = "例如 **/*.java，默认 **", required = false) String glob,
        @ToolParam(description = "返回数量，1～1000，默认 200", required = false) Integer limit) {
        return files.listFiles(
            workspaceId(), resolve(path), glob == null ? "**" : glob, number(limit, 200, 1000));
    }

    @ParallelTool
    @Tool(
        name = "read_file",
        description = "按行读取 UTF-8 文件，返回行号、总行数和截断信息。用 nextLine 继续读取。修改前先读取；文件版本由系统保存，无需复制哈希。")
    public ToolResult readFile(
        @ToolParam(description = "工作区相对文件路径") String path,
        @ToolParam(description = "起始行号，从 1 开始，默认 1", required = false) Integer startLine,
        @ToolParam(description = "最多返回行数，1～1000，默认 200", required = false) Integer lineCount) {
        path = resolveFile(path);
        ToolResult result =
            files.readFile(
                workspaceId(),
                path,
                number(startLine, 1, Integer.MAX_VALUE),
                number(lineCount, 200, 1000));
        if (Boolean.TRUE.equals(result.get("ok"))) hashes.put(path, (String) result.get("sha256"));
        return result;
    }

    @ParallelTool
    @Tool(
        name = "search_code",
        description = "用 ripgrep 正则搜索文件正文，返回路径、行号和命中行。无匹配是成功的空结果，失败返回错误。truncated=true 时缩小范围。")
    public ToolResult searchCode(
        @ToolParam(description = "ripgrep 正则表达式") String pattern,
        @ToolParam(description = "工作区相对文件或目录，默认根目录", required = false) String path,
        @ToolParam(description = "文件 glob，默认 **", required = false) String glob,
        @ToolParam(description = "最多命中数量，1～1000，默认 100", required = false) Integer limit) {
        if (pattern == null || pattern.isBlank())
            throw new IllegalArgumentException("pattern 不能为空");
        return files.searchCode(
            workspaceId(),
            pattern,
            resolve(path),
            glob == null ? "**" : glob,
            number(limit, 100, 1000));
    }

    @Tool(
        name = "write_file",
        description =
            "写入完整 UTF-8 文件，自动创建父目录。新文件直接创建；已有文件必须先 read_file，使用已读取版本替换。已有未读取文件会拒绝，避免意外覆盖。")
    public ToolResult writeFile(
        @ToolParam(description = "工作区相对文件路径") String path,
        @ToolParam(description = "完整文件内容，允许空文本，最多 256KB") String content) {
        path = resolveFile(path);
        if (content == null) throw new IllegalArgumentException("content 不能为 null");
        Long id = workspaceId();
        String hash = hashes.get(path);
        return outcome(
            path,
            hash == null
                ? files.writeFile(id, path, content)
                : files.replaceFile(id, path, hash, content));
    }

    @Tool(
        name = "edit_file",
        description =
            "替换已有 UTF-8 文件的准确文本。先 read_file；默认 oldText 必须只匹配一处，多处时增加上下文或显式 replaceAll。newText 为空表示删除匹配文本。")
    public ToolResult editFile(
        @ToolParam(description = "工作区相对文件路径") String path,
        @ToolParam(description = "准确旧文本，可包含多行上下文") String oldText,
        @ToolParam(description = "替换文本，允许空字符串") String newText,
        @ToolParam(description = "是否替换所有匹配，默认 false", required = false) Boolean replaceAll) {
        path = resolveFile(path);
        Long id = workspaceId();
        if (oldText == null || oldText.isEmpty() || newText == null)
            throw new IllegalArgumentException("oldText 不能为空，newText 不能为 null");
        String hash = hashes.get(path);
        if (hash == null) return ToolResult.error("FILE_NOT_READ", "请先 read_file 再编辑：" + path);
        return outcome(
            path,
            files.editFile(id, path, hash, oldText, newText, Boolean.TRUE.equals(replaceAll)));
    }

    @Tool(
        name = "apply_patch",
        description = "对已读取文件应用 unified diff。文件版本由系统保存；补丁必须精确匹配，失败后重新读取或改用 edit_file。")
    public ToolResult applyPatch(
        @ToolParam(description = "工作区相对文件路径") String path,
        @ToolParam(description = "统一 Diff 文本") String patch) {
        path = resolveFile(path);
        Long id = workspaceId();
        String hash = hashes.get(path);
        if (hash == null) return ToolResult.error("FILE_NOT_READ", "请先 read_file 再应用补丁：" + path);
        if (patch == null || patch.isBlank()) throw new IllegalArgumentException("patch 不能为空");
        return outcome(path, files.patchFile(id, path, hash, patch));
    }

    private ToolResult outcome(String path, ToolResult result) {
        if (Boolean.TRUE.equals(result.get("ok"))) hashes.put(path, (String) result.get("sha256"));
        return result;
    }

    private synchronized Long workspaceId() {
        Long id = session.ensure();
        if (!id.equals(selected)) {
            hashes.clear();
            selected = id;
        }
        return id;
    }

    private String resolve(String value) {
        return WorkspaceSelection.resolveDirectory(session.directory(), value);
    }

    private String resolveFile(String value) {
        String path = resolve(value);
        if (".".equals(path)) throw new IllegalArgumentException("需要工作区内文件路径");
        return path;
    }

    private static int number(Integer value, int fallback, int max) {
        int result = value == null ? fallback : value;
        if (result < 1 || result > max) throw new IllegalArgumentException("数值超出范围：1～" + max);
        return result;
    }
}
