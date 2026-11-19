package qg.qgent.notification.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import qg.qgent.notification.config.PushProperties;
import qg.qgent.notification.dto.PushDeviceRegistrationRequest;
import qg.qgent.notification.dto.PushDeviceResponse;
import qg.qgent.notification.entity.NotificationEntity;
import qg.qgent.notification.entity.PushDeliveryEntity;
import qg.qgent.notification.entity.PushDeviceEntity;
import qg.qgent.notification.mapper.NotificationMapper;
import qg.qgent.notification.mapper.PushDeliveryMapper;
import qg.qgent.notification.mapper.PushDeviceMapper;
import qg.qgent.support.TestIds;

/** 验证设备注册、推送入队去重和配置关闭时的行为。 */
class PushNotificationServiceTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void registrationCreatesOrReactivatesTheSameInstallation(boolean newDevice) {
        Long userId = TestIds.next();
        PushDeviceMapper devices = mock(PushDeviceMapper.class);
        PushTokenCipher cipher = mock(PushTokenCipher.class);
        PushProperties properties = mock(PushProperties.class);
        when(properties.deliveryConfigured()).thenReturn(true);
        when(cipher.hash("token")).thenReturn("hash");
        when(cipher.encrypt("token")).thenReturn("encrypted");
        PushDeviceEntity existing = new PushDeviceEntity();
        existing.setId(TestIds.next());
        existing.setUserId(userId);
        existing.setInstallationId("installation");
        existing.setProvider("FCM");
        existing.setActive(false);
        if (!newDevice) {
            when(devices.selectInstallation(userId, "installation")).thenReturn(existing);
        }
        when(devices.insert(any(PushDeviceEntity.class)))
                .thenAnswer(invocation -> {
                    PushDeviceEntity entity = invocation.getArgument(0);
                    entity.setId(TestIds.next());
                    return 1;
                });
        PushDeviceRegistrationRequest request = new PushDeviceRegistrationRequest();
        request.setInstallationId(" installation ");
        request.setPlatform("ANDROID");
        request.setToken(" token ");
        PushNotificationService service =
                new PushNotificationService(
                        devices,
                        mock(PushDeliveryMapper.class),
                        mock(NotificationMapper.class),
                        cipher,
                        mock(FcmPushGateway.class),
                        properties);

        PushDeviceResponse response = service.register(userId, request);

        ArgumentCaptor<PushDeviceEntity> saved = ArgumentCaptor.forClass(PushDeviceEntity.class);
        if (newDevice) {
            verify(devices).insert(saved.capture());
            verify(devices, never()).updateById(any(PushDeviceEntity.class));
        } else {
            verify(devices).updateById(saved.capture());
            verify(devices, never()).insert(any(PushDeviceEntity.class));
            assertEquals(existing.getId().toString(), response.getId());
        }
        PushDeviceEntity device = saved.getValue();
        assertNotNull(device.getId());
        assertEquals(userId, device.getUserId());
        assertEquals("installation", device.getInstallationId());
        assertEquals("FCM", device.getProvider());
        assertEquals("ANDROID", device.getPlatform());
        assertEquals("hash", device.getTokenHash());
        assertEquals("encrypted", device.getTokenCiphertext());
        assertTrue(response.isActive());
    }

    @Test
    void duplicateOutboxInsertIsTreatedAsIdempotentRetry() {
        PushDeviceMapper devices = mock(PushDeviceMapper.class);
        PushDeliveryMapper deliveries = mock(PushDeliveryMapper.class);
        NotificationEntity notification = notification();
        PushDeviceEntity device = new PushDeviceEntity();
        device.setId(TestIds.next());
        when(devices.selectActiveByUser(notification.getRecipientUserId()))
                .thenReturn(List.of(device));
        when(deliveries.insert(any(PushDeliveryEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        PushNotificationService service = service(devices, deliveries, mock(FcmPushGateway.class));

        service.enqueue(notification);

        verify(deliveries).insert(any(PushDeliveryEntity.class));
    }

    @Test
    void disabledProviderDoesNotClaimOutboxRows() {
        PushDeliveryMapper deliveries = mock(PushDeliveryMapper.class);
        PushProperties properties = new PushProperties();
        PushNotificationService service =
                new PushNotificationService(
                        mock(PushDeviceMapper.class),
                        deliveries,
                        mock(NotificationMapper.class),
                        mock(PushTokenCipher.class),
                        mock(FcmPushGateway.class),
                        properties);

        service.dispatchDue();

        verify(deliveries, never()).selectDue(any(), anyInt(), anyInt());
    }

    private PushNotificationService service(
            PushDeviceMapper devices, PushDeliveryMapper deliveries, FcmPushGateway gateway) {
        return new PushNotificationService(
                devices,
                deliveries,
                mock(NotificationMapper.class),
                mock(PushTokenCipher.class),
                gateway,
                new PushProperties());
    }

    private NotificationEntity notification() {
        NotificationEntity notification = new NotificationEntity();
        notification.setId(TestIds.next());
        notification.setRecipientUserId(TestIds.next());
        return notification;
    }
}
