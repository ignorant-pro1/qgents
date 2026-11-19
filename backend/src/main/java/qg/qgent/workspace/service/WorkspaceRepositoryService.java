package qg.qgent.workspace.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import qg.qgent.common.web.ApiException;
import qg.qgent.github.entity.ProjectRepositoryEntity;
import qg.qgent.github.mapper.ProjectRepositoryMapper;
import qg.qgent.github.service.GitHubRepositoryService;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.sandbox.service.SandboxCommands;
import qg.qgent.workspace.entity.WorkspaceEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Stream;

/**
 * 按需为 Workspace 准备仓库工作树。
 * <p>
 * 同一项目同一仓库只有一份 bare Git 数据（{@code .git/<providerRepositoryId>.git}），
 * 每个 Workspace 在其中注册一个 worktree。Git 命令都在沙箱内以规范容器路径执行，
 * 使 worktree 的 {@code .git} 指针和共享元数据在沙箱重启后依然有效；后端只在宿主机上
 * 建目录、读写准备记录并核对 worktree 实际状态。
 */
@Service
@RequiredArgsConstructor
public class WorkspaceRepositoryService {
    /**
     * 容器内项目根的固定路径，与 SandboxBindFactory 的挂载目标一致。
     */
    static final String CONTAINER_PROJECT = "/qgents/project";

    private static final String READY = "READY";
    private static final String MISSING = "MISSING";
    private static final String FAILED = "FAILED";

    /**
     * 创建工作区时尚未开始准备的仓库；只出现在创建结果里。
     */
    public static final String NOT_STARTED = "NOT_STARTED";

    private final ProjectDirectoryService directories;
    private final ProjectRepositoryMapper importedRepositories;
    private final GitHubRepositoryService github;
    private final SandboxCommands commands;
    private final WorkspaceOperationLock locks;
    private final ObjectMapper json;

    /**
     * 仓库标识、展示名或本地身份、工作树路径、当前分支与 HEAD、状态。
     */
    public record RepositoryView(
        String providerRepositoryId,
        String name,
        String path,
        String branch,
        String head,
        String state,
        boolean managed,
        boolean imported) {
    }

    /**
     * 一次准备的结果；失败时 state 为 FAILED 并带 error，不隐藏已完成的部分。
     * 复用既有工作树时 baseCommit 是原创建基线，head 是当前提交，note 说明指定参数是否被忽略。
     */
    public record Preparation(
        String providerRepositoryId,
        String name,
        String path,
        String baseCommit,
        String head,
        String branch,
        String state,
        String error,
        String note) {
    }

    /**
     * 列出 Workspace 内实际存在的仓库，包括由命令创建、不受管理的 Git 仓库。
     */
    public List<RepositoryView> list(Long projectId, WorkspaceEntity workspace) {
        String directory = WorkspaceService.directoryName(workspace);
        Path workspaceRoot = directories.workspace(projectId, directory);
        Map<Long, ProjectRepositoryEntity> imported = imported(projectId);
        Map<Long, State> states = states(projectId, directory);
        List<RepositoryView> result = new ArrayList<>();
        for (State state : states.values()) {
            ProjectRepositoryEntity remote = imported.get(state.providerRepositoryId());
            Path worktree =
                directories.worktree(projectId, directory, state.providerRepositoryId());
            Worktree inspected = inspect(projectId, worktree);
            result.add(
                new RepositoryView(
                    state.providerRepositoryId().toString(),
                    remote == null ? state.name() : remote.getFullName(),
                    relative(workspaceRoot, worktree),
                    inspected.branch(),
                    inspected.head(),
                    inspected.state(),
                    true,
                    remote != null));
        }
        for (Path candidate : untrackedRepositories(workspaceRoot, states.keySet())) {
            Worktree inspected = inspect(projectId, candidate);
            result.add(
                new RepositoryView(
                    null,
                    candidate.getFileName().toString(),
                    relative(workspaceRoot, candidate),
                    inspected.branch(),
                    inspected.head(),
                    inspected.state(),
                    false,
                    false));
        }
        return result;
    }

