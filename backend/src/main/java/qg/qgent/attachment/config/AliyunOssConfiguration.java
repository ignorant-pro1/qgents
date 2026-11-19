package qg.qgent.attachment.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AliyunOssProperties.class)
public class AliyunOssConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "aliyun.oss", name = "enabled", havingValue = "true")
    public OSS aliyunOssClient(AliyunOssProperties properties) {
        if (!properties.configured()) {
            throw new IllegalStateException(
                "aliyun.oss 已启用但缺少 endpoint/bucket-name/access-key-id/access-key-secret 配置（请通过环境变量设置）");
        }
        return new OSSClientBuilder()
            .build(
                properties.getEndpoint(),
                properties.getAccessKeyId(),
                properties.getAccessKeySecret());
    }
}
