package qg.qgent.attachment.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 附件和各业务头像共用的 OSS 配置。
 */
@Getter
@Setter
@ConfigurationProperties("aliyun.oss")
public class AliyunOssProperties {

    private boolean enabled = false;

    private String endpoint = "";

    private String bucketName = "";

    private String accessKeyId = "";

    private String accessKeySecret = "";

    private long presignExpirySeconds = 900;

    private String publicBaseUrl = "";

    public boolean configured() {
        return !endpoint.isBlank()
            && !bucketName.isBlank()
            && !accessKeyId.isBlank()
            && !accessKeySecret.isBlank();
    }
}
