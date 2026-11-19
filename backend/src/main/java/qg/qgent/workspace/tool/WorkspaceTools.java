package qg.qgent.workspace.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import qg.qgent.harness.tool.ParallelTool;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.sandbox.service.SandboxCommands;
import qg.qgent.workspace.entity.WorkspaceEntity;
import qg.qgent.workspace.service.WorkSession;
import qg.qgent.workspace.service.WorkspaceRepositoryService;
import qg.qgent.workspace.service.WorkspaceSelection;
import qg.qgent.workspace.service.WorkspaceService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作区选择、仓库准备与命令工具；Harness 提供当前对话的状态和按需环境获取。
 */
@Slf4j
public final class WorkspaceTools {
    private final Long actor;
    private final Long projectId;
    private final WorkspaceService workspaces;
    private final WorkspaceRepositoryService repositories;
    private final SandboxCommands workspaceCommands;
    private final WorkSession session;

    public WorkspaceTools(
        Long actor,
        Long projectId,
        WorkspaceService workspaces,
        WorkspaceRepositoryService repositories,
        SandboxCommands commands,
        WorkSession session) {
        this.actor = actor;
        this.projectId = projectId;
        this.workspaces = workspaces;
        this.repositories = repositories;
        this.workspaceCommands = commands;
        this.session = session;
    }

    @ParallelTool
    @Tool(name = "list_workspaces", description = "列出当前项目的 Workspace 及是否默认、状态和当前选中项；不启动沙箱。")
    public ToolResult listWorkspaces() {
        Long current = session.workspaceId();
        ToolResult result = ToolResult.ok();
        // 首次运行尚未选择 Workspace，按合同显式返回 null；Map.of 不接受 null 值
        result.put("currentWorkspaceId", current == null ? null : current.toString());
        result.put(
            "workspaces",
            workspaces.list(projectId).stream()
                .map(workspace ->
                    Map.of(
                        "workspaceId",
                        workspace.getId().toString(),
                        "isDefault",
                        Boolean.TRUE.equals(workspace.getIsDefault()),
                        "status",
                        workspace.getStatus(),
                        "selected",
                        workspace.getId().equals(current)))
                .toList());
        return result;
    }

