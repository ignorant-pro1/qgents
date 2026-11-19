package qg.qgent.realtime.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import qg.qgent.support.TestIds;
import qg.qgent.team.entity.TeamEventEntity;
import qg.qgent.team.mapper.TeamEventMapper;
import qg.qgent.team.mapper.TeamMapper;
import qg.qgent.team.mapper.TeamMemberMapper;
import qg.qgent.user.mapper.UserMapper;

/** Publishing and subscription behavior at the realtime domain boundary. */
class EventServiceTest {

    @Test
    void projectPublisherPersistsAnEventThenSendsItToTheCurrentGroupMembers() {
        Fixture fixture = new Fixture();
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        Long invitedUser = TestIds.next();
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setDefaultGroup(false);
        when(fixture.events.nextSequence(projectId)).thenReturn(9L);
        when(fixture.groups.selectById(groupId)).thenReturn(group);
        when(fixture.groups.selectUserIds(groupId)).thenReturn(List.of(invitedUser));

        EventService publisher = fixture.publisher();
        publisher.publish(
                projectId,
                groupId,
                "group.created",
                groupId.toString(),
                Map.of("name", "planning"));

        verify(fixture.projects).selectByIdForUpdate(projectId);
        verify(fixture.events, never()).maxSequence(any());
        var persisted = org.mockito.ArgumentCaptor.forClass(EventEntity.class);
        verify(fixture.events).insert(persisted.capture());
        assertEquals(9L, persisted.getValue().getSequenceNo());
        assertEquals(projectId, persisted.getValue().getProjectId());
        assertEquals("group.created", persisted.getValue().getEventType());
        assertNotNull(persisted.getValue().getCreatedAt());
        verify(fixture.events).nextSequence(projectId);

        var frame = org.mockito.ArgumentCaptor.forClass(RealtimeFrame.class);
        verify(fixture.realtime).broadcastToUsers(eq(Set.of(invitedUser)), frame.capture());
        assertEquals("project", frame.getValue().scope());
        assertEquals(projectId.toString(), frame.getValue().projectId());
        assertEquals(groupId.toString(), frame.getValue().groupId());
        assertEquals("group.created", frame.getValue().type());
        var order = inOrder(fixture.transactions, fixture.realtime);
        order.verify(fixture.transactions).commit(any(TransactionStatus.class));
        order.verify(fixture.realtime).broadcastToUsers(any(), any(RealtimeFrame.class));
        verifyNoMoreInteractions(fixture.events);
    }

