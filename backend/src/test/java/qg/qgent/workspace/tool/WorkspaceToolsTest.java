package qg.qgent.workspace.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qg.qgent.sandbox.service.SandboxCommands;
import qg.qgent.workspace.entity.WorkspaceEntity;
import qg.qgent.workspace.service.WorkSession;
import qg.qgent.workspace.service.WorkspaceRepositoryService;
import qg.qgent.workspace.service.WorkspaceService;

/** 工具合同：列表结构、默认值、创建 Workspace 的输入边界与逐仓库结果。 */
class WorkspaceToolsTest {
    private final Long projectId = 7L;
    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final WorkspaceRepositoryService repositories = mock(WorkspaceRepositoryService.class);
    private final WorkSession session = mock(WorkSession.class);
    private final WorkspaceTools tools =
            new WorkspaceTools(
                    1L, projectId, workspaces, repositories, mock(SandboxCommands.class), session);

    @org.junit.jupiter.api.BeforeEach
    void sessionUsesDefaultWorkspace() {
        when(session.workspaceId()).thenReturn(11L);
        when(session.ensure()).thenReturn(11L);
        when(session.ensure(anyLong())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void listWorkspacesMarksDefaultAndSelection() {
        when(workspaces.list(projectId))
                .thenReturn(List.of(workspace(11L, true), workspace(12L, false)));

        var result = tools.listWorkspaces();

        assertThat(result.get("currentWorkspaceId")).isEqualTo("11");
        assertThat((List<Map<String, Object>>) result.get("workspaces"))
                .containsExactly(
                        Map.of(
                                "workspaceId",
                                "11",
                                "isDefault",
                                true,
                                "status",
                                "READY",
                                "selected",
                                true),
                        Map.of(
                                "workspaceId",
                                "12",
                                "isDefault",
                                false,
                                "status",
                                "READY",
                                "selected",
                                false));
    }

    @Test
    void listWorkspacesBeforeSelectionReturnsNullCurrentId() {
        WorkSession unselectedSession = mock(WorkSession.class);
        when(unselectedSession.workspaceId()).thenReturn(null);
        WorkspaceTools unselected =
                new WorkspaceTools(
                        1L,
                        projectId,
                        workspaces,
                        repositories,
                        mock(SandboxCommands.class),
                        unselectedSession);
        when(workspaces.list(projectId)).thenReturn(List.of(workspace(11L, true)));

        var result = unselected.listWorkspaces();

        assertThat(result).containsEntry("currentWorkspaceId", null);
        assertThat((List<Map<String, Object>>) result.get("workspaces"))
                .singleElement()
                .satisfies(item -> assertThat(item).containsEntry("selected", false));
    }

    @Test
    void listWorkspaceRepositoriesDefaultsToCurrentWorkspace() {
        when(workspaces.require(projectId, 11L)).thenReturn(workspace(11L, true));
        when(repositories.list(projectId, workspaces.require(projectId, 11L)))
                .thenReturn(
                        List.of(
                                new WorkspaceRepositoryService.RepositoryView(
                                        "42",
                                        "owner/repo",
                                        "42",
                                        "qgents/default-repo-42",
                                        "abc",
                                        "READY",
                                        true,
                                        true)));

        var result = tools.listWorkspaceRepositories(null);

        assertThat(result.get("workspaceId")).isEqualTo("11");
        assertThat((List<Map<String, Object>>) result.get("repositories"))
                .singleElement()
                .satisfies(item ->
                        assertThat(item)
                                .containsEntry("providerRepositoryId", "42")
                                .containsEntry("managed", true)
                                .containsEntry("imported", true)
                                .containsEntry("state", "READY"));
    }

    @Test
    void createWorkspaceRequiresInstructionAndAtLeastOneRepository() {
        assertThatThrownBy(() ->
                        tools.createWorkspace(" ", List.of(Map.of("providerRepositoryId", "42"))))
                .hasMessageContaining("任务意图不能为空");
        assertThatThrownBy(() -> tools.createWorkspace("隔离任务", List.of()))
                .hasMessageContaining("至少指定一个仓库");
        verify(workspaces, never()).create(anyLong());
    }

    @Test
    void invalidRepositoryEntriesAreRejectedBeforeAnyResourceIsCreated() {
        assertThatThrownBy(() ->
                        tools.createWorkspace(
                                "隔离任务",
                                List.of(
                                        Map.of("providerRepositoryId", "42"),
                                        Map.of("providerRepositoryId", 43))))
                .hasMessageContaining("十进制字符串");
        assertThatThrownBy(() ->
                        tools.createWorkspace(
                                "隔离任务", List.of(Map.of("providerRepositoryId", "abc"))))
                .hasMessageContaining("资源 ID 无效");
        assertThatThrownBy(() ->
                        tools.createWorkspace(
                                "隔离任务", List.of(Map.of("providerRepositoryId", "42", "base", 7))))
                .hasMessageContaining("base 必须是字符串");
        assertThatThrownBy(() ->
                        tools.createWorkspace(
                                "隔离任务",
                                List.of(Map.of("providerRepositoryId", "42", "branch", true))))
                .hasMessageContaining("branch 必须是字符串");

        verify(workspaces, never()).create(anyLong());
        verifyNoInteractions(repositories);
    }

    @Test
    void createWorkspacePreparesEachRepositoryAndReportsPartialFailure() {
        WorkspaceEntity created = workspace(21L, false);
        when(workspaces.create(projectId)).thenReturn(created);
        when(repositories.prepare(1L, projectId, created, 42L, null, null))
                .thenReturn(
                        new WorkspaceRepositoryService.Preparation(
                                "42",
                                "owner/repo",
                                "42",
                                "abc",
                                "abc",
                                "qgents/ws-21-repo-42",
                                "READY",
                                null,
                                null));
        when(repositories.prepare(1L, projectId, created, 43L, "release", null))
                .thenReturn(
                        new WorkspaceRepositoryService.Preparation(
                                "43",
                                "owner/other",
                                "43",
                                null,
                                null,
                                null,
                                "FAILED",
                                "指定的基线不存在",
                                null));

        var result =
                tools.createWorkspace(
                        "隔离任务",
                        List.of(
                                Map.of("providerRepositoryId", "42"),
                                Map.of("providerRepositoryId", "43", "base", "release")));

        assertThat(result)
                .containsEntry("ok", false)
                .containsEntry("workspaceId", "21")
                .containsEntry("errorCode", "WORKSPACE_REPOSITORY_NOT_READY")
                .containsEntry("error", "部分仓库未准备完成，见 repositories 中的 state 与 error");
        var items = (List<Map<String, Object>>) result.get("repositories");
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("state", "READY").containsEntry("head", "abc");
        assertThat(items.get(1))
                .containsEntry("state", "FAILED")
                .containsEntry("error", "指定的基线不存在");
        verify(session).select(21L);
    }

    @Test
    void sandboxFailureKeepsWorkspaceIdAndMarksRepositoriesNotStarted() {
        WorkspaceEntity created = workspace(21L, false);
        when(workspaces.create(projectId)).thenReturn(created);
        WorkSession failingSession = mock(WorkSession.class);
        when(failingSession.ensure(21L)).thenThrow(new IllegalStateException("无法启动沙箱"));
        WorkspaceTools failing =
                new WorkspaceTools(
                        1L,
                        projectId,
                        workspaces,
                        repositories,
                        mock(SandboxCommands.class),
                        failingSession);

        var result = failing.createWorkspace("隔离任务", List.of(Map.of("providerRepositoryId", "42")));

        assertThat(result)
                .containsEntry("ok", false)
                .containsEntry("workspaceId", "21")
                .containsEntry("errorCode", "WORKSPACE_ENVIRONMENT_FAILED")
                .containsEntry("error", "无法启动沙箱");
        assertThat((List<Map<String, Object>>) result.get("repositories"))
                .singleElement()
                .satisfies(item ->
                        assertThat(item)
                                .containsEntry("state", "NOT_STARTED")
                                .containsEntry("error", null));
        verifyNoInteractions(repositories);
        verify(failingSession, never()).select(any());
    }

    @Test
    void unexpectedPreparationErrorKeepsCompletedItemsAndStopsRemainingWork() {
        WorkspaceEntity created = workspace(21L, false);
        when(workspaces.create(projectId)).thenReturn(created);
        when(repositories.prepare(1L, projectId, created, 42L, null, null))
                .thenReturn(
                        new WorkspaceRepositoryService.Preparation(
                                "42",
                                "owner/repo",
                                "42",
                                "abc",
                                "abc",
                                "qgents/ws-21-repo-42",
                                "READY",
                                null,
                                null));
        when(repositories.prepare(1L, projectId, created, 43L, null, null))
                .thenThrow(new IllegalStateException("无法保存仓库准备记录"));

        var result =
                tools.createWorkspace(
                        "隔离任务",
                        List.of(
                                Map.of("providerRepositoryId", "42"),
                                Map.of("providerRepositoryId", "43"),
                                Map.of("providerRepositoryId", "44")));

        assertThat(result)
                .containsEntry("ok", false)
                .containsEntry("errorCode", "WORKSPACE_REPOSITORY_PREPARATION_FAILED")
                .containsEntry("error", "无法保存仓库准备记录");
        var items = (List<Map<String, Object>>) result.get("repositories");
        assertThat(items)
                .extracting(item -> item.get("state"))
                .containsExactly("READY", "FAILED", "NOT_STARTED");
        verify(repositories, never()).prepare(1L, projectId, created, 44L, null, null);
    }

    @Test
    void prepareWorkspaceRepositoryRejectsInvalidIdBeforeStartingSandbox() {
        assertThatThrownBy(() -> tools.prepareWorkspaceRepository(null, "not-a-number", null, null))
                .hasMessageContaining("资源 ID 无效");
        verifyNoInteractions(repositories, workspaces);
    }

    private WorkspaceEntity workspace(Long id, boolean isDefault) {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(id);
        workspace.setProjectId(projectId);
        workspace.setIsDefault(isDefault);
        workspace.setStatus("READY");
        return workspace;
    }
}