    @ParallelTool
    @Tool(
        name = "list_workspace_repositories",
        description =
            "列出指定 Workspace（省略时用当前 Workspace）实际已准备的仓库：标识、名称、路径、当前分支与提交、状态。"
                + "项目已导入但未准备的仓库不会出现在这里；由命令创建的其他 Git 仓库以 managed=false 如实列出。不启动沙箱。")
    public ToolResult listWorkspaceRepositories(
        @ToolParam(description = "Workspace ID；省略时使用当前 Workspace", required = false)
        String workspaceId) {
        WorkspaceEntity workspace = workspace(workspaceId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (var repository : repositories.list(projectId, workspace)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("providerRepositoryId", repository.providerRepositoryId());
            item.put("name", repository.name());
            item.put("path", repository.path());
            item.put("branch", repository.branch());
            item.put("head", repository.head());
            item.put("state", repository.state());
            item.put("managed", repository.managed());
            item.put("imported", repository.imported());
            items.add(item);
        }
        ToolResult result = ToolResult.ok();
        result.put("workspaceId", workspace.getId().toString());
        result.put("repositories", items);
        return result;
    }

    @Tool(
        name = "create_workspace",
        description =
            "仅在用户明确要求隔离工作时新建 Workspace，并为任务涉及的仓库准备 worktree 和独立分支。"
                + "instruction 写明用户已确定的任务意图；repositories 至少一个，每项为 {providerRepositoryId, base?, branch?}；"
                + "providerRepositoryId 必须是 GitHub 数字 ID 的十进制字符串，例如 \"42\"，不能传 JSON 数字；"
                + "base 是用户明确指定的基线分支或提交，省略时默认 Workspace 已准备该仓库则用其 HEAD，否则用远端默认分支。"
                + "不会自动 commit、push 或创建 PR；返回每个仓库的路径、基线 commit、当前 head 和分支，须在回复中说明。")
    public ToolResult createWorkspace(
        @ToolParam(description = "用户已确定的任务意图") String instruction,
        @ToolParam(description = "任务涉及的仓库，至少一个") List<Map<String, Object>> repositories) {
        if (instruction == null || instruction.isBlank())
            throw new IllegalArgumentException("任务意图不能为空");
        if (repositories == null || repositories.isEmpty())
            throw new IllegalArgumentException("至少指定一个仓库");
        // 先校验全部条目，避免创建工作区、启动沙箱后才发现输入不合法
        List<RepositoryRequest> requests = repositoryRequests(repositories);
        WorkspaceEntity workspace = workspaces.create(projectId);
        try {
            session.ensure(workspace.getId());
        } catch (RuntimeException exception) {
            log.error("工作区环境初始化失败 workspaceId={}", workspace.getId(), exception);
            return creation(
                workspace,
                ToolResult.error("WORKSPACE_ENVIRONMENT_FAILED", describe(exception)),
                requests.stream().map(this::notStarted).toList());
        }
        session.select(workspace.getId());
        List<Map<String, Object>> items = new ArrayList<>();
        boolean ready = true;
        for (int index = 0; index < requests.size(); index++) {
            RepositoryRequest request = requests.get(index);
            try {
                var preparation =
                    this.repositories.prepare(
                        actor,
                        projectId,
                        workspace,
                        request.id(),
                        request.base(),
                        request.branch());
                items.add(item(preparation));
                ready &= "READY".equals(preparation.state());
            } catch (RuntimeException exception) {
                // 非预期内部错误：保留已完成结果，其余如实标为未开始后停止
                log.error(
                    "准备仓库失败 workspaceId={} repositoryId={}",
                    workspace.getId(),
                    request.id(),
                    exception);
                items.add(failedItem(request.id(), describe(exception)));
                for (RepositoryRequest remaining : requests.subList(index + 1, requests.size()))
                    items.add(notStarted(remaining));
                return creation(
                    workspace,
                    ToolResult.error(
                        "WORKSPACE_REPOSITORY_PREPARATION_FAILED", describe(exception)),
                    items);
            }
        }
        return creation(
            workspace,
            ready
                ? ToolResult.ok()
                : ToolResult.error(
                "WORKSPACE_REPOSITORY_NOT_READY",
                "部分仓库未准备完成，见 repositories 中的 state 与 error"),
            items);
    }

    @Tool(
        name = "prepare_workspace_repository",
        description =
            "为 Workspace（省略时用当前 Workspace）按需准备一个已导入仓库的 worktree。"
                + "已存在的工作树直接复用，不重置或清理已有修改，返回原基线 commit、当前 head，并在指定 base/branch 未生效时给出 note；"
                + "base 省略时默认 Workspace 已准备该仓库则用其 HEAD，否则用远端默认分支。"
                + "分支已被其他工作树检出、或分支已存在但没有工作树时失败，不覆盖不重置。"
                + "仓库已从项目移除导入时失败，但本地工作树保留。不会自动 commit、push 或创建 PR。")
    public ToolResult prepareWorkspaceRepository(
        @ToolParam(description = "Workspace ID；省略时使用当前 Workspace", required = false)
        String workspaceId,
        @ToolParam(description = "GitHub 仓库数字 ID 的十进制字符串") String providerRepositoryId,
        @ToolParam(description = "基线分支或提交；省略时按默认规则", required = false) String base,
        @ToolParam(description = "工作分支名；省略时自动生成", required = false) String branch) {
        Long id = requiredResourceId(providerRepositoryId);
        WorkspaceEntity workspace = workspace(workspaceId);
        session.ensure(workspace.getId());
        var result = repositories.prepare(actor, projectId, workspace, id, base, branch);
        ToolResult value =
            "READY".equals(result.state())
                ? ToolResult.ok()
                : ToolResult.error("WORKSPACE_REPOSITORY_NOT_READY", result.error());
        value.put("workspaceId", workspace.getId().toString());
        value.put("providerRepositoryId", result.providerRepositoryId());
        value.put("path", result.path());
        value.put("baseCommit", result.baseCommit());
        value.put("head", result.head());
        value.put("branch", result.branch());
        value.put("state", result.state());
        value.put("note", result.note());
        return value;
    }

    @Tool(name = "use_workspace", description = "选择当前项目已有 Workspace，后续代码工具使用该工作区并重置当前目录；不会启动沙箱。")
    public ToolResult useWorkspace(@ToolParam(description = "Workspace ID") String workspaceId) {
        Long id = workspaces.require(projectId, requiredResourceId(workspaceId)).getId();
        session.select(id);
        ToolResult result = ToolResult.ok();
        result.put("workspaceId", id.toString());
        return result;
    }

    @Tool(
        name = "set_working_directory",
        description =
            "设置当前 Agent 在已选 Workspace 中的工作目录，路径相对 Workspace 根；传 . 返回根目录。每个 Agent 的目录独立保存。")
    public ToolResult setWorkingDirectory(
        @ToolParam(description = "相对 Workspace 根目录的路径；. 表示根目录") String directory) {
        String resolved = WorkspaceSelection.resolveDirectory(".", directory);
        session.setDirectory(resolved);
        ToolResult result = ToolResult.ok();
        result.put("directory", resolved);
        return result;
    }

    @Tool(
        name = "run_command",
        description =
            "在当前目录的 Docker 沙箱中执行 argv 数组。短等待后返回句柄；running=true 时用 command_output 继续等待。")
    public ToolResult runCommand(
        @ToolParam(description = "可执行文件和参数，例如 [git,status]") List<String> command,
        @ToolParam(description = "相对当前目录的工作目录，可选") String directory,
        @ToolParam(description = "执行超时秒数，1～3600，默认 600") Integer timeoutSeconds,
        @ToolParam(description = "本次等待秒数，0～30，默认 10") Integer waitSeconds) {
        if (command == null || command.isEmpty()) throw new IllegalArgumentException("命令不能为空");
        String cwd = WorkspaceSelection.resolveDirectory(session.directory(), directory);
        return workspaceCommands.start(
            session.ensure(),
            cwd,
            command,
            bounded(timeoutSeconds, 600, 1, 3600),
            bounded(waitSeconds, 10, 0, 30));
    }

    @ParallelTool
    @Tool(name = "command_output", description = "读取命令的有界输出，可短暂等待；running=true 时使用返回的游标继续读取。")
    public ToolResult commandOutput(
        @ToolParam(description = "run_command 返回的执行 ID") String executionId,
        @ToolParam(description = "stdout 字节游标，默认 0") Long stdoutOffset,
        @ToolParam(description = "stderr 字节游标，默认 0") Long stderrOffset,
        @ToolParam(description = "每个流最多读取字节，4～64000，默认 16000") Integer maxBytes,
        @ToolParam(description = "等待秒数，0～30，默认 10") Integer waitSeconds) {
        return workspaceCommands.output(
            session.requireInitialized(),
            requiredResourceId(executionId),
            offset(stdoutOffset),
            offset(stderrOffset),
            bounded(maxBytes, 16000, 4, 64000),
            bounded(waitSeconds, 10, 0, 30));
    }

    @Tool(name = "cancel_command", description = "停止当前 Workspace 中正在运行的命令。")
    public ToolResult cancelCommand(@ToolParam(description = "命令执行 ID") String executionId) {
        return workspaceCommands.cancel(
            session.requireInitialized(), requiredResourceId(executionId));
    }

    /**
     * 未指定时使用当前选择，其次使用项目默认 Workspace。
     */
    private WorkspaceEntity workspace(String workspaceId) {
        if (workspaceId != null && !workspaceId.isBlank()) {
            return workspaces.require(projectId, requiredResourceId(workspaceId));
        }
        Long selected = session.workspaceId();
        return selected == null
            ? workspaces.defaultWorkspace(projectId)
            : workspaces.require(projectId, selected);
    }

    private List<RepositoryRequest> repositoryRequests(List<Map<String, Object>> repositories) {
        List<RepositoryRequest> requests = new ArrayList<>();
        for (Map<String, Object> request : repositories) {
            if (request == null) throw new IllegalArgumentException("仓库条目不能为空");
            Object id = request.get("providerRepositoryId");
            if (!(id instanceof String text)) {
                throw new IllegalArgumentException("providerRepositoryId 必须是十进制字符串 ID，例如 \"42\"");
            }
            requests.add(
                new RepositoryRequest(
                    requiredResourceId(text),
                    optionalString(request, "base"),
                    optionalString(request, "branch")));
        }
        return requests;
    }

    private String optionalString(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value == null) return null;
        if (!(value instanceof String text)) throw new IllegalArgumentException(key + " 必须是字符串");
        return text.isBlank() ? null : text;
    }

