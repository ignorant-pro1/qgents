package qg.qgent.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.common.storage.AvatarStorageService;
import qg.qgent.common.web.ApiException;
import qg.qgent.user.dto.AvatarConfirmRequest;
import qg.qgent.user.dto.AvatarConfirmResponse;
import qg.qgent.user.dto.AvatarCredentialResponse;
import qg.qgent.user.entity.UserEntity;
import qg.qgent.user.mapper.UserMapper;

/**
 * 用户头像：签发凭证、确认上传后写入头像 URL 并删除被替换的旧对象。
 */
@Service
@RequiredArgsConstructor
public class AvatarService {

    private final UserMapper userMapper;
    private final AvatarStorageService storage;

    /**
     * 为当前用户签发头像直传凭证。
     */
    public AvatarCredentialResponse credential(Long userId, String mediaType, Long sizeBytes) {
        AvatarStorageService.AvatarCredential credential =
            storage.createCredential("avatars", userId, mediaType, sizeBytes);
        return new AvatarCredentialResponse(credential);
    }

    /**
     * 确认头像上传：校验对象属于当前用户且已真实上传，写入头像 URL 并在提交后删除旧对象。
     */
    @Transactional
    public AvatarConfirmResponse confirm(Long userId, AvatarConfirmRequest body) {
        String objectKey = body.getObjectKey();
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在");
        }
        String publicUrl = storage.confirm("avatars", userId, objectKey);
        userMapper.update(
            null,
            Wrappers.<UserEntity>lambdaUpdate()
                .set(UserEntity::getAvatarUrl, publicUrl)
                .eq(UserEntity::getId, userId));
        storage.deleteReplacedAfterCommit("avatars", userId, user.getAvatarUrl(), objectKey);
        return new AvatarConfirmResponse(publicUrl);
    }
}
