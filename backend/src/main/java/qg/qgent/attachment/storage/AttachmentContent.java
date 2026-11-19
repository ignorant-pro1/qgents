package qg.qgent.attachment.storage;

import java.io.InputStream;

/**
 * 内容流及文件信息，调用方负责关闭流。
 */
public record AttachmentContent(InputStream stream, String contentType, String fileName, Long sizeBytes) {
}
