package qg.qgent.realtime.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.common.config.PerformanceMetrics;
import qg.qgent.common.web.ApiException;
import qg.qgent.notification.entity.NotificationEventEntity;
import qg.qgent.notification.mapper.NotificationEventMapper;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.mapper.ProjectMemberMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.realtime.entity.EventEntity;
import qg.qgent.realtime.mapper.EventMapper;
import qg.qgent.realtime.transport.SseEventTransport;
import qg.qgent.realtime.websocket.RealtimeFrame;
import qg.qgent.team.entity.TeamEventEntity;
import qg.qgent.team.mapper.TeamEventMapper;
import qg.qgent.team.mapper.TeamMapper;
import qg.qgent.team.mapper.TeamMemberMapper;
import qg.qgent.user.mapper.UserMapper;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 持久化项目、团队和通知事件，校验 SSE 可见范围，并委托传输层管理实时连接。
 */
@Service
public class EventService {
    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    // 单次轮询批量拉取上限。
    private static final int BATCH_SIZE = 100;
    // 事件保留时长（小时），过期事件不再可续传。
    private static final int RETENTION_HOURS = 24;
    // 同一项目事件写入发生死锁时的最大新事务重试次数。
    private static final int EVENT_DEADLOCK_RETRIES = 3;
    // 死锁重试的初始退避时间（毫秒）。
    private static final long EVENT_DEADLOCK_BACKOFF_MS = 50L;

    private final EventMapper eventMapper;
    private final ProjectAccessService projectAccess;
    private final NotificationEventMapper notificationEventMapper;
    private final TeamEventMapper teamEventMapper;
    private final RealtimeHub realtimeTransport;
    private final SseEventTransport sseTransport;
    private final ProjectMemberMapper projectMemberMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final ChatGroupMapper groupMapper;
    private final ProjectMapper projectMapper;
    private final UserMapper userMapper;
    private final TeamMapper teamMapper;
    private final TransactionTemplate eventTransaction;
    private final PerformanceMetrics metrics;
    // 每个 EventService Bean 专用的事务同步资源键，保存待提交的项目事件队列。
    private final Object projectEventQueueResource = new Object();

