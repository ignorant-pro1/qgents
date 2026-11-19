package qg.qgent.attachment.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.attachment.dto.*;
import qg.qgent.attachment.entity.AttachmentEntity;
import qg.qgent.attachment.mapper.AttachmentMapper;
import qg.qgent.attachment.storage.AttachmentResource;
import qg.qgent.attachment.storage.AttachmentStorageStrategy;
import qg.qgent.auth.service.TokenService;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.service.ProjectAccessService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * 文件存储独立于附件元数据；上传确认后可供消息展示和 Agent 读取。
 */
@Service
public class AttachmentService {
    private final AttachmentMapper attachmentMapper;
    private final AttachmentStorageStrategy storage;
    private final ProjectAccessService access;
    private final TokenService tokens;
    private final long maxSizeBytes;

    public AttachmentService(
        AttachmentMapper attachmentMapper,
        AttachmentStorageStrategy storage,
        ProjectAccessService access,
        TokenService tokens,
        @Value("${app.attachment-max-size-bytes:52428800}") long maxSizeBytes) {
        this.attachmentMapper = attachmentMapper;
        this.storage = storage;
        this.access = access;
        this.tokens = tokens;
        this.maxSizeBytes = maxSizeBytes;
    }

    /**
     * 先保存 PENDING 记录，再按存储配置返回 OSS 直传或本地上传地址。
     */
    @Transactional
    public AttachmentUploadCredentialResponse createCredential(
        Long actor, Long projectId, AttachmentCreateRequest body) {
        access.requireProjectMember(projectId, actor);
        long sizeBytes = body.getSizeBytes();
        if (sizeBytes <= 0) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST, "ATTACHMENT_SIZE_REQUIRED", "文件大小必须大于 0");
        }
        if (sizeBytes > maxSizeBytes) {
            throw new ApiException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "ATTACHMENT_TOO_LARGE",
                "文件大小超过上限 " + maxSizeBytes + " 字节");
        }
        AttachmentEntity attachment = new AttachmentEntity();
        attachment.setProjectId(projectId);
        attachment.setUploadedBy(actor);
        attachment.setFileName(body.getFileName().trim());
        attachment.setMediaType(body.getContentType());
        attachment.setSizeBytes(sizeBytes);
        attachment.setStatus("PENDING");
        // 对象键包含附件自增 ID，先插入取回 ID 再补写。
        attachmentMapper.insert(attachment);
        attachment.setObjectKey("projects/" + projectId + "/attachments/" + attachment.getId());
        attachmentMapper.updateById(attachment);

        UploadCredential credential =
            storage.createCredential(attachment.getObjectKey(), attachment.getMediaType());
        return new AttachmentUploadCredentialResponse(attachment.getId().toString(), credential);
    }

    @Transactional
    public AttachmentStatusResponse confirmUpload(Long actor, Long projectId, Long attachmentId) {
        access.requireProjectMember(projectId, actor);
        AttachmentEntity attachment = requireAttachment(projectId, attachmentId);
        if ("READY".equals(attachment.getStatus())) {
            return new AttachmentStatusResponse(attachment.getId().toString(), "READY");
        }
        if (!"PENDING".equals(attachment.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_NOT_PENDING", "附件已确认或不可确认");
        }
        var actualSize = storage.objectSize(attachment.getObjectKey());
        if (actualSize.isEmpty()) {
            throw new ApiException(
                HttpStatus.CONFLICT, "ATTACHMENT_NOT_UPLOADED", "附件尚未上传，请先上传再确认");
        }
        if (actualSize.getAsLong() > maxSizeBytes) {
            throw new ApiException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "ATTACHMENT_TOO_LARGE",
                "文件大小超过上限 " + maxSizeBytes + " 字节");
        }
        if (actualSize.getAsLong() != attachment.getSizeBytes()) {
            throw new ApiException(
                HttpStatus.CONFLICT, "ATTACHMENT_SIZE_MISMATCH", "实际文件大小与申请上传时不一致");
        }
        attachment.setStatus("READY");
        attachmentMapper.updateById(attachment);
        return new AttachmentStatusResponse(attachment.getId().toString(), attachment.getStatus());
    }

    public void uploadBytes(Long actor, Long projectId, Long attachmentId, InputStream bytes) {
        access.requireProjectMember(projectId, actor);
        AttachmentEntity attachment = requireAttachment(projectId, attachmentId);
        if (!"PENDING".equals(attachment.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_NOT_PENDING", "附件不可上传（状态非待上传）");
        }
        storage.storeBytes(
            attachment.getObjectKey(), bytes, attachment.getSizeBytes(), maxSizeBytes);
    }

    /**
     * 附件元数据与短期内容地址；不读取文件字节。
     */
    public AttachmentInfoResponse info(Long actor, Long projectId, Long attachmentId) {
        AttachmentEntity attachment = requireReadyAttachment(actor, projectId, attachmentId);
        AttachmentKind kind = kind(attachment);
        String contentUrl =
            "/api/v1/projects/"
                + projectId
                + "/attachments/"
                + attachmentId
                + "/content?token="
                + tokens.access(actor);
        LocalDateTime expiresAt =
            LocalDateTime.now(ZoneOffset.UTC).plusSeconds(tokens.accessSeconds());
        return new AttachmentInfoResponse(
            attachment.getId().toString(),
            attachment.getFileName(),
            attachment.getMediaType(),
            attachment.getSizeBytes(),
            kind.name(),
            kind.previewable(),
            contentUrl,
            expiresAt);
    }

    /**
     * Agent 按需读取前，先用类型和大小判断能否处理，不读取字节。
     */
    public AttachmentPreviewMetadata previewMetadata(
        Long actor, Long projectId, Long attachmentId) {
        AttachmentEntity attachment = requireReadyAttachment(actor, projectId, attachmentId);
        return new AttachmentPreviewMetadata(kind(attachment), attachment.getSizeBytes());
    }

    /**
     * 批量回填消息附件预览：无效、跨项目或未就绪的 ID 不出现在结果里。
     */
    public Map<String, MessageAttachmentPreview> messagePreviews(
        Long projectId, Collection<String> attachmentIds) {
        Set<Long> ids = new LinkedHashSet<>();
        for (String attachmentId : attachmentIds) {
            try {
                ids.add(Long.valueOf(attachmentId));
            } catch (NumberFormatException ignored) {
                // 消息正文里的附件 ID 非法时只是没有预览，不影响消息本身
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, MessageAttachmentPreview> previews = new LinkedHashMap<>();
        for (AttachmentEntity attachment : attachmentMapper.selectBatchIds(ids)) {
            if (!projectId.equals(attachment.getProjectId())
                || !"READY".equals(attachment.getStatus())) {
                continue;
            }
            previews.put(
                attachment.getId().toString(), new MessageAttachmentPreview(kind(attachment)));
        }
        return previews;
    }

    /**
     * 按字节游标读取文本或代码附件；返回内容始终停在完整 UTF-8 字符边界。
     */
    public AttachmentTextChunk readTextChunk(
        Long actor, Long projectId, Long attachmentId, long offset, int maxBytes) {
        if (maxBytes < 4) throw new IllegalArgumentException("maxBytes 至少为 4，避免 UTF-8 字符无法前进");
        AttachmentEntity attachment = requireReadyAttachment(actor, projectId, attachmentId);
        AttachmentKind kind = kind(attachment);
        if (!kind.readableAsText()) {
            throw new ApiException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "ATTACHMENT_TYPE_UNSUPPORTED",
                "暂不支持读取该附件类型（" + kind + "），仅支持文本和代码");
        }
        long size = attachment.getSizeBytes();
        if (offset < 0 || offset > size) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "ATTACHMENT_OFFSET_INVALID",
                "读取位置无效，附件大小为 " + size + " 字节");
        }
        if (offset == size) {
            return new AttachmentTextChunk(
                attachment.getFileName(), attachment.getMediaType(), size, offset, "", null);
        }
        long end = Math.min(offset + maxBytes - 1, size - 1);
        byte[] bytes;
        try (InputStream stream = storage.loadRange(attachment.getObjectKey(), offset, end)) {
            bytes = stream.readNBytes(maxBytes);
        } catch (IOException e) {
            throw new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR, "ATTACHMENT_READ_FAILED", "读取附件内容失败");
        }
        if (bytes.length == 0) {
            throw new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR, "ATTACHMENT_READ_FAILED", "附件内容为空或不可读");
        }
        int length = utf8CompleteLength(bytes);
        // 极端无效 UTF-8 下没有完整字符边界，也要前进，避免同一游标反复返回。
        if (length == 0) length = bytes.length;
        long nextOffset = offset + length;
        return new AttachmentTextChunk(
            attachment.getFileName(),
            attachment.getMediaType(),
            size,
            offset,
            new String(bytes, 0, length, StandardCharsets.UTF_8),
            nextOffset < size ? nextOffset : null);
    }

    /**
     * 读取单张图片；类型须为当前模型接口可处理的格式，字节数在读取时再次限制。
     */
    public AttachmentImage readImage(Long actor, Long projectId, Long attachmentId) {
        AttachmentEntity attachment = requireReadyAttachment(actor, projectId, attachmentId);
        if (!kind(attachment).readableAsImage()) {
            throw new ApiException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "ATTACHMENT_TYPE_UNSUPPORTED",
                "附件不是图片，无法作为图片读取");
        }
        String mediaType =
            attachment.getMediaType() == null
                ? ""
                : attachment.getMediaType().toLowerCase(Locale.ROOT).trim();
        if (!SUPPORTED_IMAGE_MEDIA_TYPES.contains(mediaType)) {
            throw new ApiException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "ATTACHMENT_IMAGE_TYPE_UNSUPPORTED",
                "当前模型接口不支持图片格式 " + mediaType + "，支持 png/jpeg/gif/webp");
        }
        if (attachment.getSizeBytes() > IMAGE_BYTES_CAP) {
            throw new ApiException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "ATTACHMENT_IMAGE_TOO_LARGE",
                "图片超过 " + IMAGE_BYTES_CAP + " 字节上限");
        }
        byte[] bytes;
        try (InputStream stream =
                 storage.loadContent(
                         attachment.getObjectKey(),
                         attachment.getFileName(),
                         attachment.getMediaType())
                     .stream()) {
            bytes = stream.readNBytes((int) IMAGE_BYTES_CAP + 1);
        } catch (IOException e) {
            throw new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR, "ATTACHMENT_READ_FAILED", "读取附件内容失败");
        }
        if (bytes.length > IMAGE_BYTES_CAP) {
            throw new ApiException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "ATTACHMENT_IMAGE_TOO_LARGE",
                "图片超过 " + IMAGE_BYTES_CAP + " 字节上限");
        }
        return new AttachmentImage(attachment.getFileName(), mediaType, bytes);
    }

    /**
     * 截掉末尾不完整的 UTF-8 多字节序列；无效字节按替换字符解码。
     */
    private static int utf8CompleteLength(byte[] bytes) {
        int end = bytes.length;
        int lead = end - 1;
        while (lead >= 0 && end - lead <= 4 && (bytes[lead] & 0xC0) == 0x80) {
            lead--;
        }
        if (lead < 0) return end;
        int value = bytes[lead] & 0xFF;
        int expected =
            value < 0x80
                ? 1
                : (value >> 5) == 0x6
                  ? 2
                  : (value >> 4) == 0xE ? 3 : (value >> 3) == 0x1E ? 4 : 1;
        return end - lead < expected ? lead : end;
    }

    /**
     * 统一内容读取入口；预览和下载共用同一份元数据与存储流。
     */
    public AttachmentContentResource contentResource(
        Long actor, Long projectId, Long attachmentId) {
        AttachmentEntity attachment = requireReadyAttachment(actor, projectId, attachmentId);
        long size =
            storage.objectSize(attachment.getObjectKey())
                .orElseThrow(() ->
                    new ApiException(
                        HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "附件文件不存在"));
        return new AttachmentContentResource(
            new AttachmentResource(
                storage,
                attachment.getObjectKey(),
                attachment.getFileName(),
                attachment.getMediaType(),
                size),
            kind(attachment));
    }

    public record AttachmentContentResource(AttachmentResource resource, AttachmentKind kind) {
    }

    public record AttachmentTextChunk(
        String fileName,
        String mediaType,
        long sizeBytes,
        long offset,
        String content,
        Long nextOffset) {
    }

    public record AttachmentImage(String fileName, String mediaType, byte[] bytes) {
    }

    // 单张图片字节上限；超限时工具返回明确失败，不加入模型请求。
    public static final long IMAGE_BYTES_CAP = 3_900_000L;

    private static final Set<String> SUPPORTED_IMAGE_MEDIA_TYPES =
        Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    private static AttachmentKind kind(AttachmentEntity attachment) {
        return AttachmentKind.from(attachment.getMediaType(), attachment.getFileName());
    }

    public record AttachmentPreviewMetadata(AttachmentKind kind, Long sizeBytes) {
    }

    public record MessageAttachmentPreview(AttachmentKind kind) {
    }

    private AttachmentEntity requireReadyAttachment(Long actor, Long projectId, Long attachmentId) {
        access.requireProjectMember(projectId, actor);
        AttachmentEntity attachment = requireAttachment(projectId, attachmentId);
        if (!"READY".equals(attachment.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_NOT_READY", "附件尚未上传完成");
        }
        return attachment;
    }

    private AttachmentEntity requireAttachment(Long projectId, Long attachmentId) {
        AttachmentEntity attachment = attachmentMapper.selectById(attachmentId);
        if (attachment == null || !projectId.equals(attachment.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "附件不存在或无权访问");
        }
        return attachment;
    }
}
