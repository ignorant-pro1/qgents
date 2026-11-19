package qg.qgent.attachment.storage;

import org.springframework.core.io.AbstractResource;

import java.io.InputStream;

/**
 * 延迟打开附件内容，交给 Spring 写出响应并关闭流；范围请求直接交给存储读取。
 */
public class AttachmentResource extends AbstractResource {
    private final AttachmentStorageStrategy storage;
    private final String objectKey;
    private final String fileName;
    private final String contentType;
    private final long size;
    private final Long start;
    private final Long end;

    public AttachmentResource(
        AttachmentStorageStrategy storage,
        String objectKey,
        String fileName,
        String contentType,
        long size) {
        this(storage, objectKey, fileName, contentType, size, null, null);
    }

    private AttachmentResource(
        AttachmentStorageStrategy storage,
        String objectKey,
        String fileName,
        String contentType,
        long size,
        Long start,
        Long end) {
        this.storage = storage;
        this.objectKey = objectKey;
        this.fileName = fileName;
        this.contentType = contentType;
        this.size = size;
        this.start = start;
        this.end = end;
    }

    public AttachmentResource region(long start, long end) {
        return new AttachmentResource(storage, objectKey, fileName, contentType, size, start, end);
    }

    public String contentType() {
        return contentType == null || contentType.isBlank()
            ? "application/octet-stream"
            : contentType;
    }

    @Override
    public String getFilename() {
        return fileName;
    }

    @Override
    public String getDescription() {
        return "Attachment " + fileName;
    }

    @Override
    public long contentLength() {
        return start == null ? size : end - start + 1;
    }

    @Override
    public InputStream getInputStream() {
        return start == null
            ? storage.loadContent(objectKey, fileName, contentType).stream()
            : storage.loadRange(objectKey, start, end);
    }
}
