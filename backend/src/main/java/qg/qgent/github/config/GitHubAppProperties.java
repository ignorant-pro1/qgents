package qg.qgent.github.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * GitHub App 的服务端配置；个人 OAuth 不参与此授权流程。
 */
@Component
@Getter
@Setter
@ConfigurationProperties("github.app")
public class GitHubAppProperties {
    private String appId = "";
    private String slug = "";
    private String privateKeyPath = "";
    private String webhookSecret = "";

    public boolean configured() {
        return !appId.isBlank() && !slug.isBlank() && !privateKeyPath.isBlank();
    }
}
