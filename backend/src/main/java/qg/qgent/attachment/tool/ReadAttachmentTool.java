package qg.qgent.attachment.tool;

import org.springframework.ai.content.Media;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.HttpStatus;
import org.springframework.util.MimeTypeUtils;
import qg.qgent.attachment.service.AttachmentService;
import qg.qgent.common.web.ApiException;
import qg.qgent.harness.tool.ToolResult;

import java.net.URI;
import java.util.Base64;

/**
 * 按需读取群聊附件：文本和代码分段返回，图片暂存到当前 Agent 会话。
 */
public class ReadAttachmentTool {
    public static final int TEXT_CHUNK_BYTES = 64 * 1024;

    private final AttachmentService attachments;
    private final Long actor;
    private final Long projectId;
    private final ImageSink images;

    public ReadAttachmentTool(
        AttachmentService attachments, Long actor, Long projectId, ImageSink images) {
        this.attachments = attachments;
        this.actor = actor;
        this.projectId = projectId;
        this.images = images;
    }

    @Tool(
        name = "read_attachment",
        description =
            "按附件 ID 读取当前项目的图片或文本/代码附件。"
                + "聊天上下文里的图片和文件消息只有引用信息，需要内容时必须调用本工具，不要假设已经看到。"
                + "文本/代码按字节游标分段返回，续读时把 nextOffset 作为 offset；图片会加入下一轮模型请求。")
    public ToolResult readAttachment(
        @ToolParam(description = "附件 ID，来自聊天上下文或 read_chat_message") String attachmentId,
        @ToolParam(description = "文本/代码的字节游标，首次读取省略或传 0，续读传上次返回的 nextOffset", required = false)
        Long offset) {
        Long id = parseId(attachmentId);
        AttachmentService.AttachmentPreviewMetadata meta =
            attachments.previewMetadata(actor, projectId, id);
        if (meta.kind().readableAsImage()) {
            return readImage(id);
        }
        return readText(id, offset == null ? 0 : offset);
    }

    private ToolResult readText(Long id, long offset) {
        AttachmentService.AttachmentTextChunk chunk =
            attachments.readTextChunk(actor, projectId, id, offset, TEXT_CHUNK_BYTES);
        ToolResult result = ToolResult.ok();
        result.put("attachmentId", id.toString());
        result.put("fileName", chunk.fileName());
        result.put("mediaType", chunk.mediaType());
        result.put("sizeBytes", chunk.sizeBytes());
        result.put("offset", chunk.offset());
        result.put("nextOffset", chunk.nextOffset());
        result.put("hasMore", chunk.nextOffset() != null);
        result.put("content", chunk.content());
        return result;
    }

    private ToolResult readImage(Long id) {
        AttachmentService.AttachmentImage image = attachments.readImage(actor, projectId, id);
        var mime = MimeTypeUtils.parseMimeType(image.mediaType());
        String dataUri =
            "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(image.bytes());
        images.stage(id.toString(), image.fileName(), new Media(mime, URI.create(dataUri)));
        ToolResult result = ToolResult.ok();
        result.put("attachmentId", id.toString());
        result.put("fileName", image.fileName());
        result.put("mediaType", image.mediaType());
        result.put("imagePrepared", true);
        result.put("note", "图片已暂存；若后续还有模型调用，会随下一条用户消息附加。当前工具结果不包含图片内容。");
        return result;
    }

    private Long parseId(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_ID_INVALID", "附件 ID 无效");
        }
    }

    @FunctionalInterface
    public interface ImageSink {
        void stage(String attachmentId, String fileName, Media media);
    }
}