    /**
     * 为 Workspace 准备一个仓库 worktree。已就绪的工作树直接复用，不重置、不清理修改。
     * 调用方必须先为该 Workspace 取得沙箱会话。
     */
    public Preparation prepare(
        Long actor,
        Long projectId,
        WorkspaceEntity workspace,
        Long providerRepositoryId,
        String base,
        String branch) {
        String directory = WorkspaceService.directoryName(workspace);
        String path =
            relative(
                directories.workspace(projectId, directory),
                directories.worktree(projectId, directory, providerRepositoryId));
        return locks.execute(
            projectId + "/repos/" + providerRepositoryId,
            () -> {
                // 先核对导入关系：导入已移除时不能返回“准备成功”，但也不动本地工作树
                ProjectRepositoryEntity remote = imported(projectId).get(providerRepositoryId);
                if (remote == null) {
                    return failed(
                        providerRepositoryId,
                        providerRepositoryId.toString(),
                        path,
                        "仓库未导入当前项目，无法取得远端授权");
                }
                String name = remote.getFullName();
                Worktree existing =
                    inspect(
                        projectId,
                        directories.worktree(
                            projectId, directory, providerRepositoryId));
                if (READY.equals(existing.state())) {
                    return reused(
                        providerRepositoryId,
                        name,
                        path,
                        existing,
                        states(projectId, directory).get(providerRepositoryId),
                        base,
                        branch);
                }
                try {
                    return prepareLocked(
                        actor,
                        projectId,
                        workspace,
                        directory,
                        providerRepositoryId,
                        remote,
                        base,
                        branch,
                        name,
                        path);
                } catch (PreparationFailure failure) {
                    writeState(
                        projectId,
                        directory,
                        new State(providerRepositoryId, name, path, null, null, FAILED));
                    return failed(providerRepositoryId, name, path, failure.getMessage());
                } catch (ApiException failure) {
                    // 凭证、安装暂停或命令失败：如实记为单仓库失败，不中断同批其他仓库
                    writeState(
                        projectId,
                        directory,
                        new State(providerRepositoryId, name, path, null, null, FAILED));
                    return failed(providerRepositoryId, name, path, failure.getMessage());
                }
            });
    }

    private Preparation reused(
        Long providerRepositoryId,
        String name,
        String path,
        Worktree existing,
        State state,
        String base,
        String branch) {
        String note = null;
        if (state == null) {
            note = "已复用现有工作树，但缺少原准备记录，baseCommit 不可用";
        } else if (base != null && !base.isBlank()) {
            note = "已复用现有工作树，指定的 base 未应用；原基线为 " + state.baseCommit();
        } else if (branch != null && !branch.isBlank() && !branch.equals(existing.branch())) {
            note = "已复用现有工作树，指定的 branch 未应用；当前分支为 " + existing.branch();
        }
        return new Preparation(
            providerRepositoryId.toString(),
            name,
            path,
            state == null ? null : state.baseCommit(),
            existing.head(),
            existing.branch(),
            READY,
            null,
            note);
    }

    private Preparation prepareLocked(
        Long actor,
        Long projectId,
        WorkspaceEntity workspace,
        String directory,
        Long providerRepositoryId,
        ProjectRepositoryEntity remote,
        String base,
        String branch,
        String name,
        String path) {
        Long sandboxWorkspaceId = workspace.getId();
        ensureBareRepository(
            sandboxWorkspaceId,
            projectId,
            providerRepositoryId,
            "https://github.com/" + remote.getFullName() + ".git");
        String token = github.repositoryTokenByProviderId(actor, projectId, providerRepositoryId);
        fetch(sandboxWorkspaceId, projectId, providerRepositoryId, token);
        String baseCommit =
            resolveBase(sandboxWorkspaceId, projectId, providerRepositoryId, remote, base);
        String targetBranch =
            branch == null || branch.isBlank()
                ? generatedBranch(workspace, providerRepositoryId)
                : branch;
        requireBranchAvailable(projectId, providerRepositoryId, targetBranch);
        createWorktree(
            sandboxWorkspaceId,
            projectId,
            directory,
            providerRepositoryId,
            targetBranch,
            baseCommit);
        Worktree created =
            inspect(
                projectId,
                directories.worktree(projectId, directory, providerRepositoryId));
        if (!READY.equals(created.state())) {
            throw new PreparationFailure("worktree 创建后校验失败");
        }
        writeState(
            projectId,
            directory,
            new State(
                providerRepositoryId,
                remote.getFullName(),
                path,
                baseCommit,
                targetBranch,
                READY));
        return new Preparation(
            providerRepositoryId.toString(),
            name,
            path,
            baseCommit,
            created.head(),
            created.branch(),
            READY,
            null,
            null);
    }

