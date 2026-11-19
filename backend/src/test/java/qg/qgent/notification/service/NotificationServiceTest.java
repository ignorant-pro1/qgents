package qg.qgent.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.notification.entity.NotificationEntity;
import qg.qgent.notification.mapper.NotificationMapper;
import qg.qgent.realtime.service.EventService;
import qg.qgent.support.TestIds;

class NotificationServiceTest {

    private final NotificationMapper mapper = mock(NotificationMapper.class);
    private final EventService events = mock(EventService.class);

    @Test
    void notificationPersistsAndPublishesToRecipient() {
        Long recipient = TestIds.next();
        PushNotificationService push = mock(PushNotificationService.class);
        when(mapper.insert(any(NotificationEntity.class)))
                .thenAnswer(invocation -> {
                    NotificationEntity entity = invocation.getArgument(0);
                    entity.setId(TestIds.next());
                    return 1;
                });
        NotificationService service = new NotificationService(mapper, events, push);
        service.notify(recipient, TestIds.next(), null, "INVITED", "团队邀请", "欢迎", null);
        ArgumentCaptor<NotificationEntity> notification =
                ArgumentCaptor.forClass(NotificationEntity.class);
        verify(mapper).insert(notification.capture());
        assertEquals("INVITED", notification.getValue().getKind());
        verify(events).publishNotification(eq(recipient), any(), eq("notification.created"), any());
        verify(push).enqueue(notification.getValue());
    }
}
