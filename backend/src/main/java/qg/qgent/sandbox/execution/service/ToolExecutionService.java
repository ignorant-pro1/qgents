package qg.qgent.sandbox.execution.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.sandbox.config.SandboxProperties;
import qg.qgent.sandbox.exception.SandboxException;
import qg.qgent.sandbox.execution.dto.ToolContext;
import qg.qgent.sandbox.execution.dto.ToolExecutionRequest;
import qg.qgent.sandbox.execution.dto.ToolExecutionResponse;
import qg.qgent.sandbox.execution.dto.ToolResult;
import qg.qgent.sandbox.execution.entity.ExecutionStatus;
import qg.qgent.sandbox.execution.entity.ToolExecutionEntity;
import qg.qgent.sandbox.execution.entity.ToolExecutionLogEntity;
import qg.qgent.sandbox.execution.mapper.ToolExecutionLogMapper;
import qg.qgent.sandbox.execution.mapper.ToolExecutionMapper;
import qg.qgent.sandbox.execution.output.CommandOutput;
import qg.qgent.sandbox.filesystem.service.WorkspacePathResolver;
import qg.qgent.sandbox.runtime.SandboxAllocation;
import qg.qgent.sandbox.service.SandboxService;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * 管理同步文件工具与异步命令的执行、取消、完成通知和持久化。
 * 命令使用独立线程池；所有记录使用应用主数据源。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolExecutionService {
    private static final String PROCESS_EXIT_NONZERO = "PROCESS_EXIT_NONZERO";
    private static final String TOOL_EXECUTION_TIMED_OUT = "TOOL_EXECUTION_TIMED_OUT";
    private static final String TOOL_EXECUTION_FAILED = "TOOL_EXECUTION_FAILED";
    private static final String EXECUTION_QUEUE_UNAVAILABLE = "EXECUTION_QUEUE_UNAVAILABLE";
    private static final int MAX_FAILURE_REASON_LENGTH = 1024;
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[^\\s,;]+");
    private static final Pattern SENSITIVE_VALUE =
        Pattern.compile(
            "(?i)\\b(token|password|secret|api[-_]?key|authorization)\\b\\s*[:=]\\s*([^\\s,;}]*)");
    private static final Pattern WINDOWS_HOST_PATH =
        Pattern.compile("(?i)(?<![A-Za-z0-9_])(?:[A-Za-z]:[\\\\/])[^\\s,;\\\"']+");
    private static final Pattern UNIX_HOST_PATH =
        Pattern.compile(
            "(?<![A-Za-z0-9_/:])/(?:home|Users|root|tmp|var|etc|opt|srv)(?:/[^\\s,;\\\"']*)?");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private final SandboxService sandboxes;
    private final WorkspacePathResolver paths;
    private final ToolRegistry tools;
    private final ToolExecutionMapper executionMapper;
    private final ToolExecutionLogMapper logMapper;
    private final ObjectMapper objectMapper;
    private final SandboxProperties properties;
    private final ExecutorService sandboxExecutionPool;
    private final Clock clock;
    private final ActiveToolExecutions active = new ActiveToolExecutions();
    private final ConcurrentMap<Long, Object> logLocks = new ConcurrentHashMap<>();

    /**
     * Sandbox 重启后把无法恢复的排队中和运行中记录标记为中断。
     */
    @PostConstruct
    void markInterruptedExecutions() {
        executionMapper.markInterrupted(properties.getInstanceId(), utc(clock.instant()));
    }

    /**
     * 文件读取、检索和编辑在调用线程直接执行，结果无需队列或轮询。
     */
    public ToolExecutionResponse execute(UUID sandboxId, ToolExecutionRequest request) {
        SandboxAllocation sandbox = sandboxes.findAllocation(sandboxId);
        ToolExecutionEntity entity = prepare(sandboxId, request);
        return active.execute(
            entity.getId(),
            sandboxId,
            request.getAgentRunId(),
            () -> {
                run(entity.getId(), sandbox, request);
                return find(entity.getId());
            });
    }

    /**
     * 命令在线程池执行；调用方持有执行句柄并按需等待完成通知。
     */
    public ToolExecutionResponse submit(UUID sandboxId, ToolExecutionRequest request) {
        SandboxAllocation sandbox = sandboxes.findAllocation(sandboxId);
        ToolExecutionEntity entity = prepare(sandboxId, request);
        try {
            active.submit(
                entity.getId(),
                sandboxId,
                request.getAgentRunId(),
                sandboxExecutionPool,
                () -> {
                    run(entity.getId(), sandbox, request);
                    return find(entity.getId());
                });
        } catch (RejectedExecutionException exception) {
            executionMapper.rejectQueued(
                entity.getId(),
                properties.getInstanceId(),
                EXECUTION_QUEUE_UNAVAILABLE,
                "命令执行队列不可用",
                utc(clock.instant()));
            throw new SandboxException(
                HttpStatus.SERVICE_UNAVAILABLE, EXECUTION_QUEUE_UNAVAILABLE, "命令执行队列暂时不可用");
        }
        return response(entity);
    }

    /**
     * 等待进程内完成通知；超过等待窗口返回当前状态，不反复查询数据库。
     */
    public ToolExecutionResponse await(Long executionId, Duration wait) {
        CompletableFuture<ToolExecutionResponse> completion = active.completion(executionId);
        if (completion == null || wait.isZero() || wait.isNegative()) return find(executionId);
        try {
            return completion.get(wait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            return find(executionId);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待命令执行被中断", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("命令执行失败", exception.getCause());
        }
    }

    /**
     * 先落库取得自增执行编号；异步线程随后按该编号读取已提交记录。
     */
    private ToolExecutionEntity prepare(UUID sandboxId, ToolExecutionRequest request) {
        // 参数与执行结果持久化，临时环境变量只用于本次进程
        String argumentsJson = json(request.getArguments());
        ToolExecutionEntity entity = createEntity(sandboxId, request, argumentsJson);
        executionMapper.insert(entity);
        append(entity.getId(), "SYSTEM", "工具执行已登记：" + request.getTool());
        log.info(
            "tool registered executionId={} sandboxId={} agentRunId={} tool={}",
            entity.getId(),
            sandboxId,
            request.getAgentRunId(),
            request.getTool());
        return entity;
    }

    /**
     * 查询一条持久化工具执行记录。
     */
    public ToolExecutionResponse find(Long executionId) {
        return response(require(executionId));
    }

    /**
     * 取消仍在排队或运行的执行。
     * 数据库状态先切换为 CANCELLED，再中断本 Sandbox 中对应的执行线程。
     */
    public ToolExecutionResponse cancel(Long executionId) {
        LocalDateTime finishedAt = utc(clock.instant());
        if (executionMapper.markCancelled(executionId, properties.getInstanceId(), finishedAt)
            == 0) {
            ToolExecutionEntity current = require(executionId);
            if (!properties.getInstanceId().equals(current.getOwnerInstanceId())) {
                throw new SandboxException(
                    HttpStatus.CONFLICT,
                    "EXECUTION_OWNED_BY_OTHER_INSTANCE",
                    "执行不属于当前 Sandbox");
            }
            if (current.getStatus().terminal()) return response(current);
            throw new SandboxException(
                HttpStatus.CONFLICT,
                "EXECUTION_NOT_CANCELLABLE",
                "当前执行状态不可取消：" + current.getStatus());
        }
        ToolExecutionResponse cancelled = response(require(executionId));
        active.cancel(executionId, cancelled);
        append(executionId, "SYSTEM", "工具执行已取消");
        log.debug("tool cancel executionId={}", executionId);
        logLocks.remove(executionId);
        return cancelled;
    }

    /**
     * Cancels commands owned by a Sandbox and waits for their worker bodies to stop using it.
     */
    public void stopAndAwaitBySandbox(UUID sandboxId) {
        Set<Long> ids =
            new LinkedHashSet<>(
                executionMapper.selectActiveIdsBySandbox(
                    sandboxId.toString(), properties.getInstanceId()));
        ids.addAll(active.idsBySandbox(sandboxId));
        stopAndAwait(ids);
    }

    /**
     * Cancels commands started by one root or child AgentRun without affecting sibling runs.
     */
    public void stopAndAwaitByAgentRun(Long agentRunId) {
        stopAndAwait(agentRunIds(agentRunId));
    }

    /**
     * Requests cancellation for one AgentRun without waiting for its command workers to exit.
     */
    public void cancelByAgentRun(Long agentRunId) {
        agentRunIds(agentRunId).forEach(this::cancelIgnoringTerminal);
    }

    private Set<Long> agentRunIds(Long agentRunId) {
        Set<Long> ids =
            new LinkedHashSet<>(
                executionMapper.selectActiveIdsByAgentRun(
                    agentRunId, properties.getInstanceId()));
        ids.addAll(active.idsByAgentRun(agentRunId));
        return ids;
    }

    private void stopAndAwait(Collection<Long> ids) {
        ids.forEach(this::cancelIgnoringTerminal);
        active.awaitWorkers(ids);
    }

    /**
     * 记录已进入终态或已由其他实例接管时不再重复取消。
     */
    private void cancelIgnoringTerminal(Long executionId) {
        try {
            cancel(executionId);
        } catch (SandboxException exception) {
            if (!"EXECUTION_NOT_CANCELLABLE".equals(exception.getCode())) throw exception;
        }
    }

    /**
     * 读取命令当前输出；执行记录限定输出目录，不接受外部路径。
     */
    public Map<String, Object> output(
        Long executionId, long stdoutOffset, long stderrOffset, int count) {
        ToolExecutionEntity execution = require(executionId);
        if (!"command.run".equals(execution.getToolName()))
            throw new SandboxException(
                HttpStatus.UNPROCESSABLE_ENTITY, "NOT_COMMAND_EXECUTION", "只有命令执行提供输出窗口");
        var sandbox = sandboxes.findAllocation(UUID.fromString(execution.getSandboxId()));
        var directory =
            CommandOutput.directory(
                paths.resolveLocal(sandbox.getWorkspaceStorageKey()), executionId);
        return Map.of(
            "stdout",
            CommandOutput.read(directory, "stdout", stdoutOffset, count),
            "stderr",
            CommandOutput.read(directory, "stderr", stderrOffset, count));
    }

    private void run(Long executionId, SandboxAllocation sandbox, ToolExecutionRequest request) {
        Instant started = clock.instant();
        if (executionMapper.markRunning(executionId, properties.getInstanceId(), utc(started))
            == 0) {
            return;
        }
        ToolExecutionEntity running = executionMapper.selectById(executionId);
        if (running == null || running.getStatus() != ExecutionStatus.RUNNING) {
            return;
        }
        append(executionId, "SYSTEM", "开始执行工具：" + request.getTool());
        log.info(
            "tool start executionId={} sandboxId={} agentRunId={} tool={}",
            executionId,
            sandbox.getId(),
            request.getAgentRunId(),
            request.getTool());

        ExecutionStatus status;
        Integer exitCode = null;
        String resultJson = null;
        String failureCode = null;
        String failureReason = null;
        try {
            Duration timeout = timeout(request.getTimeoutSeconds(), sandbox.getExecutionTimeout());
            ToolContext context = context(sandbox, timeout);
            context.setExecutionId(executionId);
            context.setEnvironment(
                request.getEnvironment() == null ? Map.of() : request.getEnvironment());
            ToolResult result = tools.execute(request.getTool(), context, request.getArguments());
            result.getStandardOutput().forEach(line -> append(executionId, "STDOUT", line));
            result.getStandardError().forEach(line -> append(executionId, "STDERR", line));
            exitCode = result.getExitCode();
            resultJson = json(result.getResult());
            // 命令正常取得退出码即完成执行；非零退出码由模型判断，不冒充基础设施失败。
            status =
                "command.run".equals(request.getTool()) || exitCode == null || exitCode == 0
                    ? ExecutionStatus.SUCCEEDED
                    : ExecutionStatus.FAILED;
            if (status == ExecutionStatus.FAILED) {
                failureCode = PROCESS_EXIT_NONZERO;
                failureReason = "工具进程以非零退出码结束";
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            status = ExecutionStatus.TIMED_OUT;
            failureCode = TOOL_EXECUTION_TIMED_OUT;
            failureReason = "工具执行超时或被中断";
            log.warn("tool interrupted executionId={} tool={}", executionId, request.getTool());
        } catch (RuntimeException exception) {
            status = ExecutionStatus.FAILED;
            failureCode = failureCode(exception);
            failureReason = failureReason(exception);
            log.error(
                "tool failed executionId={} sandboxId={} agentRunId={} tool={} failureCode={} failureReason={}",
                executionId,
                sandbox.getId(),
                request.getAgentRunId(),
                request.getTool(),
                failureCode,
                failureReason);
        }

        executionMapper.finishIfRunning(
            executionId,
            properties.getInstanceId(),
            status,
            exitCode,
            resultJson,
            failureCode,
            failureReason,
            utc(clock.instant()));
        ToolExecutionEntity completed = executionMapper.selectById(executionId);
        if (completed != null) {
            append(executionId, "SYSTEM", "工具执行结束，状态：" + completed.getStatus());
        }
        logLocks.remove(executionId);
        log.info(
            "tool done executionId={} sandboxId={} agentRunId={} tool={} status={} exitCode={} durationMs={} failureCode={} failureReason={}",
            executionId,
            sandbox.getId(),
            request.getAgentRunId(),
            request.getTool(),
            status,
            exitCode,
            Duration.between(started, clock.instant()).toMillis(),
            failureCode,
            failureReason);
    }

    private ToolContext context(SandboxAllocation sandbox, Duration timeout) {
        return new ToolContext(
            sandbox,
            paths.resolveLocal(sandbox.getWorkspaceStorageKey()),
            "/workspace",
            timeout);
    }

    private ToolExecutionEntity createEntity(
        UUID sandboxId, ToolExecutionRequest request, String argumentsJson) {
        ToolExecutionEntity entity = new ToolExecutionEntity();
        entity.setOwnerInstanceId(properties.getInstanceId());
        entity.setSandboxId(sandboxId.toString());
        entity.setAgentRunId(request.getAgentRunId());
        entity.setWorkspaceId(request.getWorkspaceId());
        entity.setToolName(request.getTool());
        entity.setArgumentsJson(argumentsJson);
        entity.setStatus(ExecutionStatus.QUEUED);
        entity.setCreatedAt(utc(clock.instant()));
        return entity;
    }

    private ToolExecutionEntity require(Long executionId) {
        ToolExecutionEntity entity = executionMapper.selectById(executionId);
        if (entity == null) {
            throw new SandboxException(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "工具执行记录不存在");
        }
        return entity;
    }

    private void append(Long executionId, String stream, String content) {
        Object lock = logLocks.computeIfAbsent(executionId, ignored -> new Object());
        synchronized (lock) {
            ToolExecutionLogEntity log = new ToolExecutionLogEntity();
            log.setExecutionId(executionId);
            log.setSequenceNo(logMapper.selectMaxSequence(executionId) + 1);
            log.setStream(stream);
            log.setContent(content.length() <= 16000 ? content : content.substring(0, 16000));
            log.setCreatedAt(utc(clock.instant()));
            logMapper.insert(log);
        }
    }

    private Duration timeout(Long seconds, Duration sandboxLimit) {
        Duration requested = seconds == null ? sandboxLimit : Duration.ofSeconds(seconds);
        Duration effective = requested.compareTo(sandboxLimit) <= 0 ? requested : sandboxLimit;
        return effective.compareTo(properties.getMaxExecutionTimeout()) <= 0
            ? effective
            : properties.getMaxExecutionTimeout();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new SandboxException(
                HttpStatus.UNPROCESSABLE_ENTITY, "TOOL_ARGUMENT_INVALID", "工具参数无法序列化");
        }
    }

    private Map<String, Object> map(String value) {
        if (value == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (Exception exception) {
            // 结果由本服务写入，读不回来说明数据已损坏，不能当空结果交给模型。
            throw new IllegalStateException("工具执行结果无法解析", exception);
        }
    }

    private String failureCode(RuntimeException exception) {
        if (exception instanceof SandboxException sandboxException) {
            return sandboxException.getCode();
        }
        if (exception instanceof IllegalArgumentException) return "TOOL_ARGUMENT_INVALID";
        return TOOL_EXECUTION_FAILED;
    }

    private String failureReason(RuntimeException exception) {
        if ((exception instanceof SandboxException || exception instanceof IllegalArgumentException)
            && exception.getMessage() != null
            && !exception.getMessage().isBlank()) {
            return sanitizeFailureReason(exception.getMessage());
        }
        return "工具执行失败";
    }

    /**
     * SandboxException 的说明可能来自底层文件或运行时组件，只保留有限、可展示的摘要。
     * 不允许将凭据、宿主机路径或多行内部细节写入执行记录。
     */
    private String sanitizeFailureReason(String value) {
        String sanitized = BEARER.matcher(value).replaceAll("Bearer [redacted]");
        sanitized = SENSITIVE_VALUE.matcher(sanitized).replaceAll("$1=[redacted]");
        sanitized = WINDOWS_HOST_PATH.matcher(sanitized).replaceAll("[host path omitted]");
        sanitized = UNIX_HOST_PATH.matcher(sanitized).replaceAll("[host path omitted]");
        sanitized = sanitized.replaceAll("[\\r\\n]+", " ").strip();
        if (sanitized.isBlank()) {
            return "工具执行失败";
        }
        return sanitized.length() <= MAX_FAILURE_REASON_LENGTH
            ? sanitized
            : sanitized.substring(0, MAX_FAILURE_REASON_LENGTH - 3) + "...";
    }

    private ToolExecutionResponse response(ToolExecutionEntity entity) {
        return new ToolExecutionResponse(
            entity.getId(),
            entity.getOwnerInstanceId(),
            UUID.fromString(entity.getSandboxId()),
            entity.getToolName(),
            entity.getStatus(),
            entity.getExitCode(),
            map(entity.getResultJson()),
            entity.getFailureCode(),
            entity.getFailureReason(),
            instant(entity.getCreatedAt()),
            instant(entity.getStartedAt()),
            instant(entity.getFinishedAt()));
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
