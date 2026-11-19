package qg.qgent.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.common.web.ApiException;
import qg.qgent.notification.config.PushProperties;
import qg.qgent.notification.dto.PushDeviceRegistrationRequest;
import qg.qgent.notification.dto.PushDeviceResponse;
import qg.qgent.notification.entity.NotificationEntity;
import qg.qgent.notification.entity.PushDeliveryEntity;
import qg.qgent.notification.entity.PushDeviceEntity;
import qg.qgent.notification.mapper.NotificationMapper;
import qg.qgent.notification.mapper.PushDeliveryMapper;
import qg.qgent.notification.mapper.PushDeviceMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 手机设备注册和推送队列调度；设备凭证加密保存，发送失败按配置重试。
 */
@Service
@RequiredArgsConstructor
public class PushNotificationService {
    private final PushDeviceMapper deviceMapper;
    private final PushDeliveryMapper deliveryMapper;
    private final NotificationMapper notificationMapper;
    private final PushTokenCipher tokenCipher;
    private final FcmPushGateway gateway;
    private final PushProperties properties;

    /**
     * 注册或刷新一个安装实例；相同用户与 installationId 重试只更新同一行。
     */
    @Transactional
    public PushDeviceResponse register(Long userId, PushDeviceRegistrationRequest request) {
        if (!properties.deliveryConfigured()) {
            throw new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE, "PUSH_NOT_CONFIGURED", "离线推送服务尚未配置");
        }
        String installationId = request.getInstallationId().trim();
        String token = request.getToken().trim();
        PushDeviceEntity device = deviceMapper.selectInstallation(userId, installationId);
        boolean newDevice = device == null;
        if (newDevice) {
            device = new PushDeviceEntity();
            device.setUserId(userId);
            device.setInstallationId(installationId);
            device.setProvider("FCM");
        }
        device.setPlatform(request.getPlatform());
        device.setTokenHash(tokenCipher.hash(token));
        device.setTokenCiphertext(tokenCipher.encrypt(token));
        device.setActive(true);
        if (newDevice) {
            try {
                deviceMapper.insert(device);
            } catch (DuplicateKeyException e) {
                throw new ApiException(HttpStatus.CONFLICT, "PUSH_DEVICE_CONFLICT", "设备推送注册冲突，请重试");
            }
        } else {
            deviceMapper.updateById(device);
        }
        return view(device);
    }

    /**
     * 注销安装实例；重复注销不报错。
     */
    @Transactional
    public void unregister(Long userId, String installationId) {
        PushDeviceEntity existing = deviceMapper.selectInstallation(userId, installationId);
        if (existing != null && Boolean.TRUE.equals(existing.getActive())) {
            existing.setActive(false);
            deviceMapper.updateById(existing);
        }
    }

    /**
     * 通知落库后创建每台活动设备一条推送记录（同一通知和设备只保留一条）。
     */
    @Transactional
    public void enqueue(NotificationEntity notification) {
        for (PushDeviceEntity device :
            deviceMapper.selectActiveByUser(notification.getRecipientUserId())) {
            PushDeliveryEntity delivery = new PushDeliveryEntity();
            delivery.setNotificationId(notification.getId());
            delivery.setDeviceId(device.getId());
            delivery.setStatus("PENDING");
            delivery.setAttemptCount(0);
            delivery.setNextAttemptAt(LocalDateTime.now(ZoneOffset.UTC));
            try {
                deliveryMapper.insert(delivery);
            } catch (DuplicateKeyException ignored) {
                // 同一通知和设备只保留一条推送记录。
            }
        }
    }

    /**
     * 定时认领并发送待推送记录；多实例通过条件更新保证同一条记录只有一个发送者。
     */
    @Scheduled(fixedDelayString = "${qgents.push.poll-interval-ms:5000}")
    public void dispatchDue() {
        if (!properties.deliveryConfigured()) return;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        deliveryMapper.recoverStale(now.minusMinutes(5), now);
        int maxAttempts = Math.max(1, Math.min(properties.getMaxAttempts(), 20));
        List<PushDeliveryEntity> due = deliveryMapper.selectDue(now, maxAttempts, 50);
        for (PushDeliveryEntity candidate : due) {
            if (deliveryMapper.claim(candidate.getId(), now) == 0) continue;
            PushDeliveryEntity delivery = deliveryMapper.selectById(candidate.getId());
            PushDeviceEntity device =
                delivery == null ? null : deviceMapper.selectById(delivery.getDeviceId());
            NotificationEntity notification =
                delivery == null
                    ? null
                    : notificationMapper.selectById(delivery.getNotificationId());
            if (delivery == null
                || device == null
                || notification == null
                || !Boolean.TRUE.equals(device.getActive())) {
                deliveryMapper.markFailed(
                    candidate.getId(), "PUSH_TARGET_NOT_FOUND", now.plusDays(3650), now);
                continue;
            }
            try {
                PushSendResult result =
                    gateway.send(
                        tokenCipher.decrypt(device.getTokenCiphertext()), notification);
                deliveryMapper.markSent(delivery.getId(), result.providerMessageId(), now);
            } catch (PushGatewayException e) {
                if (e.isInvalidToken()) {
                    device.setActive(false);
                    deviceMapper.updateById(device);
                }
                int attempt =
                    Math.max(
                        1,
                        delivery.getAttemptCount() == null
                            ? 1
                            : delivery.getAttemptCount());
                long backoffSeconds = Math.min(300, 1L << Math.min(attempt, 8));
                deliveryMapper.markFailed(
                    delivery.getId(), e.getCode(), now.plusSeconds(backoffSeconds), now);
            } catch (RuntimeException e) {
                deliveryMapper.markFailed(
                    delivery.getId(), "PUSH_DELIVERY_FAILED", now.plusMinutes(5), now);
            }
        }
    }

    private PushDeviceResponse view(PushDeviceEntity entity) {
        return new PushDeviceResponse(
            entity.getId().toString(),
            entity.getInstallationId(),
            entity.getPlatform(),
            Boolean.TRUE.equals(entity.getActive()));
    }
}
