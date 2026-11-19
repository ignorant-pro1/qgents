package qg.qgent.harness.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import qg.qgent.chat.service.GroupService;
import qg.qgent.common.web.ApiException;
import qg.qgent.harness.dto.AgentRunSnapshot;
import qg.qgent.harness.entity.AgentRunEntity;
import qg.qgent.harness.entity.AgentRunToolEntity;
import qg.qgent.harness.entity.RunStatus;
import qg.qgent.harness.mapper.AgentRunMapper;
import qg.qgent.harness.mapper.AgentRunToolMapper;
import qg.qgent.realtime.service.EventService;
import qg.qgent.realtime.transport.SseEmitterFactory;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 一次 Agent 运行的可展示进度。
 * <p>
 * 正文增量只走群级 SSE 的进程内队列，不写数据库；工具明细写入 {@code agent_run_tools}，
 * 快照以数据库为准，运行结束后或进程重启后仍能回看。轮次等低频状态仍通过
 * {@link EventService} 持久化，供现有项目 SSE/WS 与刷新兜底。正文只保存在当前进程，
 * 重启后不可恢复，以数据库终态、工具明细和最终消息为准。
 */
@Service
public class AgentStreamService {
    // 正文增量聚合：最多 120ms 或 200 字发一帧，避免逐 token 推送。
    private static final long FLUSH_INTERVAL_MS = 120;
    private static final int FLUSH_CHARS = 200;
    // 单条 SSE 连接允许积压的帧数；慢客户端溢出直接断开，由快照恢复。
    private static final int QUEUE_CAPACITY = 256;
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;
    private static final long HEARTBEAT_MS = 15_000;
    private static final long ACCESS_RECHECK_MS = 10_000;
    private static final int MAX_STREAMS = 128;
    private static final int TOOL_NAME_LIMIT = 64;
    // 工具入参与结果只保留前 2000 字，截断时末尾加省略号。
    private static final int TOOL_TEXT_LIMIT = 2000;

    private final EventService events;
    private final AgentRunMapper runs;
    private final AgentRunToolMapper toolRuns;
    private final GroupService groups;
    private final Executor pump;
    private final SseEmitterFactory emitters;
    private final AtomicInteger connections = new AtomicInteger();
    private final Map<Long, LiveRun> live = new ConcurrentHashMap<>();
    // 根运行生命周期内的 seq/round 计数器：子运行结束后续接也不重置。
    private final Map<Long, RootStream> roots = new ConcurrentHashMap<>();
    private final Map<Long, Set<GroupStream>> streams = new ConcurrentHashMap<>();

    public AgentStreamService(
        EventService events,
        AgentRunMapper runs,
        AgentRunToolMapper toolRuns,
        GroupService groups,
        SseEmitterFactory emitters,
        @Qualifier("sseEventPumpExecutor") Executor pump) {
        this.events = events;
        this.runs = runs;
        this.toolRuns = toolRuns;
        this.groups = groups;
        this.emitters = emitters;
        this.pump = pump;
    }

    /**
     * 注册运行的实时状态，并以即将开始的轮次广播开始事件。
     */
    public RunStreamSink sink(AgentRunEntity run) {
        RootStream root = roots.computeIfAbsent(rootRunId(run), ignored -> new RootStream());
        LiveRun state = live.computeIfAbsent(run.getId(), ignored -> new LiveRun(run, root));
        beginRound(state);
        return new Sink(state, true);
    }

    /**
     * 发布即将开始的这一轮；loop 的第一次 roundStarted 不再重复递增。
     */
    private void beginRound(LiveRun state) {
        synchronized (state) {
            if (state.closed) return;
            flush(state, true);
            int round = state.root.round(state.run.getId()).incrementAndGet();
            state.round = round;
            state.segments.add(new Segment(round));
            long revision = state.root.revision(state.run.getId()).incrementAndGet();
            emit(state, "agent-run.updated", updatedPayload(state, RunStatus.RUNNING, revision));
        }
        drainPublishes(state);
    }

