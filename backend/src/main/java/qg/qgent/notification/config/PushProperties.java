package qg.qgent.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 移动端离线推送配置。所有凭证仅允许通过受控环境变量注入。
 */
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "qgents.push")
public class PushProperties {
    private boolean enabled;
    private String fcmEndpoint = "https://fcm.googleapis.com/v1/projects";
    private String fcmProjectId;
    private String fcmServiceAccountJsonBase64;
    private String tokenEncryptionKey;
    private int maxAttempts = 5;

    /**
     * 是否具备真实投递所需配置。
     */
    public boolean deliveryConfigured() {
        return enabled
            && hasText(fcmEndpoint)
            && hasText(fcmProjectId)
            && hasText(fcmServiceAccountJsonBase64)
            && hasText(tokenEncryptionKey);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
