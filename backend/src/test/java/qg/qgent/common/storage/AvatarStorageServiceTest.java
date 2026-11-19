package qg.qgent.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aliyun.oss.OSS;
import java.net.URL;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import qg.qgent.attachment.config.AliyunOssProperties;
import qg.qgent.common.web.ApiException;
import qg.qgent.support.TestIds;

/** 共用头像存储测试：OSS 未启用 501、媒体类型/大小校验、三种对象键前缀、跨主体拒绝、确认与公共读 URL。 */
class AvatarStorageServiceTest {

    private final AliyunOssProperties properties = new AliyunOssProperties();
    private final ObjectProvider<OSS> provider = mock(ObjectProvider.class);
    private final OSS oss = mock(OSS.class);

    private AvatarStorageService disabled() {
        return new AvatarStorageService(provider, properties);
    }

    private AvatarStorageService enabled() {
        properties.setEnabled(true);
        properties.setEndpoint("https://oss-cn-guangzhou.aliyuncs.com");
        properties.setBucketName("my-bucket");
        properties.setAccessKeyId("a");
        properties.setAccessKeySecret("b");
        properties.setPresignExpirySeconds(900);
        properties.setPublicBaseUrl("https://cdn.example.com/");
        when(provider.getObject()).thenReturn(oss);
        return new AvatarStorageService(provider, properties);
    }

    /** enabled() 但不配置公共读基础 URL，用于验证签发/确认时的提前失败。 */
    private AvatarStorageService enabledWithoutPublicUrl() {
        properties.setEnabled(true);
        properties.setEndpoint("https://oss-cn-guangzhou.aliyuncs.com");
        properties.setBucketName("my-bucket");
        properties.setAccessKeyId("a");
        properties.setAccessKeySecret("b");
        properties.setPresignExpirySeconds(900);
        when(provider.getObject()).thenReturn(oss);
        return new AvatarStorageService(provider, properties);
    }

    @Test
    void credentialThrows501WhenOssDisabled() {
        assertThatThrownBy(() ->
                        disabled().createCredential("avatars", TestIds.next(), "image/png", 1000L))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.status()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
                    assertThat(ex.code()).isEqualTo("AVATAR_STORAGE_NOT_CONFIGURED");
                });
    }

    @Test
    void credentialRejectsNonImageMediaType() {
        assertThatThrownBy(() ->
                        enabled().createCredential("avatars", TestIds.next(), "text/plain", 1000L))
                .isInstanceOf(ApiException.class)
                .satisfies(e ->
                        assertThat(((ApiException) e).code())
                                .isEqualTo("AVATAR_MEDIA_TYPE_REJECTED"));
    }

    @Test
    void credentialRejectsOversized() {
        assertThatThrownBy(() ->
                        enabled()
                                .createCredential(
                                        "avatars",
                                        TestIds.next(),
                                        "image/png",
                                        AvatarStorageService.MAX_SIZE_BYTES + 1))
                .isInstanceOf(ApiException.class)
                .satisfies(e ->
                        assertThat(((ApiException) e).status())
                                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
    }

    @Test
    void credentialGeneratesServerSideObjectKeyPerScope() throws Exception {
        when(oss.generatePresignedUrl(
                        eq("my-bucket"), any(String.class), any(java.util.Date.class), any()))
                .thenReturn(new URL("https://put-url"));
        AvatarStorageService storage = enabled();

        for (String scope : List.of("avatars", "teams", "projects")) {
            Long ownerId = TestIds.next();
            AvatarStorageService.AvatarCredential credential =
                    storage.createCredential(scope, ownerId, "image/jpeg", 1000L);

            assertThat(credential.objectKey())
                    .startsWith(scope + "/" + ownerId + "/")
                    .endsWith(".jpg");
            assertThat(credential.credential().getUploadUrl()).isEqualTo("https://put-url");
        }
    }

    @Test
    void confirmRejectsNotUploadedObject() {
        when(oss.doesObjectExist("my-bucket", "avatars/7/x.png")).thenReturn(false);
        assertThatThrownBy(() -> enabled().confirm("avatars", 7L, "avatars/7/x.png"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.code()).isEqualTo("AVATAR_NOT_UPLOADED");
                });
    }

    @Test
    void confirmRejectsObjectKeyOfAnotherScopeOrOwner() {
        Long ownerId = TestIds.next();
        AvatarStorageService storage = enabled();

        for (String foreignKey :
                List.of(
                        "teams/" + ownerId + "/x.png",
                        "avatars/" + TestIds.next() + "/x.png",
                        "projects/" + ownerId + "/x.png")) {
            assertThatThrownBy(() -> storage.confirm("avatars", ownerId, foreignKey))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> {
                        ApiException ex = (ApiException) e;
                        assertThat(ex.status()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(ex.code()).isEqualTo("AVATAR_OBJECT_KEY_FORBIDDEN");
                    });
        }
    }

    @Test
    void credentialThrows501WhenPublicBaseUrlMissing() {
        assertThatThrownBy(() ->
                        enabledWithoutPublicUrl()
                                .createCredential("teams", TestIds.next(), "image/png", 1000L))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.status()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
                    assertThat(ex.code()).isEqualTo("AVATAR_PUBLIC_URL_NOT_CONFIGURED");
                });
    }

    @Test
    void confirmThrows501WhenPublicBaseUrlMissing() {
        when(oss.doesObjectExist("my-bucket", "projects/1/x.png")).thenReturn(true);
        assertThatThrownBy(
                        () -> enabledWithoutPublicUrl().confirm("projects", 1L, "projects/1/x.png"))
                .isInstanceOf(ApiException.class)
                .satisfies(e ->
                        assertThat(((ApiException) e).code())
                                .isEqualTo("AVATAR_PUBLIC_URL_NOT_CONFIGURED"));
    }

    @Test
    void objectKeyOnlyResolvesCurrentPublicUrlAndOwnerPrefix() {
        AvatarStorageService storage = enabled();

        // 公共地址带尾斜杠、URL 带查询参数时仍能还原对象键
        assertThat(storage.objectKey("avatars", 7L, "https://cdn.example.com/avatars/7/a.png?v=1"))
                .isEqualTo("avatars/7/a.png");
        assertThat(storage.objectKey("avatars", 7L, "https://cdn.example.com/avatars/7/a.png"))
                .isEqualTo("avatars/7/a.png");
        // 外链、其他 bucket、其他主体前缀都不处理
        assertThat(storage.objectKey("avatars", 7L, "https://other.example.com/avatars/7/a.png"))
                .isNull();
        assertThat(storage.objectKey("avatars", 7L, "https://cdn.example.com/avatars/8/a.png"))
                .isNull();
        assertThat(storage.objectKey("avatars", 7L, "https://cdn.example.com/teams/7/a.png"))
                .isNull();
        assertThat(storage.objectKey("avatars", 7L, null)).isNull();
    }

    @Test
    void confirmReturnsPublicReadUrl() {
        when(oss.doesObjectExist(eq("my-bucket"), any(String.class))).thenReturn(true);
        assertThat(enabled().confirm("avatars", 1L, "avatars/1/x.png"))
                .isEqualTo("https://cdn.example.com/avatars/1/x.png");
    }
}
