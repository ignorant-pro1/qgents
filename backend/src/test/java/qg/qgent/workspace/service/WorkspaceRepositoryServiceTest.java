package qg.qgent.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.github.entity.ProjectRepositoryEntity;
import qg.qgent.github.mapper.ProjectRepositoryMapper;
import qg.qgent.github.service.GitHubRepositoryService;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.sandbox.exception.SandboxException;
import qg.qgent.sandbox.service.SandboxCommands;
import qg.qgent.workspace.entity.WorkspaceEntity;

/** 仓库准备与清单：用真实目录结构模拟沙箱内的 Git 结果，不依赖 Docker。 */
class WorkspaceRepositoryServiceTest {

    @TempDir Path root;

    private final ProjectRepositoryMapper importedRepositories =
            mock(ProjectRepositoryMapper.class);
    private final GitHubRepositoryService github = mock(GitHubRepositoryService.class);
    private final SandboxCommands commands = mock(SandboxCommands.class);
    private final ObjectMapper json = new ObjectMapper();
    private final Map<Path, Map<String, String>> gitConfig = new HashMap<>();
    private final Long projectId = 7L;
    private final Long repositoryId = 42L;
    private final Long actor = 1L;

    @Test
    void preparesWorktreeWithSharedBareRepositoryAndGeneratedBranch() {
        WorkspaceEntity workspace = workspace(11L, false);
        when(importedRepositories.selectList(any())).thenReturn(List.of(imported("main")));
        when(github.repositoryTokenByProviderId(actor, projectId, repositoryId))
                .thenReturn("token");
        fakeGit();

        var result = service().prepare(actor, projectId, workspace, repositoryId, null, null);
        assertThat(result.state()).as(String.valueOf(result.error())).isEqualTo("READY");
        assertThat(result.branch()).isEqualTo("qgents/ws-11-repo-42");
        assertThat(result.baseCommit()).isEqualTo(sha("main"));
        assertThat(result.head()).isEqualTo(sha("main"));
        assertThat(result.note()).isNull();
        assertThat(result.path()).isEqualTo("42");
        assertThat(directories().repositoryGit(projectId, repositoryId).resolve("objects"))
                .exists();
        assertThat(directories().worktree(projectId, "11", repositoryId).resolve(".git")).exists();
        assertThat(directories().repositoryState(projectId, "11", repositoryId)).exists();
    }

    @Test
    void bareRepositoryGetsCredentialFreeOriginSharedByWorktrees() {
        when(importedRepositories.selectList(any())).thenReturn(List.of(imported("main")));
        when(github.repositoryTokenByProviderId(actor, projectId, repositoryId))
                .thenReturn("token");
        fakeGit();

        service().prepare(actor, projectId, workspace(11L, false), repositoryId, null, null);
        service().prepare(actor, projectId, workspace(12L, false), repositoryId, null, null);

        Map<String, String> config =
                gitConfig.get(directories().repositoryGit(projectId, repositoryId));
        assertThat(config)
                .containsEntry("remote.origin.url", "https://github.com/owner/repo.git")
                .containsEntry("remote.origin.fetch", "+refs/heads/*:refs/remotes/origin/*");
        assertThat(config.values()).noneMatch(value -> value.contains("token"));
    }

    @Test
    void originPointingElsewhereIsReportedInsteadOfOverwritten() throws IOException {
        Path git = directories().repositoryGit(projectId, repositoryId);
        Files.createDirectories(git.resolve("objects"));
        gitConfig
                .computeIfAbsent(git, ignored -> new HashMap<>())
                .put("remote.origin.url", "https://github.com/other/repo.git");
        when(importedRepositories.selectList(any())).thenReturn(List.of(imported("main")));
        when(github.repositoryTokenByProviderId(actor, projectId, repositoryId))
                .thenReturn("token");
        fakeGit();

        var result =
                service()
                        .prepare(actor, projectId, workspace(11L, false), repositoryId, null, null);

        assertThat(result.state()).isEqualTo("FAILED");
        assertThat(result.error()).contains("origin");
        assertThat(gitConfig.get(git))
                .containsEntry("remote.origin.url", "https://github.com/other/repo.git");
    }

