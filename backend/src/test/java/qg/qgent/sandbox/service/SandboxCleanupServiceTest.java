package qg.qgent.sandbox.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandbox.config.SandboxProperties;
import qg.qgent.sandbox.dto.CreateSandboxRequest;
import qg.qgent.sandbox.execution.service.ToolExecutionService;
import qg.qgent.sandbox.runtime.FakeContainerRuntime;
import qg.qgent.support.TestIds;
import qg.qgent.workspace.service.WorkspaceOperationLock;

class SandboxCleanupServiceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void cleanupCancelsToolsAndDestroysExpiredSandbox() {
        SandboxProperties properties = new SandboxProperties();
        properties.setWorkspaceMetadataRoot(temporaryDirectory.resolve("metadata").toString());
        FakeContainerRuntime runtime = new FakeContainerRuntime();
        Clock clock = Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC);
        SandboxService sandboxes =
                new SandboxService(
                        runtime, properties, clock, new WorkspaceOperationLock(properties));
        ToolExecutionService executions = mock(ToolExecutionService.class);
        SandboxCleanupService cleanup = new SandboxCleanupService(sandboxes, executions);
        CreateSandboxRequest request = request();
        sandboxes.create(request);
        runtime.find(request.getSandboxId())
                .orElseThrow()
                .setExpiresAt(clock.instant().minusSeconds(1));

        cleanup.cleanupExpiredSandboxes();

        verify(executions).stopAndAwaitBySandbox(request.getSandboxId());
        assertTrue(sandboxes.find(request.getSandboxId()).isEmpty());
    }

    @Test
    void cleanupWaitsForExecutionWorkersBeforeDestroyingContainer() throws Exception {
        SandboxService sandboxes = mock(SandboxService.class);
        ToolExecutionService executions = mock(ToolExecutionService.class);
        java.util.UUID sandboxId = java.util.UUID.randomUUID();
        when(sandboxes.expiredSandboxIds()).thenReturn(List.of(sandboxId));
        CountDownLatch stopping = new CountDownLatch(1), exited = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            stopping.countDown();
                            assertTrue(exited.await(5, TimeUnit.SECONDS));
                            return null;
                        })
                .when(executions)
                .stopAndAwaitBySandbox(sandboxId);
        SandboxCleanupService cleanup = new SandboxCleanupService(sandboxes, executions);

        try (var pool = Executors.newSingleThreadExecutor()) {
            var future = pool.submit(cleanup::cleanupExpiredSandboxes);
            try {
                assertTrue(stopping.await(5, TimeUnit.SECONDS));
                assertFalse(future.isDone());
                verify(sandboxes, never()).destroy(any());
            } finally {
                exited.countDown();
            }
            future.get(5, TimeUnit.SECONDS);
            var order = inOrder(executions, sandboxes);
            order.verify(executions).stopAndAwaitBySandbox(sandboxId);
            order.verify(sandboxes).destroy(sandboxId);
        }
    }

    private CreateSandboxRequest request() {
        CreateSandboxRequest request = new CreateSandboxRequest();
        request.setSandboxId(java.util.UUID.randomUUID());
        request.setAgentRunId(TestIds.next());
        request.setWorkspaceStorageKey("workspaces/" + java.util.UUID.randomUUID());
        request.setImageProfile("dev-tools");
        return request;
    }
}