    /**
     * 基线优先级：显式指定 > 默认 Workspace 当前 HEAD > 远端默认分支。
     */
    private String resolveBase(
        Long sandboxWorkspaceId,
        Long projectId,
        Long providerRepositoryId,
        ProjectRepositoryEntity remote,
        String base) {
        if (base != null && !base.isBlank()) {
            String reference =
                requireCommit(sandboxWorkspaceId, projectId, providerRepositoryId, base);
            if (reference == null) {
                reference =
                    requireCommit(
                        sandboxWorkspaceId,
                        projectId,
                        providerRepositoryId,
                        "refs/remotes/origin/" + base);
            }
            if (reference == null) throw new PreparationFailure("指定的基线不存在：" + base);
            return reference;
        }
        Worktree defaultWorktree =
            inspect(
                projectId,
                directories.worktree(projectId, "default", providerRepositoryId));
        if (READY.equals(defaultWorktree.state()) && defaultWorktree.head() != null) {
            return defaultWorktree.head();
        }
        if (remote.getDefaultBranch() == null || remote.getDefaultBranch().isBlank()) {
            throw new PreparationFailure("仓库没有默认分支信息，请明确指定基线");
        }
        String reference =
            requireCommit(
                sandboxWorkspaceId,
                projectId,
                providerRepositoryId,
                "refs/remotes/origin/" + remote.getDefaultBranch());
        if (reference == null) throw new PreparationFailure("远端默认分支不可用");
        return reference;
    }

    /**
     * 共享 bare 仓库只在缺少 origin 时补齐；已有配置指向其他仓库时如实报冲突，不覆盖。
     */
    private void ensureBareRepository(
        Long sandboxWorkspaceId, Long projectId, Long providerRepositoryId, String remoteUrl) {
        Path git = directories.repositoryGit(projectId, providerRepositoryId);
        if (!Files.isDirectory(git.resolve("objects"))) {
            run(
                sandboxWorkspaceId,
                List.of(
                    "git",
                    "init",
                    "--bare",
                    "--quiet",
                    containerGit(projectId, providerRepositoryId)),
                Map.of(),
                "无法初始化仓库 Git 数据");
        }
        String origin =
            configValue(
                sandboxWorkspaceId, projectId, providerRepositoryId, "remote.origin.url");
        if (origin == null) {
            run(
                sandboxWorkspaceId,
                List.of(
                    "git",
                    "--git-dir=" + containerGit(projectId, providerRepositoryId),
                    "config",
                    "remote.origin.url",
                    remoteUrl),
                Map.of(),
                "无法配置仓库 origin");
        } else if (!origin.equals(remoteUrl)) {
            throw new PreparationFailure(
                "共享 Git 数据的 origin 与当前导入仓库不一致：" + origin + "；请先处理该配置（例如仓库改名后重新导入）");
        }
        if (configValue(sandboxWorkspaceId, projectId, providerRepositoryId, "remote.origin.fetch")
            == null) {
            run(
                sandboxWorkspaceId,
                List.of(
                    "git",
                    "--git-dir=" + containerGit(projectId, providerRepositoryId),
                    "config",
                    "remote.origin.fetch",
                    "+refs/heads/*:refs/remotes/origin/*"),
                Map.of(),
                "无法配置仓库 origin 的 fetch refspec");
        }
    }

    private void fetch(
        Long sandboxWorkspaceId, Long projectId, Long providerRepositoryId, String token) {
        run(
            sandboxWorkspaceId,
            List.of(
                "git",
                "--git-dir=" + containerGit(projectId, providerRepositoryId),
                "fetch",
                "--quiet",
                "--no-tags",
                "origin",
                "+refs/heads/*:refs/remotes/origin/*"),
            GitHubRepositoryService.gitAuthEnvironment(token),
            "无法从远端获取仓库");
    }