    @Test
    void defaultWorkspaceUsesDefaultBranchName() {
        WorkspaceEntity workspace = workspace(11L, true);
        when(importedRepositories.selectList(any())).thenReturn(List.of(imported("main")));
        when(github.repositoryTokenByProviderId(actor, projectId, repositoryId))
                .thenReturn("token");
        fakeGit();

        var result = service().prepare(actor, projectId, workspace, repositoryId, null, null);

        assertThat(result.state()).isEqualTo("READY");
        assertThat(result.branch()).isEqualTo("qgents/default-repo-42");
        assertThat(directories().worktree(projectId, "default", repositoryId)).exists();
    }

    @Test
    void existingWorktreeIsReusedWithoutRunningGit() {
        WorkspaceEntity workspace = workspace(11L, false);
        when(importedRepositories.selectList(any())).thenReturn(List.of(imported("main")));
        fakeGit();
        service().prepare(actor, projectId, workspace, repositoryId, null, null);
        org.mockito.Mockito.clearInvocations(commands);

        var result = service().prepare(actor, projectId, workspace, repositoryId, null, null);

        assertThat(result.state()).isEqualTo("READY");
        verify(commands, never()).git(any(), anyList(), anyMap());
    }

    @Test
    void reusedWorktreeReportsOriginalBaseCurrentHeadAndIgnoredArguments() throws IOException {
        WorkspaceEntity workspace = workspace(11L, false);
        when(importedRepositories.selectList(any())).thenReturn(List.of(imported("main")));
        when(github.repositoryTokenByProviderId(actor, projectId, repositoryId))
                .thenReturn("token");
        fakeGit();
        service().prepare(actor, projectId, workspace, repositoryId, null, null);
        // 工作树产生新提交：ref 前进，HEAD 仍指向同一分支
        Path ref =
                directories()
                        .repositoryGit(projectId, repositoryId)
                        .resolve("refs/heads/qgents/ws-11-repo-42");
        Files.writeString(ref, sha("next") + "\n");

        var result = service().prepare(actor, projectId, workspace, repositoryId, "develop", null);

        assertThat(result.state()).isEqualTo("READY");
        assertThat(result.baseCommit()).isEqualTo(sha("main"));
        assertThat(result.head()).isEqualTo(sha("next"));
        assertThat(result.note()).contains("base 未应用");
    }

    @Test
    void reusedWorktreeWithoutStateRecordHasNoBaseCommit() throws IOException {
        WorkspaceEntity workspace = workspace(11L, false);
        when(importedRepositories.selectList(any())).thenReturn(List.of(imported("main")));
        fakeGit();
        service().prepare(actor, projectId, workspace, repositoryId, null, null);
        Files.delete(directories().repositoryState(projectId, "11", repositoryId));

        var result =
                service().prepare(actor, projectId, workspace, repositoryId, null, "other-branch");

        assertThat(result.baseCommit()).isNull();
        assertThat(result.note()).contains("缺少原准备记录");
    }

    @Test
    void existingBranchWithoutWorktreeFailsInsteadOfCheckingItOut() throws IOException {
        WorkspaceEntity workspace = workspace(11L, false);
        when(importedRepositories.selectList(any())).thenReturn(List.of(imported("main")));
        when(github.repositoryTokenByProviderId(actor, projectId, repositoryId))
                .thenReturn("token");
        fakeGit();
        Path git = directories().repositoryGit(projectId, repositoryId);
        Files.createDirectories(git.resolve("objects"));
        Files.createDirectories(git.resolve("refs/heads"));
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/main\n");
        Path branch = git.resolve("refs/heads/qgents/ws-11-repo-42");
        Files.createDirectories(branch.getParent());
        Files.writeString(branch, sha("old") + "\n");

        var result = service().prepare(actor, projectId, workspace, repositoryId, null, null);

        assertThat(result.state()).isEqualTo("FAILED");
        assertThat(result.error()).contains("分支已存在");
        assertThat(Files.readString(branch)).isEqualTo(sha("old") + "\n");
        assertThat(directories().worktree(projectId, "11", repositoryId)).doesNotExist();
    }

