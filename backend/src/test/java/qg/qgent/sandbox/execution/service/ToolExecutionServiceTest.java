package qg.qgent.sandbox.execution.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import qg.qgent.sandbox.config.SandboxProperties;
import qg.qgent.sandbox.dto.SandboxExecutionContext;
import qg.qgent.sandbox.exception.SandboxException;
import qg.qgent.sandbox.execution.dto.ToolExecutionRequest;
import qg.qgent.sandbox.execution.dto.ToolResult;
import qg.qgent.sandbox.execution.entity.ExecutionStatus;
import qg.qgent.sandbox.execution.entity.ToolExecutionEntity;
import qg.qgent.sandbox.execution.entity.ToolExecutionLogEntity;
import qg.qgent.sandbox.execution.mapper.ToolExecutionLogMapper;
import qg.qgent.sandbox.execution.mapper.ToolExecutionMapper;
import qg.qgent.sandbox.filesystem.service.WorkspacePathResolver;
import qg.qgent.sandbox.runtime.SandboxAllocation;
import qg.qgent.sandbox.service.SandboxService;
import qg.qgent.support.TestIds;

class ToolExecutionServiceTest {
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
        MDC.clear();
    }

    @Test
    void fileToolRunsOnCallerEvenWhenCommandPoolIsBusy() throws Exception {
        CountDownLatch busy = new CountDownLatch(1), release = new CountDownLatch(1);
        pool.execute(() -> {
            busy.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(busy.await(5, TimeUnit.SECONDS));
        ToolExecutionFixture fixture = fixture();
        AtomicReference<Thread> toolThread = new AtomicReference<>();
        Long child = TestIds.next(), workspace = TestIds.next();
        fixture.request().setAgentRunId(child);
        fixture.request().setWorkspaceId(workspace);
        when(fixture.tools().execute(anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    toolThread.set(Thread.currentThread());
                    return ToolResult.value(Map.of("lines", List.of("content")));
                });
        try {
            var result = fixture.service().execute(fixture.sandboxId(), fixture.request());
            assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
            assertEquals(Thread.currentThread(), toolThread.get());
            assertEquals(child, fixture.rows().get(result.getId()).getAgentRunId());
            assertEquals(workspace, fixture.rows().get(result.getId()).getWorkspaceId());
        } finally {
            release.countDown();
        }
    }

    @Test
    void commandWaitUsesCompletionNotificationAndPreservesContext() throws Exception {
        ToolExecutionFixture fixture = fixture();
        fixture.request().setTool("command.run");
        Long child = TestIds.next();
        fixture.request().setAgentRunId(child);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Long> observedRun = new AtomicReference<>();
        AtomicReference<String> observedRequest = new AtomicReference<>();
        when(fixture.tools().execute(anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    observedRun.set(SandboxExecutionContext.currentAgentRunId());
                    observedRequest.set(MDC.get("requestId"));
                    started.countDown();
                    release.await();
                    return new ToolResult(7, Map.of("stdout", "done"), List.of(), List.of());
                });
        MDC.put("requestId", "request-for-command");
        Long executionId = fixture.service().submit(fixture.sandboxId(), fixture.request()).getId();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        clearInvocations(fixture.mapper());
        try (var waiter = Executors.newSingleThreadExecutor()) {
            var result =
                    waiter.submit(
                            () -> fixture.service().await(executionId, Duration.ofSeconds(5)));
            release.countDown();
            assertEquals(7, result.get(5, TimeUnit.SECONDS).getExitCode());
        } finally {
            release.countDown();
        }
        assertEquals(child, observedRun.get());
        assertEquals("request-for-command", observedRequest.get());
        verify(fixture.mapper(), atMost(4)).selectById(executionId);
    }

    @Test
    void cancelledQueuedCommandNeverInvokesToolWhenWorkerBecomesAvailable() throws Exception {
        CountDownLatch busy = new CountDownLatch(1), release = new CountDownLatch(1);
        pool.execute(() -> {
            busy.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(busy.await(5, TimeUnit.SECONDS));
        ToolExecutionFixture fixture = fixture();
        fixture.request().setTool("command.run");
        Long executionId = fixture.service().submit(fixture.sandboxId(), fixture.request()).getId();
        try {
            assertEquals(
                    ExecutionStatus.CANCELLED, fixture.service().cancel(executionId).getStatus());
            assertEquals(
                    ExecutionStatus.CANCELLED,
                    fixture.service().await(executionId, Duration.ofSeconds(1)).getStatus());
        } finally {
            release.countDown();
        }
        // 同一个单线程池的后续任务完成，表示先前排队的命令已经走完收尾。
        pool.submit(() -> {}).get(5, TimeUnit.SECONDS);
        verify(fixture.tools(), org.mockito.Mockito.never()).execute(anyString(), any(), any());
        assertEquals(ExecutionStatus.CANCELLED, fixture.service().find(executionId).getStatus());
    }

    @Test
    void shortWaitReturnsHandleAndCancellationWakesWaiter() throws Exception {
        ToolExecutionFixture fixture = fixture();
        fixture.request().setTool("command.run");
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        when(fixture.tools().execute(anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    started.countDown();
                    release.await();
                    return ToolResult.value(Map.of());
                });
        Long executionId = fixture.service().submit(fixture.sandboxId(), fixture.request()).getId();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertEquals(
                ExecutionStatus.RUNNING,
                fixture.service().await(executionId, Duration.ofMillis(20)).getStatus());
        try (var waiter = Executors.newSingleThreadExecutor()) {
            var result =
                    waiter.submit(
                            () -> fixture.service().await(executionId, Duration.ofSeconds(5)));
            assertEquals(
                    ExecutionStatus.CANCELLED, fixture.service().cancel(executionId).getStatus());
            assertEquals(ExecutionStatus.CANCELLED, result.get(5, TimeUnit.SECONDS).getStatus());
            assertEquals(
                    ExecutionStatus.CANCELLED, fixture.service().cancel(executionId).getStatus());
        } finally {
            release.countDown();
        }
    }

    @Test
    void stoppingSandboxWaitsUntilInterruptedCommandWorkerActuallyExits() throws Exception {
        ToolExecutionFixture fixture = fixture();
        fixture.request().setTool("command.run");
        CountDownLatch started = new CountDownLatch(1),
                interrupted = new CountDownLatch(1),
                release = new CountDownLatch(1);
        when(fixture.tools().execute(anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException exception) {
                        interrupted.countDown();
                        release.await();
                    }
                    return ToolResult.value(Map.of());
                });
        Long executionId = fixture.service().submit(fixture.sandboxId(), fixture.request()).getId();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        when(fixture.mapper().selectActiveIdsBySandbox(fixture.sandboxId().toString(), "local"))
                .thenReturn(List.of(executionId));

        try (ExecutorService waiter = Executors.newSingleThreadExecutor()) {
            CompletableFuture<Void> stopping =
                    CompletableFuture.runAsync(
                            () -> fixture.service().stopAndAwaitBySandbox(fixture.sandboxId()),
                            waiter);
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertFalse(stopping.isDone());
            release.countDown();
            stopping.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void stoppingOneAgentRunCancelsAndWaitsOnlyItsCommandWorker() throws Exception {
        ToolExecutionFixture fixture = fixture();
        Long childRunId = TestIds.next();
        fixture.request().setTool("command.run");
        fixture.request().setAgentRunId(childRunId);
        CountDownLatch started = new CountDownLatch(1),
                interrupted = new CountDownLatch(1),
                release = new CountDownLatch(1);
        when(fixture.tools().execute(anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException exception) {
                        interrupted.countDown();
                        release.await();
                    }
                    return ToolResult.value(Map.of());
                });
        Long executionId = fixture.service().submit(fixture.sandboxId(), fixture.request()).getId();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        when(fixture.mapper().selectActiveIdsByAgentRun(childRunId, "local"))
                .thenReturn(List.of(executionId));

        try (ExecutorService waiter = Executors.newSingleThreadExecutor()) {
            CompletableFuture<Void> stopping =
                    CompletableFuture.runAsync(
                            () -> fixture.service().stopAndAwaitByAgentRun(childRunId), waiter);
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertFalse(stopping.isDone());
            release.countDown();
            stopping.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        verify(fixture.mapper()).selectActiveIdsByAgentRun(childRunId, "local");
    }

    @Test
    void workspaceReleaseStillWaitsForAgentRunCommandAlreadyMarkedCancelled() throws Exception {
        ToolExecutionFixture fixture = fixture();
        Long agentRunId = TestIds.next();
        fixture.request().setAgentRunId(agentRunId);
        CountDownLatch started = new CountDownLatch(1),
                interrupted = new CountDownLatch(1),
                release = new CountDownLatch(1);
        when(fixture.tools().execute(anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException exception) {
                        interrupted.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException secondInterrupt) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return ToolResult.value(Map.of("ok", true));
                });
        fixture.service().submit(fixture.sandboxId(), fixture.request());
        assertTrue(started.await(5, TimeUnit.SECONDS));
        fixture.service().cancelByAgentRun(agentRunId);
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));

        try (var waiter = Executors.newSingleThreadExecutor()) {
            CompletableFuture<Void> releaseWork =
                    CompletableFuture.runAsync(
                            () -> fixture.service().stopAndAwaitBySandbox(fixture.sandboxId()),
                            waiter);
            Thread.sleep(100);
            assertFalse(
                    releaseWork.isDone(),
                    "release must include workers cancelled earlier by run ID");
            release.countDown();
            releaseWork.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void submitPersistsQueuedRecordAndCompletesInBackground() throws Exception {
        Map<Long, ToolExecutionEntity> rows = new ConcurrentHashMap<>();
        ArrayList<ToolExecutionLogEntity> logs = new ArrayList<>();
        ToolExecutionMapper executions = executionMapper(rows);
        ToolExecutionLogMapper logMapper = logMapper(logs);
        SandboxService sandboxes = mock(SandboxService.class);
        ToolRegistry tools = mock(ToolRegistry.class);
        WorkspacePathResolver paths = mock(WorkspacePathResolver.class);
        SandboxProperties properties = new SandboxProperties();
        UUID sandboxId = UUID.randomUUID();
        SandboxAllocation sandbox = allocation(sandboxId);
        when(sandboxes.findAllocation(sandboxId)).thenReturn(sandbox);
        when(tools.execute(anyString(), any(), any()))
                .thenReturn(ToolResult.value(Map.of("ok", true)));
        ToolExecutionService service =
                new ToolExecutionService(
                        sandboxes,
                        paths,
                        tools,
                        executions,
                        logMapper,
                        new ObjectMapper().findAndRegisterModules(),
                        properties,
                        pool,
                        Clock.systemUTC());
        ToolExecutionRequest request = new ToolExecutionRequest();
        request.setTool("file.read");
        request.setEnvironment(Map.of("GIT_CONFIG_VALUE_0", "temporary-secret"));

        Long executionId = service.submit(sandboxId, request).getId();
        for (int attempt = 0;
                attempt < 100 && rows.get(executionId).getStatus() != ExecutionStatus.SUCCEEDED;
                attempt++) {
            Thread.sleep(10);
        }

        assertEquals(ExecutionStatus.SUCCEEDED, service.find(executionId).getStatus());
        assertFalse(rows.get(executionId).getArgumentsJson().contains("temporary-secret"));
        org.mockito.Mockito.verify(tools)
                .execute(
                        anyString(),
                        org.mockito.ArgumentMatchers.argThat(context ->
                                "temporary-secret"
                                        .equals(
                                                context.getEnvironment()
                                                        .get("GIT_CONFIG_VALUE_0"))),
                        any());
        assertEquals(null, service.find(executionId).getFailureCode());
        assertEquals(null, service.find(executionId).getFailureReason());
        assertFalse(logs.isEmpty());
    }

    @Test
    void sandboxExceptionPersistsStructuredFailureCodeAndReason() throws Exception {
        ToolExecutionFixture fixture = fixture();
        when(fixture.tools().execute(anyString(), any(), any()))
                .thenThrow(
                        new SandboxException(
                                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                                "FILE_PATCH_FAILED",
                                "hunk 声明行数与正文不一致"));

        Long executionId = fixture.service().submit(fixture.sandboxId(), fixture.request()).getId();
        awaitTerminal(fixture.rows(), executionId);

        assertEquals(ExecutionStatus.FAILED, fixture.service().find(executionId).getStatus());
        assertEquals("FILE_PATCH_FAILED", fixture.service().find(executionId).getFailureCode());
        assertEquals("hunk 声明行数与正文不一致", fixture.service().find(executionId).getFailureReason());
    }

    @Test
    void sandboxExceptionFailureReasonIsRedactedAndBounded() throws Exception {
        ToolExecutionFixture fixture = fixture();
        String detail =
                "Bearer super-secret token=abc https://example.invalid/test?api_key=abc "
                        + "C:\\Users\\Administrator\\private.txt "
                        + "x".repeat(1200);
        when(fixture.tools().execute(anyString(), any(), any()))
                .thenThrow(
                        new SandboxException(
                                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                                "TOOL_PATH_INVALID",
                                detail));

        Long executionId = fixture.service().submit(fixture.sandboxId(), fixture.request()).getId();
        awaitTerminal(fixture.rows(), executionId);

        String reason = fixture.service().find(executionId).getFailureReason();
        assertEquals("TOOL_PATH_INVALID", fixture.service().find(executionId).getFailureCode());
        assertFalse(reason.contains("super-secret"));
        assertFalse(reason.contains("token=abc"));
        assertFalse(reason.contains("C:\\Users\\Administrator"));
        assertTrue(reason.contains("https://example.invalid/test?api_key=[redacted]"));
        assertTrue(reason.contains("[redacted]"));
        assertTrue(reason.contains("[host path omitted]"));
        assertTrue(reason.length() <= 1024);
    }

    @Test
    void processExitFailurePersistsStableCodeWithoutOutput() throws Exception {
        ToolExecutionFixture fixture = fixture();
        when(fixture.tools().execute(anyString(), any(), any()))
                .thenReturn(
                        new ToolResult(
                                17, Map.of("command", "mvn"), List.of("secret output"), List.of()));

        Long executionId = fixture.service().submit(fixture.sandboxId(), fixture.request()).getId();
        awaitTerminal(fixture.rows(), executionId);

        assertEquals("PROCESS_EXIT_NONZERO", fixture.service().find(executionId).getFailureCode());
        assertEquals("工具进程以非零退出码结束", fixture.service().find(executionId).getFailureReason());
        assertEquals(17, fixture.service().find(executionId).getExitCode());
    }

    @Test
    void nonzeroCommandExitIsACompletedResult() throws Exception {
        ToolExecutionFixture fixture = fixture();
        fixture.request().setTool("command.run");
        when(fixture.tools().execute(anyString(), any(), any()))
                .thenReturn(
                        new ToolResult(7, Map.of("stdout", "diagnostic"), List.of(), List.of()));
        Long executionId = fixture.service().submit(fixture.sandboxId(), fixture.request()).getId();
        awaitTerminal(fixture.rows(), executionId);
        assertEquals(ExecutionStatus.SUCCEEDED, fixture.service().find(executionId).getStatus());
        assertEquals(7, fixture.service().find(executionId).getExitCode());
        assertEquals(null, fixture.service().find(executionId).getFailureCode());
    }

    @Test
    void invalidArgumentsAreReturnedWithUsefulReason() throws Exception {
        ToolExecutionFixture fixture = fixture();
        when(fixture.tools().execute(anyString(), any(), any()))
                .thenThrow(new IllegalArgumentException("command 必须是非空参数数组"));
        Long executionId = fixture.service().submit(fixture.sandboxId(), fixture.request()).getId();
        awaitTerminal(fixture.rows(), executionId);
        assertEquals("TOOL_ARGUMENT_INVALID", fixture.service().find(executionId).getFailureCode());
        assertEquals("command 必须是非空参数数组", fixture.service().find(executionId).getFailureReason());
    }

    @Test
    void unclassifiedFailurePersistsGenericCodeAndReason() throws Exception {
        ToolExecutionFixture fixture = fixture();
        when(fixture.tools().execute(anyString(), any(), any()))
                .thenThrow(new IllegalStateException("internal detail"));

        Long executionId = fixture.service().submit(fixture.sandboxId(), fixture.request()).getId();
        awaitTerminal(fixture.rows(), executionId);

        assertEquals("TOOL_EXECUTION_FAILED", fixture.service().find(executionId).getFailureCode());
        assertEquals("工具执行失败", fixture.service().find(executionId).getFailureReason());
    }

    @Test
    void interruptedExecutionPersistsTimeoutCode() throws Exception {
        ToolExecutionFixture fixture = fixture();
        when(fixture.tools().execute(anyString(), any(), any()))
                .thenThrow(new InterruptedException("timeout"));

        Long executionId = fixture.service().submit(fixture.sandboxId(), fixture.request()).getId();
        awaitTerminal(fixture.rows(), executionId);

        assertEquals(ExecutionStatus.TIMED_OUT, fixture.service().find(executionId).getStatus());
        assertEquals(
                "TOOL_EXECUTION_TIMED_OUT", fixture.service().find(executionId).getFailureCode());
        assertEquals("工具执行超时或被中断", fixture.service().find(executionId).getFailureReason());
    }

    @Test
    void queuedExecutionCancellationIsIdempotent() {
        Map<Long, ToolExecutionEntity> rows = new ConcurrentHashMap<>();
        ArrayList<ToolExecutionLogEntity> logs = new ArrayList<>();
        ToolExecutionMapper executions = executionMapper(rows);
        ToolExecutionLogMapper logMapper = logMapper(logs);
        SandboxService sandboxes = mock(SandboxService.class);
        ToolRegistry tools = mock(ToolRegistry.class);
        WorkspacePathResolver paths = mock(WorkspacePathResolver.class);
        ExecutorService waitingPool = mock(ExecutorService.class);
        SandboxProperties properties = new SandboxProperties();
        UUID sandboxId = UUID.randomUUID();
        when(sandboxes.findAllocation(sandboxId)).thenReturn(allocation(sandboxId));
        ToolExecutionService service =
                new ToolExecutionService(
                        sandboxes,
                        paths,
                        tools,
                        executions,
                        logMapper,
                        new ObjectMapper().findAndRegisterModules(),
                        properties,
                        waitingPool,
                        Clock.systemUTC());
        ToolExecutionRequest request = new ToolExecutionRequest();
        request.setTool("file.read");

        Long executionId = service.submit(sandboxId, request).getId();

        assertEquals(ExecutionStatus.CANCELLED, service.cancel(executionId).getStatus());
        assertEquals("EXECUTION_CANCELLED", service.find(executionId).getFailureCode());
        assertEquals("执行已取消", service.find(executionId).getFailureReason());
        assertEquals(ExecutionStatus.CANCELLED, service.cancel(executionId).getStatus());
    }

    @Test
    void startupRecoveryPersistsInterruptedCodeAndReason() {
        ToolExecutionFixture fixture = fixture();
        Long executionId = TestIds.next();
        ToolExecutionEntity queued = queuedEntity(executionId, fixture.sandboxId());
        fixture.rows().put(queued.getId(), queued);

        fixture.service().markInterruptedExecutions();

        assertEquals(ExecutionStatus.INTERRUPTED, fixture.service().find(executionId).getStatus());
        assertEquals(
                "APPLICATION_RESTART_INTERRUPTED",
                fixture.service().find(executionId).getFailureCode());
        assertEquals("Sandbox 重启，执行状态已中断", fixture.service().find(executionId).getFailureReason());
    }

    @Test
    void queueRejectionPersistsFailureCodeBeforeReturningServiceError() {
        Map<Long, ToolExecutionEntity> rows = new ConcurrentHashMap<>();
        ToolExecutionMapper executions = executionMapper(rows);
        ToolExecutionLogMapper logMapper = logMapper(new ArrayList<>());
        SandboxService sandboxes = mock(SandboxService.class);
        ToolRegistry tools = mock(ToolRegistry.class);
        WorkspacePathResolver paths = mock(WorkspacePathResolver.class);
        ExecutorService rejectedPool = mock(ExecutorService.class);
        doThrow(new RejectedExecutionException("unavailable")).when(rejectedPool).execute(any());
        UUID sandboxId = UUID.randomUUID();
        when(sandboxes.findAllocation(sandboxId)).thenReturn(allocation(sandboxId));
        ToolExecutionService service =
                new ToolExecutionService(
                        sandboxes,
                        paths,
                        tools,
                        executions,
                        logMapper,
                        new ObjectMapper().findAndRegisterModules(),
                        new SandboxProperties(),
                        rejectedPool,
                        Clock.systemUTC());
        ToolExecutionRequest request = request();

        SandboxException exception =
                assertThrows(SandboxException.class, () -> service.submit(sandboxId, request));

        assertEquals("EXECUTION_QUEUE_UNAVAILABLE", exception.getCode());
        ToolExecutionEntity rejected = rows.values().iterator().next();
        assertEquals(ExecutionStatus.FAILED, rejected.getStatus());
        assertEquals("EXECUTION_QUEUE_UNAVAILABLE", rejected.getFailureCode());
        assertEquals("命令执行队列不可用", rejected.getFailureReason());
    }

    private ToolExecutionMapper executionMapper(Map<Long, ToolExecutionEntity> rows) {
        ToolExecutionMapper mapper = mock(ToolExecutionMapper.class);
        when(mapper.selectById(any()))
                .thenAnswer(invocation -> rows.get(invocation.getArgument(0)));
        when(mapper.selectActiveIdsByAgentRun(any(), anyString()))
                .thenAnswer(invocation ->
                        rows.values().stream()
                                .filter(entity ->
                                        invocation.getArgument(0).equals(entity.getAgentRunId()))
                                .filter(entity ->
                                        invocation
                                                .getArgument(1)
                                                .equals(entity.getOwnerInstanceId()))
                                .filter(entity -> !entity.getStatus().terminal())
                                .map(ToolExecutionEntity::getId)
                                .toList());
        when(mapper.markInterrupted(anyString(), any()))
                .thenAnswer(invocation -> {
                    int updated = 0;
                    for (ToolExecutionEntity entity : rows.values()) {
                        if (!entity.getStatus().terminal()) {
                            entity.setStatus(ExecutionStatus.INTERRUPTED);
                            entity.setFailureCode("APPLICATION_RESTART_INTERRUPTED");
                            entity.setFailureReason("Sandbox 重启，执行状态已中断");
                            entity.setFinishedAt(invocation.getArgument(1));
                            updated++;
                        }
                    }
                    return updated;
                });
        when(mapper.insert(any(ToolExecutionEntity.class)))
                .thenAnswer(invocation -> {
                    ToolExecutionEntity entity = invocation.getArgument(0);
                    if (entity.getId() == null) entity.setId(TestIds.next());
                    rows.put(entity.getId(), entity);
                    return 1;
                });
        when(mapper.markRunning(any(), anyString(), any()))
                .thenAnswer(invocation -> {
                    ToolExecutionEntity entity = rows.get(invocation.getArgument(0));
                    if (entity == null || entity.getStatus() != ExecutionStatus.QUEUED) return 0;
                    entity.setStatus(ExecutionStatus.RUNNING);
                    entity.setStartedAt(invocation.getArgument(2));
                    return 1;
                });
        when(mapper.finishIfRunning(
                        any(),
                        anyString(),
                        any(ExecutionStatus.class),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenAnswer(invocation -> {
                    ToolExecutionEntity entity = rows.get(invocation.getArgument(0));
                    if (entity == null || entity.getStatus() != ExecutionStatus.RUNNING) return 0;
                    entity.setStatus(invocation.getArgument(2, ExecutionStatus.class));
                    entity.setExitCode(invocation.getArgument(3));
                    entity.setResultJson(invocation.getArgument(4));
                    entity.setFailureCode(invocation.getArgument(5));
                    entity.setFailureReason(invocation.getArgument(6));
                    entity.setFinishedAt(invocation.getArgument(7));
                    return 1;
                });
        when(mapper.markCancelled(any(), anyString(), any()))
                .thenAnswer(invocation -> {
                    ToolExecutionEntity entity = rows.get(invocation.getArgument(0));
                    if (entity == null || !!entity.getStatus().terminal()) return 0;
                    entity.setStatus(ExecutionStatus.CANCELLED);
                    entity.setFailureCode("EXECUTION_CANCELLED");
                    entity.setFailureReason("执行已取消");
                    entity.setFinishedAt(invocation.getArgument(2));
                    return 1;
                });
        when(mapper.rejectQueued(any(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    ToolExecutionEntity entity = rows.get(invocation.getArgument(0));
                    if (entity == null || entity.getStatus() != ExecutionStatus.QUEUED) return 0;
                    entity.setStatus(ExecutionStatus.FAILED);
                    entity.setFailureCode(invocation.getArgument(2));
                    entity.setFailureReason(invocation.getArgument(3));
                    entity.setFinishedAt(invocation.getArgument(4));
                    return 1;
                });
        return mapper;
    }

    private ToolExecutionLogMapper logMapper(ArrayList<ToolExecutionLogEntity> logs) {
        ToolExecutionLogMapper mapper = mock(ToolExecutionLogMapper.class);
        when(mapper.selectMaxSequence(any())).thenAnswer(invocation -> (long) logs.size());
        when(mapper.insert(any()))
                .thenAnswer(invocation -> {
                    logs.add(invocation.getArgument(0));
                    return 1;
                });
        return mapper;
    }

    private SandboxAllocation allocation(UUID sandboxId) {
        Instant now = Instant.now();
        return new SandboxAllocation(
                sandboxId,
                TestIds.next(),
                "workspaces/" + UUID.randomUUID(),
                "dev-tools",
                "READY",
                "FAKE",
                now,
                now,
                now.plusSeconds(60),
                now.plusSeconds(3600),
                Duration.ofMinutes(15),
                null);
    }

    private ToolExecutionFixture fixture() {
        Map<Long, ToolExecutionEntity> rows = new ConcurrentHashMap<>();
        ArrayList<ToolExecutionLogEntity> logs = new ArrayList<>();
        ToolExecutionMapper executions = executionMapper(rows);
        ToolExecutionLogMapper logMapper = logMapper(logs);
        SandboxService sandboxes = mock(SandboxService.class);
        ToolRegistry tools = mock(ToolRegistry.class);
        WorkspacePathResolver paths = mock(WorkspacePathResolver.class);
        SandboxProperties properties = new SandboxProperties();
        UUID sandboxId = UUID.randomUUID();
        when(sandboxes.findAllocation(sandboxId)).thenReturn(allocation(sandboxId));
        ToolExecutionService service =
                new ToolExecutionService(
                        sandboxes,
                        paths,
                        tools,
                        executions,
                        logMapper,
                        new ObjectMapper().findAndRegisterModules(),
                        properties,
                        pool,
                        Clock.systemUTC());
        return new ToolExecutionFixture(service, tools, rows, sandboxId, request(), executions);
    }

    private ToolExecutionRequest request() {
        ToolExecutionRequest request = new ToolExecutionRequest();
        request.setTool("file.read");
        return request;
    }

    private ToolExecutionEntity queuedEntity(Long executionId, UUID sandboxId) {
        ToolExecutionEntity entity = new ToolExecutionEntity();
        entity.setId(executionId);
        entity.setOwnerInstanceId("local");
        entity.setSandboxId(sandboxId.toString());
        entity.setToolName("file.read");
        entity.setStatus(ExecutionStatus.QUEUED);
        entity.setCreatedAt(java.time.LocalDateTime.now());
        return entity;
    }

    private void awaitTerminal(Map<Long, ToolExecutionEntity> rows, Long executionId)
            throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            ToolExecutionEntity entity = rows.get(executionId);
            if (entity != null && entity.getStatus().terminal()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("工具执行未在预期时间内结束");
    }

    private record ToolExecutionFixture(
            ToolExecutionService service,
            ToolRegistry tools,
            Map<Long, ToolExecutionEntity> rows,
            UUID sandboxId,
            ToolExecutionRequest request,
            ToolExecutionMapper mapper) {}
}