    /**
     * 订阅一个群的 Agent 运行事件；建连时先下发全部活动运行快照。
     */
    public SseEmitter openGroupStream(Long projectId, Long groupId, Long userId) {
        groups.requireGroupMember(projectId, groupId, userId);
        SseEmitter emitter = emitters.create(SSE_TIMEOUT_MS);
        if (connections.incrementAndGet() > MAX_STREAMS) {
            connections.decrementAndGet();
            throw new ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "SSE_CONNECTION_LIMIT_EXCEEDED",
                "当前实时连接过多，请稍后重试");
        }
        GroupStream stream = new GroupStream(projectId, groupId, userId, emitter);
        streams.compute(
            groupId,
            (ignored, existing) -> {
                Set<GroupStream> updated =
                    existing == null ? ConcurrentHashMap.newKeySet() : existing;
                updated.add(stream);
                return updated;
            });
        stream.emitter.onTimeout(() -> close(stream));
        stream.emitter.onError(error -> close(stream));
        stream.emitter.onCompletion(() -> close(stream));
        try {
            pump.execute(() -> pump(stream));
        } catch (RuntimeException failure) {
            close(stream);
            throw failure;
        }
        return stream.emitter;
    }

    /**
     * 群内最近的运行（含子运行），用于刷新与断线恢复。
     */
    public List<AgentRunSnapshot> recentRuns(
        Long projectId, Long groupId, Long userId, int limit) {
        groups.requireGroupMember(projectId, groupId, userId);
        List<AgentRunEntity> found =
            runs.selectList(
                    Wrappers.<AgentRunEntity>lambdaQuery()
                        .eq(AgentRunEntity::getProjectId, projectId)
                        .eq(AgentRunEntity::getGroupId, groupId)
                        .orderByDesc(AgentRunEntity::getCreatedAt)
                        .last("LIMIT " + limit));
        Map<Long, List<AgentRunToolEntity>> rows = toolRows(found);
        return found.stream()
            .map(run -> snapshot(run, rows.getOrDefault(run.getId(), List.of())))
            .toList();
    }

    private void pump(GroupStream stream) {
        try {
            List<AgentRunEntity> groupRuns =
                live.values().stream()
                    .map(state -> state.run)
                    .filter(run -> run.getGroupId().equals(stream.groupId))
                    .toList();
            Map<Long, List<AgentRunToolEntity>> rows = toolRows(groupRuns);
            for (AgentRunEntity run : groupRuns) {
                Map<String, Object> payload =
                    Map.of("run", snapshot(run, rows.getOrDefault(run.getId(), List.of())));
                if (!send(stream, "agent-run.snapshot", payload)) return;
            }
            long lastHeartbeat = System.currentTimeMillis();
            long lastAccessCheck = lastHeartbeat;
            while (!stream.closed.get()) {
                Frame frame = stream.queue.poll(1, TimeUnit.SECONDS);
                if (frame != null && !send(stream, frame.type(), frame.payload())) return;
                long now = System.currentTimeMillis();
                if (now - lastHeartbeat >= HEARTBEAT_MS) {
                    if (!sendComment(stream)) return;
                    lastHeartbeat = now;
                }
                if (now - lastAccessCheck >= ACCESS_RECHECK_MS) {
                    lastAccessCheck = now;
                    if (!stillMember(stream)) return;
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            close(stream);
        }
    }

    private boolean stillMember(GroupStream stream) {
        try {
            groups.requireGroupMember(stream.projectId, stream.groupId, stream.userId);
            return true;
        } catch (ApiException exception) {
            return false;
        }
    }

    private boolean send(GroupStream stream, String type, Map<String, Object> payload) {
        try {
            stream.emitter.send(SseEmitter.event().name(type).data(payload));
            return true;
        } catch (IOException | IllegalStateException exception) {
            return false;
        }
    }

    private boolean sendComment(GroupStream stream) {
        try {
            stream.emitter.send(SseEmitter.event().comment("heartbeat"));
            return true;
        } catch (IOException | IllegalStateException exception) {
            return false;
        }
    }

    /**
     * 终止一条连接并清理注册；重复调用安全。
     */
    private void close(GroupStream stream) {
        if (!stream.closed.compareAndSet(false, true)) return;
        streams.computeIfPresent(
            stream.groupId,
            (ignored, existing) -> {
                existing.remove(stream);
                return existing.isEmpty() ? null : existing;
            });
        connections.decrementAndGet();
        try {
            stream.emitter.complete();
        } catch (Exception ignored) {
            // 连接可能已经关闭。
        }
    }

    /**
     * 必须在 state 的监视器内调用；把缓冲正文追加到当前分段并按需发一帧。
     */
    private void flush(LiveRun state, boolean force) {
        if (state.pending.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (!force
            && state.pending.length() < FLUSH_CHARS
            && now - state.lastFlushAt < FLUSH_INTERVAL_MS) {
            return;
        }
        String text = state.pending.toString();
        state.pending.setLength(0);
        state.lastFlushAt = now;
        if (state.segments.isEmpty()) state.segments.add(new Segment(state.round));
        state.segments.getLast().text.append(text);
        long sequence = state.root.seq(state.run.getId()).incrementAndGet();
        long revision = state.root.revision(state.run.getId()).incrementAndGet();
        Map<String, Object> payload = basePayload(state.run);
        payload.put("status", RunStatus.RUNNING.name());
        payload.put("round", state.round);
        payload.put("seq", sequence);
        payload.put("revision", revision);
        payload.put("text", text);
        fanout(state.run.getGroupId(), "agent-run.delta", payload);
    }

    private Map<String, Object> updatedPayload(
        LiveRun state, RunStatus status, long revision) {
        Map<String, Object> payload = basePayload(state.run);
        payload.put("status", status.name());
        payload.put("round", state.round);
        payload.put("rounds", rounds(state.run));
        payload.put("revision", revision);
        return payload;
    }

    private Map<String, Object> toolPayload(
        LiveRun state,
        String phase,
        String toolCallId,
        String toolName,
        int round,
        Boolean ok,
        String text,
        long revision) {
        Map<String, Object> payload = basePayload(state.run);
        payload.put("status", RunStatus.RUNNING.name());
        payload.put("round", round);
        payload.put("revision", revision);
        payload.put("phase", phase);
        payload.put("toolCallId", toolCallId);
        payload.put("tool", toolName(toolName));
        payload.put("ok", ok);
        payload.put("started".equals(phase) ? "input" : "output", text);
        return payload;
    }

    /**
     * 必须在 state 监视器内调用：SSE 按 revision 顺序入队，数据库持久化在锁外按同一顺序执行。
     */
    private void emit(LiveRun state, String type, Map<String, Object> payload) {
        fanout(state.run.getGroupId(), type, payload);
        state.pendingPublishes.add(new PendingEvent(type, payload));
    }

    /**
     * 同一运行的事件串行落库并广播，避免并行工具通知反序；不持有 state 锁做 IO。
     */
    private void drainPublishes(LiveRun state) {
        synchronized (state.publishLock) {
            while (true) {
                PendingEvent event;
                synchronized (state) {
                    event = state.pendingPublishes.poll();
                    if (event == null) return;
                }
                events.publish(
                    state.run.getProjectId(),
                    state.run.getGroupId(),
                    event.type(),
                    state.run.getId().toString(),
                    event.payload());
            }
        }
    }

    private Map<String, Object> basePayload(AgentRunEntity run) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", run.getId().toString());
        payload.put("parentRunId", id(run.getParentRunId()));
        payload.put("rootRunId", id(rootRunId(run)));
        payload.put("projectId", run.getProjectId().toString());
        payload.put("groupId", run.getGroupId().toString());
        payload.put("agentId", run.getAgentId().toString());
        payload.put("triggerMessageId", id(run.getTriggerMessageId()));
        return payload;
    }

    private void fanout(Long groupId, String type, Map<String, Object> payload) {
        Set<GroupStream> groupStreams = streams.get(groupId);
        if (groupStreams == null) return;
        Frame frame = new Frame(type, payload);
        for (GroupStream stream : groupStreams) {
            // 慢客户端不阻塞模型线程：队列满直接断开，重连后以快照恢复。
            if (!stream.queue.offer(frame)) close(stream);
        }
    }

    private AgentRunSnapshot snapshot(AgentRunEntity run, List<AgentRunToolEntity> rows) {
        LiveRun state = live.get(run.getId());
        RootStream root = roots.get(rootRunId(run));
        int round = root == null ? 0 : root.roundValue(run.getId());
        long revision = root == null ? 0 : root.revisionValue(run.getId());
        long seq = root == null ? 0 : root.seqValue(run.getId());
        List<AgentRunSnapshot.Segment> segments = List.of();
        if (state != null) {
            // 与 segments 在同一把锁内取版本号，避免迟到的快照把已有正文改回空。
            synchronized (state) {
                round = state.round;
                revision = state.root.revisionValue(run.getId());
                seq = state.root.seqValue(run.getId());
                segments = segments(state);
            }
        }
        return new AgentRunSnapshot(
            run.getId().toString(),
            id(run.getParentRunId()),
            id(rootRunId(run)),
            id(run.getProjectId()),
            id(run.getGroupId()),
            id(run.getAgentId()),
            id(run.getTriggerMessageId()),
            run.getStatus().name(),
            round,
            rounds(run),
            seq,
            revision,
            segments,
            tools(run, rows),
            run.getResult(),
            iso(run.getCreatedAt()),
            iso(run.getFinishedAt()));
    }

    private List<AgentRunSnapshot.Segment> segments(LiveRun state) {
        List<AgentRunSnapshot.Segment> result = new ArrayList<>(state.segments.size());
        for (Segment segment : state.segments) {
            if (!segment.text.isEmpty()) {
                result.add(new AgentRunSnapshot.Segment(segment.round, segment.text.toString()));
            }
        }
        return result;
    }

    /**
     * 按 run_id 一次取回工具明细，避免最近运行列表逐条查询。
     */
    private Map<Long, List<AgentRunToolEntity>> toolRows(List<AgentRunEntity> found) {
        if (found.isEmpty()) return Map.of();
        return toolRuns.byRunIds(found.stream().map(AgentRunEntity::getId).toList()).stream()
            .collect(Collectors.groupingBy(AgentRunToolEntity::getRunId));
    }

    private static List<AgentRunSnapshot.Tool> tools(
        AgentRunEntity run, List<AgentRunToolEntity> rows) {
        List<AgentRunSnapshot.Tool> result = new ArrayList<>(rows.size());
        for (AgentRunToolEntity row : rows) {
            result.add(
                new AgentRunSnapshot.Tool(
                    row.getToolCallId(),
                    row.getTool(),
                    row.getRound() == null ? 0 : row.getRound(),
                    toolState(row.getState(), run.getStatus()),
                    row.getInput(),
                    row.getOutput()));
        }
        return result;
    }

    /**
     * 运行已结束时不会再有工具事件，库里残留的 running 按中性的“结束”返回。
     */
    private static String toolState(String state, RunStatus runStatus) {
        boolean terminal = runStatus != RunStatus.RUNNING && runStatus != RunStatus.CANCELLING;
        return terminal && "running".equals(state) ? "done" : state;
    }

    private int rounds(AgentRunEntity run) {
        return run.getRounds() == null ? 0 : run.getRounds();
    }

    private static String toolName(String name) {
        return name != null && name.length() > TOOL_NAME_LIMIT
            ? name.substring(0, TOOL_NAME_LIMIT)
            : name;
    }

    private static String truncate(String text) {
        return text == null || text.length() <= TOOL_TEXT_LIMIT
            ? text
            : text.substring(0, TOOL_TEXT_LIMIT) + "…";
    }

    private static Long rootRunId(AgentRunEntity run) {
        return run.getParentRunId() == null ? run.getId() : run.getParentRunId();
    }

    private static String id(Long value) {
        return value == null ? null : value.toString();
    }

    private static String iso(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
    }

    /**
     * 同一根运行共享的单调计数器。
     */
    private static final class RootStream {
        private final Map<Long, AtomicInteger> rounds = new ConcurrentHashMap<>();
        private final Map<Long, AtomicLong> sequences = new ConcurrentHashMap<>();
        private final Map<Long, AtomicLong> revisions = new ConcurrentHashMap<>();

        AtomicInteger round(Long runId) {
            return rounds.computeIfAbsent(runId, ignored -> new AtomicInteger());
        }

        int roundValue(Long runId) {
            AtomicInteger value = rounds.get(runId);
            return value == null ? 0 : value.get();
        }

        AtomicLong seq(Long runId) {
            return sequences.computeIfAbsent(runId, ignored -> new AtomicLong());
        }

        long seqValue(Long runId) {
            AtomicLong value = sequences.get(runId);
            return value == null ? 0 : value.get();
        }

        AtomicLong revision(Long runId) {
            return revisions.computeIfAbsent(runId, ignored -> new AtomicLong());
        }

        long revisionValue(Long runId) {
            AtomicLong value = revisions.get(runId);
            return value == null ? 0 : value.get();
        }
    }

    /**
     * 单个运行的正文缓冲与轮次；运行结束后从 live 中移除，工具明细以库为准。
     */
    private static final class LiveRun {
        private final AgentRunEntity run;
        private final Long rootRunId;
        private final RootStream root;
        private final List<Segment> segments = new ArrayList<>();
        private final StringBuilder pending = new StringBuilder();
        private final Object publishLock = new Object();
        private final java.util.ArrayDeque<PendingEvent> pendingPublishes =
            new java.util.ArrayDeque<>();
        private volatile int round;
        private long lastFlushAt = System.currentTimeMillis();
        private volatile boolean closed;

        LiveRun(AgentRunEntity run, RootStream root) {
            this.run = run;
            this.rootRunId = rootRunId(run);
            this.root = root;
        }
    }

    private static final class Segment {
        private final int round;
        private final StringBuilder text = new StringBuilder();

        Segment(int round) {
            this.round = round;
        }
    }

    private record PendingEvent(String type, Map<String, Object> payload) {
    }

    private final class Sink implements RunStreamSink {
        private final LiveRun state;
        // sink 建立时已为第一次模型调用预留轮次，loop 的首个 roundStarted 不再重复发布。
        private boolean roundOpened;

        Sink(LiveRun state, boolean roundOpened) {
            this.state = state;
            this.roundOpened = roundOpened;
        }

        public void roundStarted() {
            synchronized (state) {
                if (state.closed) return;
                if (roundOpened) {
                    roundOpened = false;
                    return;
                }
            }
            beginRound(state);
        }

        public void delta(String text) {
            if (text == null || text.isEmpty()) return;
            synchronized (state) {
                if (state.closed) return;
                state.pending.append(text);
                flush(state, false);
            }
        }

        public void toolStarted(String toolCallId, String toolName, String input) {
            if (state.closed) return;
            int round = state.round;
            String text = truncate(input);
            AgentRunToolEntity row = new AgentRunToolEntity();
            row.setRunId(state.run.getId());
            row.setToolCallId(toolCallId);
            row.setTool(toolName(toolName));
            row.setRound(round);
            row.setState("running");
            row.setInput(text);
            row.setStartedAt(LocalDateTime.now(ZoneOffset.UTC));
            // 先落库再广播：随后读库的快照不会把这个工具当成不存在。
            toolRuns.insert(row);
            synchronized (state) {
                if (state.closed) return;
                flush(state, true);
                long revision = state.root.revision(state.run.getId()).incrementAndGet();
                emit(
                    state,
                    "agent-run.tool",
                    toolPayload(
                        state, "started", toolCallId, toolName, round, null, text, revision));
            }
            drainPublishes(state);
        }

        public void toolFinished(String toolCallId, String toolName, Boolean ok, String output) {
            String text = truncate(output);
            // 运行已结束也要结算：库里残留 running 的会按 done 展示，这里能给出真实结果。
            toolRuns.finish(
                state.run.getId(),
                toolCallId,
                ok == null ? "done" : ok ? "ok" : "failed",
                text,
                LocalDateTime.now(ZoneOffset.UTC));
            if (state.closed) return;
            synchronized (state) {
                if (state.closed) return;
                flush(state, true);
                long revision = state.root.revision(state.run.getId()).incrementAndGet();
                emit(
                    state,
                    "agent-run.tool",
                    toolPayload(
                        state,
                        "finished",
                        toolCallId,
                        toolName,
                        state.round,
                        ok,
                        text,
                        revision));
            }
            drainPublishes(state);
        }

        public void finished() {
            synchronized (state) {
                if (state.closed) return;
                flush(state, true);
                state.closed = true;
                long revision = state.root.revision(state.run.getId()).incrementAndGet();
                emit(
                    state,
                    "agent-run.updated",
                    updatedPayload(state, state.run.getStatus(), revision));
            }
            live.remove(state.run.getId(), state);
            drainPublishes(state);
            if (state.run.getParentRunId() == null) {
                roots.remove(state.rootRunId, state.root);
            }
        }

    }

    private static final class GroupStream {
        private final Long projectId;
        private final Long groupId;
        private final Long userId;
        private final SseEmitter emitter;
        private final BlockingQueue<Frame> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        private final AtomicBoolean closed = new AtomicBoolean();

        GroupStream(Long projectId, Long groupId, Long userId, SseEmitter emitter) {
            this.projectId = projectId;
            this.groupId = groupId;
            this.userId = userId;
            this.emitter = emitter;
        }
    }

    private record Frame(String type, Map<String, Object> payload) {
    }
}