    @Test
    void unimportedRepositoryFailsWithoutTouchingDisk() {
        WorkspaceEntity workspace = workspace(11L, false);
        when(importedRepositories.selectList(any())).thenReturn(List.of());

        var result = service().prepare(actor, projectId, workspace, repositoryId, null, null);

        assertThat(result.state()).isEqualTo("FAILED");
        assertThat(result.error()).contains("未导入");
        verify(commands, never()).git(any(), anyList(), anyMap());
        assertThat(root.resolve(projectId + "/workspaces/11/42/.git")).doesNotExist();
    }

    @Test
    void revokedImportFailsEvenWhenWorktreeStillExists() {
        WorkspaceEntity workspace = workspace(11L, false);
        when(importedRepositories.selectList(any())).thenReturn(List.of(imported("main")));
        when(github.repositoryTokenByProviderId(actor, projectId, repositoryId))
                .thenReturn("token");
        fakeGit();
        service().prepare(actor, projectId, workspace, repositoryId, null, null);
        when(importedRepositories.selectList(any())).thenReturn(List.of());

        var result = service().prepare(actor, projectId, workspace, repositoryId, null, null);

        assertThat(result.state()).isEqualTo("FAILED");
        assertThat(result.error()).contains("未导入");
        assertThat(directories().worktree(projectId, "11", repositoryId)).exists();
        var listed =
                service().list(projectId, workspace).stream()
                        .filter(item -> "42".equals(item.providerRepositoryId()))
                        .findFirst()
                        .orElseThrow();
        assertThat(listed.imported()).isFalse();
        assertThat(listed.state()).isEqualTo("READY");
    }

    @Test
    void credentialFailureBecomesSingleRepositoryFailure() {
        when(importedRepositories.selectList(any())).thenReturn(List.of(imported("main")));
        when(github.repositoryTokenByProviderId(actor, projectId, repositoryId))
                .thenThrow(
                        new SandboxException(
                                org.springframework.http.HttpStatus.CONFLICT,
                                "INSTALLATION_SUSPENDED",
                                "GitHub App 安装已暂停"));
        fakeGit();

        var result =
                service()
                        .prepare(actor, projectId, workspace(11L, false), repositoryId, null, null);

        assertThat(result.state()).isEqualTo("FAILED");
        assertThat(result.error()).contains("安装已暂停");
        assertThat(directories().repositoryGit(projectId, repositoryId).resolve("objects"))
                .exists();
        assertThat(directories().worktree(projectId, "11", repositoryId)).doesNotExist();
    }

    @Test
    void listDistinguishesReadyMissingAndUntrackedRepositories() throws IOException {
        WorkspaceEntity workspace = workspace(11L, false);
        when(importedRepositories.selectList(any())).thenReturn(List.of(imported("main")));
        when(github.repositoryTokenByProviderId(actor, projectId, repositoryId))
                .thenReturn("token");
        fakeGit();
        service().prepare(actor, projectId, workspace, repositoryId, null, null);
        // 第二个仓库只剩准备记录，工作树已被删除
        directories().worktree(projectId, "11", 43L);
        writeState("11", 43L, "owner/gone", "43", "qgents/ws-11-repo-43");
        // 第三个仓库由命令创建，不受管理
        Path manual = directories().workspace(projectId, "11").resolve("manual");
        Files.createDirectories(manual);
        Files.writeString(manual.resolve(".git"), "gitdir: /elsewhere\n");

        var list = service().list(projectId, workspace);

        assertThat(list)
                .extracting(WorkspaceRepositoryService.RepositoryView::providerRepositoryId)
                .containsExactlyInAnyOrder("42", "43", null);
        var ready =
                list.stream()
                        .filter(item -> "42".equals(item.providerRepositoryId()))
                        .findFirst()
                        .orElseThrow();
        assertThat(ready.state()).isEqualTo("READY");
        assertThat(ready.branch()).isEqualTo("qgents/ws-11-repo-42");
        assertThat(ready.imported()).isTrue();
        var missing =
                list.stream()
                        .filter(item -> "43".equals(item.providerRepositoryId()))
                        .findFirst()
                        .orElseThrow();
        assertThat(missing.state()).isEqualTo("MISSING");
        var untracked =
                list.stream()
                        .filter(item -> item.providerRepositoryId() == null)
                        .findFirst()
                        .orElseThrow();
        assertThat(untracked.name()).isEqualTo("manual");
        assertThat(untracked.managed()).isFalse();
        assertThat(untracked.imported()).isFalse();
    }

