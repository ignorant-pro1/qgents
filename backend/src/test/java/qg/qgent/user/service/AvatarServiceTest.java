package qg.qgent.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qg.qgent.attachment.dto.UploadCredential;
import qg.qgent.common.storage.AvatarStorageService;
import qg.qgent.support.TestIds;
import qg.qgent.user.dto.AvatarConfirmRequest;
import qg.qgent.user.dto.AvatarConfirmResponse;
import qg.qgent.user.dto.AvatarCredentialResponse;
import qg.qgent.user.entity.UserEntity;
import qg.qgent.user.mapper.UserMapper;

/** AvatarService 头像上传业务测试：凭证透传、确认写入头像 URL 并清理被替换的旧对象。 */
class AvatarServiceTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final AvatarStorageService storage = mock(AvatarStorageService.class);
    private final AvatarService service = new AvatarService(userMapper, storage);

    private final Long userId = TestIds.next();

    @BeforeEach
    void stubUser() {
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setAvatarUrl("https://bucket/avatars/" + userId + "/old.jpg");
        when(userMapper.selectById(userId)).thenReturn(user);
    }

    @Test
    void credentialPassesThroughStorageResult() {
        String objectKey = "avatars/" + userId + "/abc.png";
        when(storage.createCredential(eq("avatars"), eq(userId), eq("image/png"), eq(1024L)))
                .thenReturn(
                        new AvatarStorageService.AvatarCredential(
                                objectKey,
                                new UploadCredential(
                                        "https://put-url", "PUT", Map.of(), LocalDateTime.now())));

        AvatarCredentialResponse response = service.credential(userId, "image/png", 1024L);

        assertThat(response.getObjectKey()).isEqualTo(objectKey);
        assertThat(response.getUploadUrl()).isEqualTo("https://put-url");
    }

    @Test
    void confirmWritesAvatarUrlAndDeletesReplacedObject() {
        String newKey = "avatars/" + userId + "/new.jpg";
        when(storage.confirm("avatars", userId, newKey)).thenReturn("https://public/" + newKey);
        AvatarConfirmRequest request = new AvatarConfirmRequest();
        request.setObjectKey(newKey);

        AvatarConfirmResponse response = service.confirm(userId, request);

        assertThat(response.getAvatarUrl()).isEqualTo("https://public/" + newKey);
        verify(userMapper).update(any(), any());
        // 只按旧 URL 定位旧对象，不再依赖 avatar_object_key 列
        verify(storage)
                .deleteReplacedAfterCommit(
                        "avatars", userId, "https://bucket/avatars/" + userId + "/old.jpg", newKey);
    }
}