    private ToolResult creation(
        WorkspaceEntity workspace, ToolResult result, List<Map<String, Object>> items) {
        result.put("workspaceId", workspace.getId().toString());
        result.put("repositories", items);
        return result;
    }

    private Map<String, Object> item(WorkspaceRepositoryService.Preparation preparation) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("providerRepositoryId", preparation.providerRepositoryId());
        item.put("path", preparation.path());
        item.put("baseCommit", preparation.baseCommit());
        item.put("head", preparation.head());
        item.put("branch", preparation.branch());
        item.put("state", preparation.state());
        item.put("error", preparation.error());
        item.put("note", preparation.note());
        return item;
    }

    private Map<String, Object> notStarted(RepositoryRequest request) {
        return blankItem(request.id().toString(), WorkspaceRepositoryService.NOT_STARTED, null);
    }

    private Map<String, Object> failedItem(Long providerRepositoryId, String error) {
        return blankItem(providerRepositoryId.toString(), "FAILED", error);
    }

    private Map<String, Object> blankItem(String providerRepositoryId, String state, String error) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("providerRepositoryId", providerRepositoryId);
        item.put("path", null);
        item.put("baseCommit", null);
        item.put("head", null);
        item.put("branch", null);
        item.put("state", state);
        item.put("error", error);
        item.put("note", null);
        return item;
    }

    private String describe(RuntimeException exception) {
        return exception.getMessage() == null ? "工作区环境初始化失败" : exception.getMessage();
    }

    private record RepositoryRequest(Long id, String base, String branch) {
    }

    private Long requiredResourceId(String value) {
        try {
            return Long.valueOf(value == null ? "" : value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("资源 ID 无效");
        }
    }

    private int bounded(Integer value, int fallback, int min, int max) {
        int result = value == null ? fallback : value;
        if (result < min || result > max)
            throw new IllegalArgumentException("参数超出范围：" + min + "～" + max);
        return result;
    }

    private long offset(Long value) {
        if (value != null && value < 0) throw new IllegalArgumentException("输出游标不能小于 0");
        return value == null ? 0 : value;
    }
}