    @Test
    void plainGitRepositoryIsListedWithRealHeadInsteadOfMissing() throws IOException {
        WorkspaceEntity workspace = workspace(11L, false);
        when(importedRepositories.selectList(any())).thenReturn(List.of());
        Path plain = directories().workspace(projectId, "11").resolve("plain");
        Path git = plain.resolve(".git");
        Files.createDirectories(git.resolve("refs/heads"));
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/main\n");
        Files.writeString(git.resolve("refs/heads/main"), sha("plain") + "\n");

        var listed =
                service().list(projectId, workspace).stream()
                        .filter(item -> "plain".equals(item.name()))
                        .findFirst()
                        .orElseThrow();

        assertThat(listed.state()).isEqualTo("READY");
        assertThat(listed.branch()).isEqualTo("main");
        assertThat(listed.head()).isEqualTo(sha("plain"));
        assertThat(listed.managed()).isFalse();
    }

    @Test
    void freshRepositoryWithoutCommitsStillExists() throws IOException {
        WorkspaceEntity workspace = workspace(11L, false);
        when(importedRepositories.selectList(any())).thenReturn(List.of());
        Path git = directories().workspace(projectId, "11").resolve("fresh").resolve(".git");
        Files.createDirectories(git);
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/main\n");

        var listed =
                service().list(projectId, workspace).stream()
                        .filter(item -> "fresh".equals(item.name()))
                        .findFirst()
                        .orElseThrow();

        assertThat(listed.state()).isEqualTo("READY");
        assertThat(listed.branch()).isEqualTo("main");
        assertThat(listed.head()).isNull();
    }

    @Test
    void brokenGitMetadataIsReportedAsFailed() throws IOException {
        WorkspaceEntity workspace = workspace(11L, false);
        when(importedRepositories.selectList(any())).thenReturn(List.of());
        Files.createDirectories(directories().workspace(projectId, "11").resolve("broken/.git"));

        var listed =
                service().list(projectId, workspace).stream()
                        .filter(item -> "broken".equals(item.name()))
                        .findFirst()
                        .orElseThrow();

        assertThat(listed.state()).isEqualTo("FAILED");
    }

    @Test
    void detachedHeadKeepsCommitWithoutBranch() throws IOException {
        WorkspaceEntity workspace = workspace(11L, false);
        when(importedRepositories.selectList(any())).thenReturn(List.of());
        Path git = directories().workspace(projectId, "11").resolve("detached").resolve(".git");
        Files.createDirectories(git);
        Files.writeString(git.resolve("HEAD"), sha("detached") + "\n");

        var listed =
                service().list(projectId, workspace).stream()
                        .filter(item -> "detached".equals(item.name()))
                        .findFirst()
                        .orElseThrow();

        assertThat(listed.state()).isEqualTo("READY");
        assertThat(listed.branch()).isNull();
        assertThat(listed.head()).isEqualTo(sha("detached"));
    }

    @Test
    void repositoriesOfOtherProjectsAreNotListed() {
        WorkspaceEntity workspace = workspace(11L, false);
        when(importedRepositories.selectList(any())).thenReturn(List.of());

        assertThat(service().list(projectId, workspace)).isEmpty();
        verify(importedRepositories).selectList(any());
    }

    private WorkspaceRepositoryService service() {
        return new WorkspaceRepositoryService(
                directories(), importedRepositories, github, commands, lock(), json);
    }

    private WorkspaceOperationLock lock() {
        var properties = new qg.qgent.sandbox.config.SandboxProperties();
        properties.setWorkspaceMetadataRoot(root.resolve("metadata").toString());
        return new WorkspaceOperationLock(properties);
    }

    private ProjectDirectoryService directories() {
        return new ProjectDirectoryService(root.toString(), root.toString());
    }

    private WorkspaceEntity workspace(Long id, boolean isDefault) {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(id);
        workspace.setProjectId(projectId);
        workspace.setIsDefault(isDefault);
        workspace.setStatus("READY");
        return workspace;
    }

