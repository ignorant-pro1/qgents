package qg.qgent.attachment.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aliyun.oss.OSS;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import qg.qgent.attachment.config.AliyunOssProperties;

class AttachmentStorageConfigurationTest {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            LocalAttachmentStorage.class, AliyunOssAttachmentStorage.class)
                    .withBean(OSS.class, () -> mock(OSS.class))
                    .withBean(AliyunOssProperties.class, AliyunOssProperties::new);

    @Test
    void defaultsToLocalStorage() {
        context.run(app -> {
            assertThat(app).hasSingleBean(AttachmentStorageStrategy.class);
            assertThat(app.getBean(AttachmentStorageStrategy.class))
                    .isInstanceOf(LocalAttachmentStorage.class);
        });
    }

    @Test
    void enabledOssReplacesLocalStorage() {
        context.withPropertyValues("aliyun.oss.enabled=true")
                .run(app -> {
                    assertThat(app).hasSingleBean(AttachmentStorageStrategy.class);
                    assertThat(app.getBean(AttachmentStorageStrategy.class))
                            .isInstanceOf(AliyunOssAttachmentStorage.class);
                });
    }
}
