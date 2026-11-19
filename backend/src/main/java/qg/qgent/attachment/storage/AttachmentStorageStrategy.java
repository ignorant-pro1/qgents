package qg.qgent.attachment.storage;

import org.springframework.http.HttpStatus;
import qg.qgent.attachment.dto.UploadCredential;
import qg.qgent.common.web.ApiException;

import java.io.InputStream;
import java.util.OptionalLong;

/**
 * 本地磁盘和 OSS 共用的附件存取接口；OSS 使用浏览器直传。
 */
public interface AttachmentStorageStrategy {
    UploadCredential createCredential(String objectKey, String mediaType);

    /**
     * 仅本地存储接收后端上传；必须完整写入并校验大小后才公开文件。
     */
    default void storeBytes(String objectKey, InputStream bytes, long expectedSize, long maxSize) {
        throw new ApiException(
            HttpStatus.CONFLICT, "ATTACHMENT_DIRECT_UPLOAD_REQUIRED", "请使用返回的上传地址上传文件");
    }

    OptionalLong objectSize(String objectKey);

    AttachmentContent loadContent(String objectKey, String fileName, String contentType);

    /**
     * 打开闭区间 [start, end] 的内容流，调用方负责关闭。
     */
    InputStream loadRange(String objectKey, long start, long end);
}
