package qg.qgent.attachment.storage;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.GetObjectRequest;
import com.aliyun.oss.model.OSSObject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import qg.qgent.attachment.config.AliyunOssProperties;
import qg.qgent.attachment.dto.UploadCredential;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import java.util.OptionalLong;

/**
 * 浏览器使用签名地址直传 OSS；后端读取对象元数据与内容。
 */
@Component
@ConditionalOnProperty(prefix = "aliyun.oss", name = "enabled", havingValue = "true")
public class AliyunOssAttachmentStorage implements AttachmentStorageStrategy {

    private final OSS oss;
    private final AliyunOssProperties properties;

    public AliyunOssAttachmentStorage(OSS oss, AliyunOssProperties properties) {
        this.oss = oss;
        this.properties = properties;
    }

    @Override
    public UploadCredential createCredential(String objectKey, String mediaType) {
        LocalDateTime expiry =
            LocalDateTime.now(ZoneOffset.UTC).plusSeconds(properties.getPresignExpirySeconds());
        GeneratePresignedUrlRequest request =
            new GeneratePresignedUrlRequest(
                properties.getBucketName(), objectKey, HttpMethod.PUT);
        request.setExpiration(Date.from(expiry.toInstant(ZoneOffset.UTC)));
        Map<String, String> headers =
            mediaType == null || mediaType.isBlank()
                ? Map.of()
                : Map.of("Content-Type", mediaType);
        if (!headers.isEmpty()) {
            request.setContentType(mediaType);
        }
        return new UploadCredential(
            oss.generatePresignedUrl(request).toString(), "PUT", headers, expiry);
    }

    @Override
    public OptionalLong objectSize(String objectKey) {
        try {
            return OptionalLong.of(
                oss.getObjectMetadata(properties.getBucketName(), objectKey)
                    .getContentLength());
        } catch (OSSException e) {
            if ("NoSuchKey".equals(e.getErrorCode())) {
                return OptionalLong.empty();
            }
            throw e;
        }
    }

    @Override
    public AttachmentContent loadContent(String objectKey, String fileName, String contentType) {
        OSSObject object = oss.getObject(properties.getBucketName(), objectKey);
        String resolvedType =
            contentType == null || contentType.isBlank()
                ? "application/octet-stream"
                : contentType;
        Long size =
            object.getObjectMetadata() == null
                ? null
                : object.getObjectMetadata().getContentLength();
        return new AttachmentContent(object.getObjectContent(), resolvedType, fileName, size);
    }

    @Override
    public InputStream loadRange(String objectKey, long start, long end) {
        GetObjectRequest request = new GetObjectRequest(properties.getBucketName(), objectKey);
        request.setRange(start, end);
        return oss.getObject(request).getObjectContent();
    }
}