    @Test
    void projectPublisherWaitsForBusinessCommitAndKeepsInvocationOrder() {
        Fixture fixture = new Fixture();
        Long projectId = TestIds.next();
        when(fixture.events.nextSequence(projectId)).thenReturn(1L, 2L);
        fixture.startBusinessTransaction();
        try {
            EventService publisher = fixture.publisher();
            publisher.publish(projectId, null, "project.updated", "project-1", Map.of("name", "A"));
            publisher.publish(
                    projectId, null, "project.member.added", "member-1", Map.of("userId", "u1"));

            verify(fixture.events, never()).insert(any(EventEntity.class));
            verifyNoInteractions(fixture.realtime);
            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());

            fixture.commitBusinessTransaction();

            var persisted = org.mockito.ArgumentCaptor.forClass(EventEntity.class);
            verify(fixture.events, org.mockito.Mockito.times(2)).insert(persisted.capture());
            assertEquals(
                    List.of("project.updated", "project.member.added"),
                    persisted.getAllValues().stream().map(EventEntity::getEventType).toList());
            var frames = org.mockito.ArgumentCaptor.forClass(RealtimeFrame.class);
            verify(fixture.realtime, org.mockito.Mockito.times(2))
                    .broadcastToUsers(any(), frames.capture());
            assertEquals(
                    List.of("project.updated", "project.member.added"),
                    frames.getAllValues().stream().map(RealtimeFrame::type).toList());
        } finally {
            fixture.clearBusinessTransaction();
        }
    }

    @Test
    void projectStreamRechecksGroupMembershipForEachLoadedBatch() {
        Fixture fixture = new Fixture();
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        Long userId = TestIds.next();
        when(fixture.events.maxSequence(projectId)).thenReturn(3L);
        EventEntity groupEvent = new EventEntity();
        groupEvent.setSequenceNo(4L);
        groupEvent.setGroupId(groupId);
        groupEvent.setEventType("message.created");
        when(fixture.events.listAfter(eq(projectId), anyLong(), anyInt()))
                .thenReturn(List.of(groupEvent));
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setDefaultGroup(false);
        when(fixture.groups.selectById(groupId)).thenReturn(group);
        when(fixture.groups.selectUserIds(groupId)).thenReturn(List.of());

        fixture.service().stream(projectId, userId, null);

        var loader = org.mockito.ArgumentCaptor.forClass(LongFunction.class);
        verify(fixture.sse).open(eq("project"), eq(projectId), eq(3L), loader.capture(), eq(true));
        @SuppressWarnings("unchecked")
        LongFunction<List<SseEventTransport.SseEvent>> loadEvents = loader.getValue();
        assertEquals(4L, loadEvents.apply(3L).getFirst().sequenceNo());
        assertEquals(false, loadEvents.apply(3L).getFirst().visible());

        when(fixture.groups.selectUserIds(groupId)).thenReturn(List.of(userId));
        assertEquals(true, loadEvents.apply(4L).getFirst().visible());
    }

    @ParameterizedTest
    @ValueSource(strings = {"project", "team", "notification"})
    void streamsRejectNegativeAndExpiredCursorsBeforeOpeningAConnection(String scope) {
        Fixture fixture = new Fixture();
        when(fixture.events.minSequence(any())).thenReturn(2L);
        when(fixture.notifications.minSequence(any())).thenReturn(2L);
        when(fixture.teams.minSequence(any())).thenReturn(2L);
        EventService service = fixture.service();
        Long ownerId = TestIds.next();
        Long userId = TestIds.next();
        java.util.function.Function<Long, SseEmitter> connect =
                cursor ->
                        switch (scope) {
                            case "project" -> service.stream(ownerId, userId, cursor);
                            case "team" -> service.teamStream(ownerId, userId, cursor);
                            default -> service.notificationStream(userId, cursor);
                        };

        ApiException negative = assertThrows(ApiException.class, () -> connect.apply(-1L));
        assertEquals("INVALID_EVENT_CURSOR", negative.code());
        ApiException expired = assertThrows(ApiException.class, () -> connect.apply(1L));
        assertEquals("EVENT_CURSOR_EXPIRED", expired.code());
        verifyNoInteractions(fixture.sse);
    }

    @Test
    void notificationStreamUsesProtocolEventNamesForLegacyKinds() {
        Fixture fixture = new Fixture();
        Long userId = TestIds.next();
        when(fixture.notifications.minSequence(userId)).thenReturn(1L);
        NotificationEventEntity legacy = new NotificationEventEntity();
        legacy.setSequenceNo(2L);
        legacy.setKind("INVITED");
        NotificationEventEntity removed = new NotificationEventEntity();
        removed.setSequenceNo(3L);
        removed.setKind("notification.removed");
        when(fixture.notifications.listAfter(eq(userId), eq(1L), anyInt()))
                .thenReturn(List.of(legacy, removed));

        fixture.service().notificationStream(userId, 1L);

        var loader = org.mockito.ArgumentCaptor.forClass(LongFunction.class);
        verify(fixture.sse)
                .open(eq("notification"), eq(userId), eq(1L), loader.capture(), eq(false));
        @SuppressWarnings("unchecked")
        LongFunction<List<SseEventTransport.SseEvent>> loadEvents = loader.getValue();
        assertEquals(
                List.of("notification.created", "notification.removed"),
                loadEvents.apply(1L).stream().map(SseEventTransport.SseEvent::type).toList());
    }

    @Test
    void notificationAndTeamFramesAreDeliveredAfterTheirTransactionCommits() {
        Fixture fixture = new Fixture();
        Long userId = TestIds.next();
        Long notificationId = TestIds.next();
        Long teamId = TestIds.next();
        when(fixture.notifications.maxSequence(userId)).thenReturn(4L);
        when(fixture.teams.maxSequence(teamId)).thenReturn(7L);
        fixture.startBusinessTransaction();
        try {
            EventService publisher = fixture.publisher();
            publisher.publishNotification(
                    userId, notificationId, "notification.created", Map.of("title", "Hello"));
            publisher.publishTeamEvent(
                    teamId, "activity.created", "resource-1", Map.of("ok", true));

            verify(fixture.users).selectByIdForUpdate(userId);
            verify(fixture.teamRows).selectByIdForUpdate(teamId);
            var notification = org.mockito.ArgumentCaptor.forClass(NotificationEventEntity.class);
            verify(fixture.notifications).insert(notification.capture());
            assertEquals(5L, notification.getValue().getSequenceNo());
            var teamEvent = org.mockito.ArgumentCaptor.forClass(TeamEventEntity.class);
            verify(fixture.teams).insert(teamEvent.capture());
            assertEquals(8L, teamEvent.getValue().getSequenceNo());
            verifyNoInteractions(fixture.realtime);

            fixture.commitBusinessTransaction();

            var recipients = org.mockito.ArgumentCaptor.forClass(Set.class);
            var frames = org.mockito.ArgumentCaptor.forClass(RealtimeFrame.class);
            verify(fixture.realtime, org.mockito.Mockito.times(2))
                    .broadcastToUsers(recipients.capture(), frames.capture());
            assertEquals(List.of(Set.of(userId), Set.of()), recipients.getAllValues());
            assertEquals(
                    List.of("notification", "team"),
                    frames.getAllValues().stream().map(RealtimeFrame::scope).toList());
            assertEquals(userId.toString(), frames.getAllValues().getFirst().recipientUserId());
            assertEquals(teamId.toString(), frames.getAllValues().get(1).teamId());
        } finally {
            fixture.clearBusinessTransaction();
        }
    }

    @Test
    void projectEventDeadlockRetriesTheWholeRequiresNewTransaction() {
        Fixture fixture = new Fixture();
        Long projectId = TestIds.next();
        when(fixture.events.insert(any(EventEntity.class)))
                .thenThrow(new DeadlockLoserDataAccessException("deadlock", null))
                .thenThrow(new DeadlockLoserDataAccessException("deadlock", null))
                .thenReturn(1);
        when(fixture.events.nextSequence(projectId)).thenReturn(1L);

        fixture.publisher()
                .publish(projectId, null, "agent-run.updated", "run-1", Map.of("status", "FAILED"));

        var definitions = org.mockito.ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(fixture.transactions, org.mockito.Mockito.times(3))
                .getTransaction(definitions.capture());
        assertTrue(
                definitions.getAllValues().stream()
                        .allMatch(definition ->
                                definition.getPropagationBehavior()
                                        == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        verify(fixture.events, org.mockito.Mockito.times(3)).insert(any(EventEntity.class));
        verify(fixture.projects, org.mockito.Mockito.times(3)).selectByIdForUpdate(projectId);
        verify(fixture.transactions).commit(any(TransactionStatus.class));
    }

    @Test
    void purgeExpiredCleansAllThreeEventLogsWithOneCutoff() {
        Fixture fixture = new Fixture();
        when(fixture.events.deleteExpired(any())).thenReturn(3);
        when(fixture.teams.deleteExpired(any())).thenReturn(2);
        when(fixture.notifications.deleteExpired(any())).thenReturn(1);

        fixture.service().purgeExpired();

        var cutoff = org.mockito.ArgumentCaptor.forClass(java.time.LocalDateTime.class);
        verify(fixture.events).deleteExpired(cutoff.capture());
        verify(fixture.teams).deleteExpired(cutoff.capture());
        verify(fixture.notifications).deleteExpired(cutoff.capture());
        assertTrue(cutoff.getAllValues().stream().allMatch(cutoff.getValue()::equals));
        verifyNoMoreInteractions(fixture.events);
    }

    private static final class Fixture {
        private final EventMapper events = mock(EventMapper.class);
        private final ProjectAccessService access = mock(ProjectAccessService.class);
        private final NotificationEventMapper notifications = mock(NotificationEventMapper.class);
        private final TeamEventMapper teams = mock(TeamEventMapper.class);
        private final RealtimeHub realtime = mock(RealtimeHub.class);
        private final SseEventTransport sse = mock(SseEventTransport.class);
        private final ProjectMemberMapper projectMembers = mock(ProjectMemberMapper.class);
        private final TeamMemberMapper teamMembers = mock(TeamMemberMapper.class);
        private final ChatGroupMapper groups = mock(ChatGroupMapper.class);
        private final ProjectMapper projects = mock(ProjectMapper.class);
        private final UserMapper users = mock(UserMapper.class);
        private final TeamMapper teamRows = mock(TeamMapper.class);
        private final PlatformTransactionManager transactions = transactionManager();

        private Fixture() {
            when(projectMembers.selectMembers(any())).thenReturn(List.of());
            when(teamMembers.selectByTeamId(any())).thenReturn(List.of());
        }

        private EventService service() {
            return new EventService(
                    events,
                    access,
                    notifications,
                    teams,
                    realtime,
                    sse,
                    projectMembers,
                    teamMembers,
                    groups,
                    projects,
                    users,
                    teamRows,
                    transactions,
                    mock(PerformanceMetrics.class));
        }

        private EventService publisher() {
            return service();
        }

        private void startBusinessTransaction() {
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
        }

        private void commitBusinessTransaction() {
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            synchronizations.forEach(TransactionSynchronization::afterCommit);
            synchronizations.forEach(
                    sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        }

        private void clearBusinessTransaction() {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.getSynchronizations()
                        .forEach(sync ->
                                sync.afterCompletion(
                                        TransactionSynchronization.STATUS_ROLLED_BACK));
                TransactionSynchronizationManager.clearSynchronization();
            }
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        private static PlatformTransactionManager transactionManager() {
            PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
            when(transactions.getTransaction(any(TransactionDefinition.class)))
                    .thenAnswer(invocation -> new SimpleTransactionStatus());
            doNothing().when(transactions).commit(any(TransactionStatus.class));
            doNothing().when(transactions).rollback(any(TransactionStatus.class));
            return transactions;
        }
    }
}