    private String configValue(
        Long sandboxWorkspaceId, Long projectId, Long providerRepositoryId, String key) {
        SandboxCommands.CommandResult result =
            commands.git(
                sandboxWorkspaceId,
                List.of(
                    "git",
                    "--git-dir=" + containerGit(projectId, providerRepositoryId),
                    "config",
                    "--get",
                    key),
                Map.of());
        if (result.exitCode() != 0) return null;
        String value = result.stdout().trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * 解析引用为提交；引用不存在时返回 null。
     */
    private String requireCommit(
        Long sandboxWorkspaceId, Long projectId, Long providerRepositoryId, String reference) {
        SandboxCommands.CommandResult result =
            commands.git(
                sandboxWorkspaceId,
                List.of(
                    "git",
                    "--git-dir=" + containerGit(projectId, providerRepositoryId),
                    "rev-parse",
                    "--verify",
                    "--quiet",
                    reference + "^{commit}"),
                Map.of());
        String sha = result.stdout().trim();
        return result.exitCode() == 0 && !sha.isEmpty() ? sha : null;
    }

    /**
     * 分支被其他工作树检出时拒绝，避免切换或覆盖已有工作成果。
     */
    private void requireBranchAvailable(Long projectId, Long providerRepositoryId, String branch) {
        Path worktrees =
            directories.repositoryGit(projectId, providerRepositoryId).resolve("worktrees");
        if (!Files.isDirectory(worktrees)) {
            return;
        }
        try (Stream<Path> entries = Files.list(worktrees)) {
            for (Path entry : entries.toList()) {
                Path head = entry.resolve("HEAD");
                if (Files.isRegularFile(head)
                    && ("ref: refs/heads/" + branch).equals(Files.readString(head).trim())) {
                    throw new PreparationFailure("分支已被其他工作树检出：" + branch);
                }
            }
        } catch (IOException exception) {
            throw new PreparationFailure("无法检查分支占用：" + branch);
        }
    }

    /**
     * 分支已存在时明确失败：检出旧分支会得到与所选基线不同的代码。
     */
    private void createWorktree(
        Long sandboxWorkspaceId,
        Long projectId,
        String directory,
        Long providerRepositoryId,
        String branch,
        String commit) {
        if (branchExists(sandboxWorkspaceId, projectId, providerRepositoryId, branch)) {
            throw new PreparationFailure("分支已存在且没有对应工作树：" + branch + "；请改用其他分支名，或先删除该分支后重试");
        }
        String target = CONTAINER_PROJECT + "/workspaces/" + directory + "/" + providerRepositoryId;
        run(
            sandboxWorkspaceId,
            List.of(
                "git",
                "--git-dir=" + containerGit(projectId, providerRepositoryId),
                "worktree",
                "add",
                "-b",
                branch,
                target,
                commit),
            Map.of(),
            "无法创建工作树");
    }

    private boolean branchExists(
        Long sandboxWorkspaceId, Long projectId, Long providerRepositoryId, String branch) {
        return requireCommit(
            sandboxWorkspaceId, projectId, providerRepositoryId, "refs/heads/" + branch)
            != null;
    }

    private String generatedBranch(WorkspaceEntity workspace, Long providerRepositoryId) {
        return Boolean.TRUE.equals(workspace.getIsDefault())
            ? "qgents/default-repo-" + providerRepositoryId
            : "qgents/ws-" + workspace.getId() + "-repo-" + providerRepositoryId;
    }

    private String containerGit(Long projectId, Long providerRepositoryId) {
        return CONTAINER_PROJECT + "/.git/" + providerRepositoryId + ".git";
    }

    private String run(
        Long sandboxWorkspaceId,
        List<String> command,
        Map<String, String> environment,
        String message) {
        SandboxCommands.CommandResult result =
            commands.git(sandboxWorkspaceId, command, environment);
        if (result.exitCode() != 0) {
            throw new PreparationFailure(message);
        }
        return result.stdout();
    }

    private Preparation failed(Long providerRepositoryId, String name, String path, String error) {
        return new Preparation(
            providerRepositoryId.toString(), name, path, null, null, null, FAILED, error, null);
    }

    /**
     * 按磁盘上的 .git 指针与共享元数据核对工作树，不依赖曾经成功的命令。
     * 普通 git init/clone 出来的仓库 .git 是目录，元数据就在该目录内。
     */
    private Worktree inspect(Long projectId, Path worktree) {
        if (!Files.isDirectory(worktree)) {
            return new Worktree(null, null, MISSING);
        }
        Path pointer = worktree.resolve(".git");
        if (Files.isDirectory(pointer)) {
            // 刚 init、尚无提交时没有 refs，但仓库存在：head 为 null，branch 仍取 HEAD 的分支名
            return Files.isRegularFile(pointer.resolve("HEAD"))
                ? new Worktree(branch(pointer), readHead(pointer, pointer), READY)
                : new Worktree(null, null, FAILED);
        }
        if (!Files.isRegularFile(pointer)) {
            return new Worktree(null, null, MISSING);
        }
        try {
            String content = Files.readString(pointer).trim();
            if (!content.startsWith("gitdir:")) {
                return new Worktree(null, null, FAILED);
            }
            Path metadata = hostPath(projectId, content.substring("gitdir:".length()).trim());
            if (metadata == null || !Files.isDirectory(metadata)) {
                return new Worktree(null, null, MISSING);
            }
            String head = readHead(metadata, commonDir(metadata));
            return head == null
                ? new Worktree(null, null, FAILED)
                : new Worktree(branch(metadata), head, READY);
        } catch (IOException exception) {
            return new Worktree(null, null, FAILED);
        }
    }

    private Path commonDir(Path metadata) {
        try {
            Path common = metadata.resolve("commondir");
            return Files.isRegularFile(common)
                ? metadata.resolve(Files.readString(common).trim()).normalize()
                : metadata.getParent().getParent();
        } catch (IOException exception) {
            return metadata.getParent().getParent();
        }
    }

    private String branch(Path metadata) {
        try {
            String head = Files.readString(metadata.resolve("HEAD")).trim();
            return head.startsWith("ref: refs/heads/")
                ? head.substring("ref: refs/heads/".length())
                : null;
        } catch (IOException exception) {
            return null;
        }
    }

    /**
     * HEAD 指向的分支提交；分离 HEAD 时直接使用 HEAD 中的提交。linked worktree 的 refs 在共享目录里。
     */
    private String readHead(Path headDir, Path gitDir) {
        try {
            String head = Files.readString(headDir.resolve("HEAD")).trim();
            if (!head.startsWith("ref: ")) {
                return head.matches("[0-9a-f]{40,64}") ? head : null;
            }
            String reference = head.substring("ref: ".length());
            Path refFile = gitDir.resolve(reference);
            if (Files.isRegularFile(refFile)) {
                return Files.readString(refFile).trim();
            }
            Path packed = gitDir.resolve("packed-refs");
            if (Files.isRegularFile(packed)) {
                for (String line : Files.readAllLines(packed)) {
                    if (line.endsWith(" " + reference)) {
                        return line.substring(0, line.indexOf(' '));
                    }
                }
            }
            return null;
        } catch (IOException exception) {
            return null;
        }
    }

    /**
     * 容器内项目路径映射回宿主机路径；不认识的路径返回 null。
     */
    private Path hostPath(Long projectId, String containerPath) {
        String prefix = CONTAINER_PROJECT + "/";
        if (!containerPath.startsWith(prefix)) {
            return null;
        }
        return directories
            .projectRoot(projectId)
            .resolve(containerPath.substring(prefix.length()))
            .normalize();
    }

    private List<Path> untrackedRepositories(Path workspaceRoot, java.util.Set<Long> managed) {
        try (Stream<Path> entries = Files.list(workspaceRoot)) {
            return entries.filter(Files::isDirectory)
                .filter(path -> !managed.contains(parseId(path.getFileName().toString())))
                .filter(path -> Files.exists(path.resolve(".git")))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 Workspace 目录", exception);
        }
    }

    private Long parseId(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Map<Long, ProjectRepositoryEntity> imported(Long projectId) {
        Map<Long, ProjectRepositoryEntity> result = new LinkedHashMap<>();
        for (ProjectRepositoryEntity repository :
            importedRepositories.selectList(
                Wrappers.<ProjectRepositoryEntity>lambdaQuery()
                    .eq(ProjectRepositoryEntity::getProjectId, projectId))) {
            result.put(repository.getProviderRepositoryId(), repository);
        }
        return result;
    }

    private Map<Long, State> states(Long projectId, String directory) {
        Path root =
            directories
                .gitRoot(projectId)
                .resolve("qgents")
                .resolve("workspaces")
                .resolve(directory);
        Map<Long, State> result = new LinkedHashMap<>();
        if (!Files.isDirectory(root)) {
            return result;
        }
        try (Stream<Path> entries = Files.list(root)) {
            for (Path entry : entries.toList()) {
                String fileName = entry.getFileName().toString();
                if (!fileName.endsWith(".json")) continue;
                Long id = parseId(fileName.substring(0, fileName.length() - ".json".length()));
                if (id == null) continue;
                try {
                    result.put(id, json.readValue(entry.toFile(), State.class));
                } catch (IOException exception) {
                    result.put(id, new State(id, id.toString(), null, null, null, FAILED));
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取仓库准备记录", exception);
        }
        return result;
    }

    private void writeState(Long projectId, String directory, State state) {
        Path file = directories.repositoryState(projectId, directory, state.providerRepositoryId());
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), ".state", ".tmp");
            json.writeValue(temp.toFile(), state);
            Files.move(
                temp,
                file,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            throw new IllegalStateException("无法保存仓库准备记录", exception);
        }
    }

    private String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private record State(
        Long providerRepositoryId,
        String name,
        String path,
        String baseCommit,
        String branch,
        String state) {
    }

    private record Worktree(String branch, String head, String state) {
    }

    private static final class PreparationFailure extends RuntimeException {
        PreparationFailure(String message) {
            super(message);
        }
    }
}
