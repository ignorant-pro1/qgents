package qg.qgent.common.storage;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import qg.qgent.attachment.config.AliyunOssProperties;
import qg.qgent.attachment.dto.UploadCredential;
import qg.qgent.common.web.ApiException;

import java.net.URL;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * 用户、团队和项目共用的头像对象存储：签发直传凭证、确认上传并返回公共读 URL。
 */
@Component
@RequiredArgsConstructor
public class AvatarStorageService {
    private static final Logger log = LoggerFactory.getLogger(AvatarStorageService.class);

    /**
     * 头像对象大小上限（字节），前端应同步限制图片体积。
     */
    public static final long MAX_SIZE_BYTES = 5 * 1024 * 1024;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    private final ObjectProvider<OSS> ossProvider;
    private final AliyunOssProperties properties;

    /**
     * 签发直传凭证；对象键为 {@code scope/ownerId/uuid.ext}，scope 为 avatars、teams 或 projects。
     */
    public AvatarCredential createCredential(
        String scope, Long ownerId, String mediaType, Long sizeBytes) {
        OSS oss = requireConfigured();
        requirePublicUrl(); // 缺公共读基础 URL 时先失败，避免客户端已经直传对象后才在确认时报错
        String normalizedMediaType =
            mediaType == null ? "" : mediaType.trim().toLowerCase(Locale.ROOT);
        if (!normalizedMediaType.startsWith("image/")) {
            throw new ApiException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "AVATAR_MEDIA_TYPE_REJECTED",
                "头像必须是图片（image/*）");
        }
        if (sizeBytes == null || sizeBytes <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AVATAR_SIZE_REQUIRED", "头像大小必须大于 0");
        }
        if (sizeBytes > MAX_SIZE_BYTES) {
            throw new ApiException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "AVATAR_TOO_LARGE",
                "头像大小超过上限 " + MAX_SIZE_BYTES + " 字节");
        }
        String objectKey =
            scope
                + "/"
                + ownerId
                + "/"
                + UUID.randomUUID()
                + "."
                + extensionFor(normalizedMediaType);
        Date expiresAt =
            new Date(System.currentTimeMillis() + properties.getPresignExpirySeconds() * 1000L);
        URL url =
            oss.generatePresignedUrl(
                properties.getBucketName(), objectKey, expiresAt, HttpMethod.PUT);
        LocalDateTime expiry = LocalDateTime.ofInstant(expiresAt.toInstant(), ZoneOffset.UTC);
        return new AvatarCredential(
            objectKey, new UploadCredential(url.toString(), "PUT", Map.of(), expiry));
    }

    /**
     * 确认头像已上传，返回其长期公共读 URL；对象键必须属于该主体命名空间（未上传返回 409）。
     */
    public String confirm(String scope, Long ownerId, String objectKey) {
        requireConfigured();
        if (objectKey == null || objectKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AVATAR_OBJECT_KEY_REQUIRED", "缺少头像对象键");
        }
        if (!objectKey.startsWith(scope + "/" + ownerId + "/")) {
            throw new ApiException(
                HttpStatus.FORBIDDEN, "AVATAR_OBJECT_KEY_FORBIDDEN", "头像对象键不属于当前主体");
        }
        if (!ossProvider.getObject().doesObjectExist(properties.getBucketName(), objectKey)) {
            throw new ApiException(
                HttpStatus.CONFLICT, "AVATAR_NOT_UPLOADED", "头像尚未上传到对象存储，请先上传再确认");
        }
        requirePublicUrl();
        return properties.getPublicBaseUrl().replaceAll("/+$", "") + "/" + objectKey;
    }

    /**
     * 从公共读 URL 还原对象键：只识别当前配置的公共地址和该主体的对象前缀，
     * 外链、旧域名或其他 bucket 返回 null（不删除无法归属的对象）。
     */
    public String objectKey(String scope, Long ownerId, String avatarUrl) {
        String base = properties.getPublicBaseUrl();
        if (avatarUrl == null || base == null || base.isBlank()) {
            return null;
        }
        String prefix = base.replaceAll("/+$", "") + "/";
        if (!avatarUrl.startsWith(prefix)) {
            return null;
        }
        String objectKey = avatarUrl.substring(prefix.length()).replaceFirst("[?#].*$", "");
        return objectKey.startsWith(scope + "/" + ownerId + "/") ? objectKey : null;
    }

    /**
     * 事务提交后删除被替换或清空的旧头像，数据库回滚时不会提前删除对象。
     */
    public void deleteReplacedAfterCommit(
        String scope, Long ownerId, String oldUrl, String keepKey) {
        String objectKey = objectKey(scope, ownerId, oldUrl);
        if (objectKey == null || objectKey.equals(keepKey)) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        deleteObject(objectKey);
                    }
                });
        } else {
            deleteObject(objectKey);
        }
    }

    /**
     * 删除旧头像对象；OSS 未启用时 no-op，删除失败只记录日志，不影响已生效的新头像。
     */
    public void deleteObject(String objectKey) {
        if (!properties.isEnabled()
            || properties.getBucketName().isBlank()
            || objectKey == null
            || objectKey.isBlank()) {
            return;
        }
        try {
            ossProvider.getObject().deleteObject(properties.getBucketName(), objectKey);
        } catch (RuntimeException e) {
            log.warn(
                "Avatar object deletion failed: objectKey={}, error={}",
                objectKey,
                e.getMessage());
        }
    }

    private OSS requireConfigured() {
        if (!properties.isEnabled() || !properties.configured()) {
            throw new ApiException(
                HttpStatus.NOT_IMPLEMENTED,
                "AVATAR_STORAGE_NOT_CONFIGURED",
                "头像上传需要启用阿里云 OSS，当前未配置");
        }
        return ossProvider.getObject();
    }

    /**
     * 检查头像公共访问地址是否已配置。
     */
    private void requirePublicUrl() {
        if (properties.getPublicBaseUrl() == null || properties.getPublicBaseUrl().isBlank()) {
            throw new ApiException(
                HttpStatus.NOT_IMPLEMENTED,
                "AVATAR_PUBLIC_URL_NOT_CONFIGURED",
                "未配置头像公共访问基础 URL（aliyun.oss.public-base-url），"
                    + "请设置环境变量 ALIYUN_OSS_PUBLIC_BASE_URL，如 https://<bucket>.<endpoint>");
        }
    }

    private String extensionFor(String mediaType) {
        String subtype =
            mediaType.startsWith("image/") ? mediaType.substring("image/".length()).trim() : "";
        String ext = subtype.equals("jpeg") ? "jpg" : subtype;
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new ApiException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "AVATAR_MEDIA_TYPE_REJECTED",
                "不支持的图片类型: " + mediaType);
        }
        return ext;
    }

    /**
     * 头像直传凭证：服务端签发的对象键与其对应预签名上传凭证。
     */
    public record AvatarCredential(String objectKey, UploadCredential credential) {
    }
}