    @Autowired
    public EventService(
        EventMapper eventMapper,
        ProjectAccessService projectAccess,
        NotificationEventMapper notificationEventMapper,
        TeamEventMapper teamEventMapper,
        RealtimeHub realtimeTransport,
        SseEventTransport sseTransport,
        ProjectMemberMapper projectMemberMapper,
        TeamMemberMapper teamMemberMapper,
        ChatGroupMapper groupMapper,
        ProjectMapper projectMapper,
        UserMapper userMapper,
        TeamMapper teamMapper,
        PlatformTransactionManager transactionManager,
        PerformanceMetrics metrics) {
        this.eventMapper = eventMapper;
        this.projectAccess = projectAccess;
        this.notificationEventMapper = notificationEventMapper;
        this.teamEventMapper = teamEventMapper;
        this.realtimeTransport = realtimeTransport;
        this.sseTransport = sseTransport;
        this.projectMemberMapper = projectMemberMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.groupMapper = groupMapper;
        this.projectMapper = projectMapper;
        this.userMapper = userMapper;
        this.teamMapper = teamMapper;
        this.eventTransaction = new TransactionTemplate(transactionManager);
        // Event persistence must start independently of the business transaction so retries can
        // replay it.
        this.eventTransaction.setPropagationBehavior(
            TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.metrics = metrics;
    }

    /**
     * 业务事务提交后，独立保存项目事件并推送；事件失败不回滚业务。
     */
    public void publish(
        Long projectId,
        Long groupId,
        String eventType,
        String resourceId,
        Map<String, Object> payload) {
        EventRequest request = new EventRequest(projectId, groupId, eventType, resourceId, payload);
        // 业务事实提交后才允许事件落库。这样 events 的行锁、外键检查或重试耗尽都不会把
        // AgentRun 等已经完成的业务写入标记为 rollback-only。
        if (TransactionSynchronizationManager.isActualTransactionActive()
            && TransactionSynchronizationManager.isSynchronizationActive()) {
            enqueueAfterCommit(request);
            return;
        }
        persistAfterCommit(request);
    }

    /**
     * 将同一业务事务中的事件按调用顺序批量挂到唯一的 afterCommit 回调。
     */
    private void enqueueAfterCommit(EventRequest request) {
        EventQueue queue =
            (EventQueue)
                TransactionSynchronizationManager.getResource(projectEventQueueResource);
        if (queue == null) {
            queue = new EventQueue();
            TransactionSynchronizationManager.bindResource(projectEventQueueResource, queue);
            EventQueue queued = queue;
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        // 单条事件的失败已经在 persistAfterCommit 中被隔离；继续投递同批后续事件。
                        for (EventRequest item : queued.requests) {
                            persistAfterCommit(item);
                        }
                    }

                    @Override
                    public void afterCompletion(int status) {
                        if (TransactionSynchronizationManager.hasResource(
                            projectEventQueueResource)) {
                            TransactionSynchronizationManager.unbindResource(
                                projectEventQueueResource);
                        }
                    }
                });
        }
        queue.requests.add(request);
    }

    /**
     * 使用全新短事务写入一条已提交业务事实对应的 SSE 事件，并在成功提交后 fan-out。
     */
    private void persistAfterCommit(EventRequest request) {
        for (int attempt = 0; attempt < EVENT_DEADLOCK_RETRIES; attempt++) {
            try {
                Set<Long> recipients =
                    eventTransaction.execute(status -> persistInNewTransaction(request));
                if (recipients != null) {
                    // TransactionTemplate 已在返回前完成 commit；不得让浏览器看到领先于数据库的刷新信号。
                    broadcast(
                        recipients,
                        "project",
                        id(request.projectId),
                        id(request.groupId),
                        null,
                        null,
                        request.resourceId,
                        request.eventType,
                        request.payload);
                }
                return;
            } catch (RuntimeException failure) {
                boolean deadlock = isDeadlock(failure);
                if (!deadlock || attempt + 1 >= EVENT_DEADLOCK_RETRIES) {
                    metrics.increment(
                        "qgents.sse.event.persist.failures",
                        "event_persist",
                        deadlock ? "deadlock_exhausted" : "failed");
                    // 事件只承担前端刷新提示；业务事实已经提交，不能因其失败被误报为 Agent 失败。
                    log.warn(
                        "project event persistence skipped projectId={} eventType={} attempts={} exceptionType={}",
                        request.projectId,
                        request.eventType,
                        attempt + 1,
                        failure.getClass().getSimpleName());
                    return;
                }
                long backoff = EVENT_DEADLOCK_BACKOFF_MS << attempt;
                metrics.increment(
                    "qgents.sse.event.persist.retries", "event_persist", "deadlock_retry");
                log.warn(
                    "event publish deadlocked; retrying projectId={} eventType={} attempt={} backoffMs={}",
                    request.projectId,
                    request.eventType,
                    attempt + 1,
                    backoff);
                if (!sleepBackoff(backoff)) {
                    metrics.increment(
                        "qgents.sse.event.persist.failures", "event_persist", "interrupted");
                    log.warn(
                        "project event persistence interrupted projectId={} eventType={} attempts={}",
                        request.projectId,
                        request.eventType,
                        attempt + 1);
                    return;
                }
            }
        }
    }

    /**
     * 在 REQUIRES_NEW 事务中分配项目序号、写入事件并返回提交后 fan-out 所需的信息。
     */
    private Set<Long> persistInNewTransaction(EventRequest request) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        EventEntity event = new EventEntity();
        event.setProjectId(request.projectId);
        event.setGroupId(request.groupId);
        // 并发安全：先持有项目行锁，把同项目事件写入串行化；再以锁定读取当前已提交的
        // 最大序号 + 1。缺少项目行锁时，REPEATABLE READ 的普通快照 MAX 会读到旧值，
        // 多个并发发布撞 uk_event_seq 唯一键 → 整体 500（群成员多选邀请等场景）。
        projectMapper.selectByIdForUpdate(request.projectId);
        event.setSequenceNo(eventMapper.nextSequence(request.projectId));
        event.setEventType(request.eventType);
        event.setResourceId(request.resourceId);
        event.setPayload(request.payload);
        event.setCreatedAt(now);
        eventMapper.insert(event);
        if (request.groupId != null) {
            var group = groupMapper.selectById(request.groupId);
            if (group != null && Boolean.FALSE.equals(group.getDefaultGroup())) {
                return Set.copyOf(groupMapper.selectUserIds(request.groupId));
            }
        }
        return projectMembers(request.projectId);
    }

    private boolean isDeadlock(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof DeadlockLoserDataAccessException) {
                return true;
            }
            if (current instanceof SQLException sql
                && ("40001".equals(sql.getSQLState()) || sql.getErrorCode() == 1213)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean sleepBackoff(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Set<Long> projectMembers(Long projectId) {
        return projectMemberMapper.selectMembers(projectId).stream()
            .map(m -> m.getUserId())
            .collect(Collectors.toSet());
    }

    /**
     * 发布一条通知级事件。 按用户维度分配单调递增序号；payload 必须为已脱敏内容。
     */
    @Transactional
    public void publishNotification(
        Long recipientUserId,
        Long notificationId,
        String eventType,
        Map<String, Object> payload) {
        if (recipientUserId == null) {
            return;
        }
        userMapper.selectByIdForUpdate(recipientUserId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        NotificationEventEntity event = new NotificationEventEntity();
        event.setRecipientUserId(recipientUserId);
        event.setSequenceNo(notificationEventMapper.maxSequence(recipientUserId) + 1);
        event.setNotificationId(notificationId);
        event.setKind(eventType);
        event.setPayload(payload);
        event.setCreatedAt(now);
        notificationEventMapper.insert(event);
        // WebSocket 用户级聚合：仅推送该接收用户（其全部在线连接都收到）
        fan(
            Set.of(recipientUserId),
            "notification",
            null,
            null,
            null,
            id(recipientUserId),
            id(notificationId),
            eventType,
            payload);
    }

    /**
     * 订阅当前用户的通知事件，支持 Last-Event-ID 续传。
     */
    public SseEmitter notificationStream(Long userId, Long lastEventId) {
        long cursor =
            resolveCursor(
                lastEventId,
                () -> notificationEventMapper.minSequence(userId),
                () -> notificationEventMapper.maxSequence(userId));
        return sseTransport.open(
            "notification",
            userId,
            cursor,
            after ->
                notificationEventMapper.listAfter(userId, after, BATCH_SIZE).stream()
                    .map(event ->
                        new SseEventTransport.SseEvent(
                            event.getSequenceNo(),
                            notificationEventType(event.getKind()),
                            event.getPayload(),
                            true))
                    .toList(),
            false);
    }

    /**
     * 发布一条团队级事件。 按团队维度分配单调递增序号；payload 必须为已脱敏内容。
     */
    @Transactional
    public void publishTeamEvent(
        Long teamId, String eventType, String resourceId, Map<String, Object> payload) {
        if (teamId == null) {
            return;
        }
        teamMapper.selectByIdForUpdate(teamId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        TeamEventEntity event = new TeamEventEntity();
        event.setTeamId(teamId);
        event.setSequenceNo(teamEventMapper.maxSequence(teamId) + 1);
        event.setEventType(eventType);
        event.setResourceId(resourceId);
        event.setPayload(payload);
        event.setCreatedAt(now);
        teamEventMapper.insert(event);
        // WebSocket 用户级聚合：推送给本团队全部成员（其所有在线连接都收到）
        Set<Long> members =
            teamMemberMapper.selectByTeamId(teamId).stream()
                .map(m -> m.getUserId())
                .collect(Collectors.toSet());
        fan(members, "team", null, null, id(teamId), null, resourceId, eventType, payload);
    }

    /**
     * 订阅团队事件；调用者必须是团队成员。
     */
    public SseEmitter teamStream(Long teamId, Long userId, Long lastEventId) {
        projectAccess.requireTeamMember(teamId, userId);
        long cursor =
            resolveCursor(
                lastEventId,
                () -> teamEventMapper.minSequence(teamId),
                () -> teamEventMapper.maxSequence(teamId));
        return sseTransport.open(
            "team",
            teamId,
            cursor,
            after ->
                teamEventMapper.listAfter(teamId, after, BATCH_SIZE).stream()
                    .map(event ->
                        new SseEventTransport.SseEvent(
                            event.getSequenceNo(),
                            event.getEventType(),
                            event.getPayload(),
                            true))
                    .toList(),
            false);
    }

    /**
     * 订阅项目事件；保留项目群聊过滤和轮询耗时统计。
     */
    public SseEmitter stream(Long projectId, Long userId, Long lastEventId) {
        projectAccess.requireProjectMember(projectId, userId);
        long cursor =
            resolveCursor(
                lastEventId,
                () -> eventMapper.minSequence(projectId),
                () -> eventMapper.maxSequence(projectId));
        return sseTransport.open(
            "project",
            projectId,
            cursor,
            after ->
                eventMapper.listAfter(projectId, after, BATCH_SIZE).stream()
                    .map(event ->
                        new SseEventTransport.SseEvent(
                            event.getSequenceNo(),
                            event.getEventType(),
                            event.getPayload(),
                            event.getGroupId() == null
                                || canSeeGroup(event.getGroupId(), userId)))
                    .toList(),
            true);
    }

    /**
     * 每次读取事件时核对成员，已有连接也能收到刚创建或刚被邀请的群事件。
     */
    private boolean canSeeGroup(Long groupId, Long userId) {
        ChatGroupEntity group = groupMapper.selectById(groupId);
        return group != null
            && (Boolean.TRUE.equals(group.getDefaultGroup())
            || groupMapper.selectUserIds(groupId).contains(userId));
    }

    /**
     * 不传游标时从最新事件开始；无效或过期的续传点拒绝连接。
     */
    private long resolveCursor(Long lastEventId, Supplier<Long> minimum, Supplier<Long> maximum) {
        if (lastEventId == null) return maximum.get();
        if (lastEventId < 0) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST, "INVALID_EVENT_CURSOR", "Last-Event-ID 必须为非负序号");
        }
        Long min = minimum.get();
        if (min == null || min > lastEventId) {
            throw new ApiException(HttpStatus.CONFLICT, "EVENT_CURSOR_EXPIRED", "续传点已过期，请重新拉取相关资源");
        }
        return lastEventId;
    }

    /**
     * 通知 SSE 使用 notification.* 事件名；业务通知类型统一映射为 notification.created。
     */
    private String notificationEventType(String kind) {
        return kind != null && kind.startsWith("notification.") ? kind : "notification.created";
    }

    /**
     * 提交后向目标用户的全部在线连接推送；失败只记日志。
     */
    private void fan(
        Set<Long> userIds,
        String scope,
        String projectId,
        String groupId,
        String teamId,
        String recipientUserId,
        String resourceId,
        String eventType,
        Map<String, Object> payload) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 事务提交前浏览器若据 WS 帧立即查询，可能读不到尚未提交的事件/消息。
            // 只在提交成功后 fan-out，回滚时不发布易失刷新信号。
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        broadcast(
                            userIds,
                            scope,
                            projectId,
                            groupId,
                            teamId,
                            recipientUserId,
                            resourceId,
                            eventType,
                            payload);
                    }
                });
            return;
        }
        broadcast(
            userIds,
            scope,
            projectId,
            groupId,
            teamId,
            recipientUserId,
            resourceId,
            eventType,
            payload);
    }

    private void broadcast(
        Set<Long> userIds,
        String scope,
        String projectId,
        String groupId,
        String teamId,
        String recipientUserId,
        String resourceId,
        String eventType,
        Map<String, Object> payload) {
        try {
            realtimeTransport.broadcastToUsers(
                userIds,
                RealtimeFrame.of(
                    eventType,
                    scope,
                    projectId,
                    groupId,
                    teamId,
                    recipientUserId,
                    resourceId,
                    payload));
        } catch (Exception e) {
            log.warn(
                "realtime fan-out failed, scope={}, eventType={}: {}",
                scope,
                eventType,
                e.getMessage());
        }
    }

    private String id(Long value) {
        return value == null ? null : value.toString();
    }

    /**
     * 单条待持久化的项目 SSE 事件；仅在当前线程的事务同步生命周期内保存。
     */
    private record EventRequest(
        Long projectId,
        Long groupId,
        String eventType,
        String resourceId,
        Map<String, Object> payload) {
    }

    /**
     * 同一业务事务内按调用顺序投递的项目事件集合。
     */
    private static final class EventQueue {
        private final List<EventRequest> requests = new ArrayList<>();
    }

    /**
     * 每日清理超过保留期的项目、团队与通知事件；三条流使用同一保留期。
     */
    @Scheduled(cron = "0 15 3 * * *")
    public void purgeExpired() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusHours(RETENTION_HOURS);
        try {
            int removed =
                eventMapper.deleteExpired(cutoff)
                    + teamEventMapper.deleteExpired(cutoff)
                    + notificationEventMapper.deleteExpired(cutoff);
            if (removed > 0) {
                log.info("purged {} expired events", removed);
            }
        } catch (Exception e) {
            log.warn("event purge failed: {}", e.getMessage());
        }
    }
}