    private ProjectRepositoryEntity imported(String defaultBranch) {
        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setProjectId(projectId);
        repository.setProviderRepositoryId(repositoryId);
        repository.setFullName("owner/repo");
        repository.setDefaultBranch(defaultBranch);
        return repository;
    }

    private void writeState(
            String directory, Long repositoryId, String name, String path, String branch)
            throws IOException {
        Path file = directories().repositoryState(projectId, directory, repositoryId);
        Files.createDirectories(file.getParent());
        json.writeValue(
                file.toFile(),
                Map.of(
                        "providerRepositoryId",
                        repositoryId,
                        "name",
                        name,
                        "path",
                        path,
                        "baseCommit",
                        sha("main"),
                        "branch",
                        branch,
                        "state",
                        "READY"));
    }

    private String sha(String seed) {
        return String.format("%040d", seed.hashCode() & 0xffffffffL).replace(' ', '0');
    }

    /** 用真实目录结构模拟沙箱内的 git：bare 仓库、refs、worktree 元数据、指针与配置。 */
    private void fakeGit() {
        when(commands.git(any(), anyList(), anyMap()))
                .thenAnswer(invocation -> {
                    List<String> argv = invocation.getArgument(1);
                    Path gitDir =
                            host(
                                    argv.stream()
                                            .filter(arg -> arg.startsWith("--git-dir="))
                                            .findFirst()
                                            .map(arg -> arg.substring("--git-dir=".length()))
                                            .orElse(argv.getLast()));
                    if (argv.contains("init")) {
                        Files.createDirectories(gitDir.resolve("objects"));
                        Files.createDirectories(gitDir.resolve("refs/heads"));
                        Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/main\n");
                        return ok("");
                    }
                    if (argv.contains("config")) {
                        Map<String, String> values =
                                gitConfig.computeIfAbsent(gitDir, ignored -> new HashMap<>());
                        if (argv.contains("--get")) {
                            String value = values.get(argv.getLast());
                            return value == null ? exit(1, "") : ok(value + "\n");
                        }
                        int index = argv.indexOf("config");
                        values.put(argv.get(index + 1), argv.get(index + 2));
                        return ok("");
                    }
                    if (argv.contains("fetch")) {
                        return ok("");
                    }
                    if (argv.contains("rev-parse")) {
                        String reference = argv.getLast().replace("^{commit}", "");
                        if (reference.startsWith("refs/heads/")) {
                            Path ref = gitDir.resolve(reference);
                            return Files.isRegularFile(ref)
                                    ? ok(Files.readString(ref).trim() + "\n")
                                    : exit(1, "");
                        }
                        String branch = reference.substring(reference.lastIndexOf('/') + 1);
                        return ok(sha(branch) + "\n");
                    }
                    if (argv.contains("worktree")) {
                        Path target = host(argv.get(argv.size() - 2));
                        String branch = argv.get(argv.indexOf("-b") + 1);
                        Files.createDirectories(target);
                        String worktreeName = target.getFileName().toString();
                        Path metadata = gitDir.resolve("worktrees").resolve(worktreeName);
                        Files.createDirectories(metadata);
                        Files.writeString(metadata.resolve("commondir"), "../..\n");
                        Files.writeString(
                                metadata.resolve("HEAD"), "ref: refs/heads/" + branch + "\n");
                        Path ref = gitDir.resolve("refs/heads/" + branch);
                        Files.createDirectories(ref.getParent());
                        Files.writeString(ref, sha("main") + "\n");
                        Files.writeString(
                                target.resolve(".git"),
                                "gitdir: /qgents/project/.git/42.git/worktrees/"
                                        + worktreeName
                                        + "\n");
                        return ok("");
                    }
                    return exit(1, "");
                });
    }

    private SandboxCommands.CommandResult ok(String stdout) {
        return new SandboxCommands.CommandResult(0, stdout);
    }

    private SandboxCommands.CommandResult exit(int code, String stdout) {
        return new SandboxCommands.CommandResult(code, stdout);
    }

    private Path host(String containerPath) {
        String prefix = "/qgents/project/";
        assertThat(containerPath).startsWith(prefix);
        return root.resolve(projectId + "/" + containerPath.substring(prefix.length()));
    }
}
