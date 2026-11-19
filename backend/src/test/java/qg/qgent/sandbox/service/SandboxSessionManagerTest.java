package qg.qgent.sandbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.sandbox.config.SandboxProperties;
import qg.qgent.sandbox.dto.CreateSandboxRequest;
import qg.qgent.sandbox.dto.SandboxResponse;
import qg.qgent.sandbox.execution.service.ToolExecutionService;
import qg.qgent.support.TestIds;
import qg.qgent.workspace.entity.WorkspaceEntity;
import qg.qgent.workspace.mapper.WorkspaceMapper;
import qg.qgent.workspace.service.WorkspaceService;
import qg.qgent.workspace.service.WorkspaceWriteLease;
import qg.qgent.workspace.service.WorkspaceWriteLeaseService;

class SandboxSessionManagerTest {
    private final ProjectDirectoryService directories = mock(ProjectDirectoryService.class);
    private final SandboxService sandboxes = mock(SandboxService.class);
    private final ToolExecutionService executions = mock(ToolExecutionService.class);
    private final WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
    private final WorkspaceWriteLeaseService leases = mock(WorkspaceWriteLeaseService.class);
    private final SandboxSessionManager sessions =
            new SandboxSessionManager(
                    sandboxes,
                    directories,
                    executions,
                    new SandboxProperties(),
                    workspaces,
                    leases);
    private final Long projectId = TestIds.next();
    private final Long workspaceId = TestIds.next();
    private final Long runId = TestIds.next();
    private final WorkspaceWriteLease lease = mock(WorkspaceWriteLease.class);

    @Test
    void preparesAndReusesSandboxResponseWithDefaultConfiguration() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(workspaceId);
        workspace.setProjectId(projectId);
        workspace.setIsDefault(true);
        workspace.setStatus("PROVISIONING");
        when(workspaces.selectById(workspaceId)).thenReturn(workspace);
        when(leases.acquire(projectId, workspaceId, runId)).thenReturn(lease);
        SandboxResponse sandbox = new SandboxResponse();
        sandbox.setId(java.util.UUID.randomUUID());
        when(sandboxes.create(any())).thenReturn(sandbox);

        SandboxSession session = sessions.acquire(runId, projectId, workspaceId);

        assertThat(session.sandboxId()).isEqualTo(sandbox.getId());
        assertThat(sessions.acquire(runId, projectId, workspaceId)).isSameAs(session);
        assertThat(workspace.getStatus()).isEqualTo("READY");
        verify(sandboxes)
                .create(
                        argThat((CreateSandboxRequest request) ->
                                runId.equals(request.getAgentRunId())
                                        && WorkspaceService.storageKey(workspace)
                                                .equals(request.getWorkspaceStorageKey())));
        verify(workspaces).updateById(workspace);
    }

    @Test
    void sandboxFailurePropagatesAndReleasesWriteLease() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(workspaceId);
        workspace.setProjectId(projectId);
        workspace.setIsDefault(true);
        when(workspaces.selectById(workspaceId)).thenReturn(workspace);
        when(leases.acquire(projectId, workspaceId, runId)).thenReturn(lease);
        IllegalStateException failure = new IllegalStateException("Sandbox unavailable");
        when(sandboxes.create(any())).thenThrow(failure);

        assertThatThrownBy(() -> sessions.acquire(runId, projectId, workspaceId)).isSameAs(failure);

        verify(leases).release(lease);
        verify(workspaces, never()).updateById(any(WorkspaceEntity.class));
        assertThatThrownBy(() -> sessions.require(workspaceId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void releasesWorkspaceOnlyAfterCommandWorkersExit() throws Exception {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(workspaceId);
        workspace.setProjectId(projectId);
        workspace.setIsDefault(true);
        workspace.setStatus("PROVISIONING");
        when(lease.getAgentRunId()).thenReturn(runId);
        when(workspaces.selectById(workspaceId)).thenReturn(workspace);
        when(leases.acquire(projectId, workspaceId, runId)).thenReturn(lease);
        SandboxResponse sandbox = new SandboxResponse();
        sandbox.setId(java.util.UUID.randomUUID());
        when(sandboxes.create(any())).thenReturn(sandbox);
        sessions.acquire(runId, projectId, workspaceId);
        CountDownLatch waitingForCommands = new CountDownLatch(1),
                releaseCommands = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            waitingForCommands.countDown();
                            assertThat(releaseCommands.await(5, TimeUnit.SECONDS)).isTrue();
                            return null;
                        })
                .when(executions)
                .stopAndAwaitBySandbox(sandbox.getId());

        try (var executor = Executors.newSingleThreadExecutor()) {
            var release = executor.submit(() -> sessions.release(workspaceId, runId));
            assertThat(waitingForCommands.await(5, TimeUnit.SECONDS)).isTrue();
            verify(sandboxes, never()).destroy(any());
            verify(leases, never()).release(lease);
            releaseCommands.countDown();
            release.get(5, TimeUnit.SECONDS);
        } finally {
            releaseCommands.countDown();
        }

        verify(sandboxes).destroy(sandbox.getId());
        verify(leases).release(lease);
    }
}
