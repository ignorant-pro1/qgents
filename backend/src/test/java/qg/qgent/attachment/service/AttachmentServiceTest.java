package qg.qgent.attachment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import qg.qgent.attachment.dto.AttachmentCreateRequest;
import qg.qgent.attachment.dto.AttachmentInfoResponse;
import qg.qgent.attachment.dto.AttachmentUploadCredentialResponse;
import qg.qgent.attachment.entity.AttachmentEntity;
import qg.qgent.attachment.mapper.AttachmentMapper;
import qg.qgent.attachment.service.AttachmentService.AttachmentImage;
import qg.qgent.attachment.service.AttachmentService.AttachmentPreviewMetadata;
import qg.qgent.attachment.service.AttachmentService.AttachmentTextChunk;
import qg.qgent.attachment.storage.AttachmentContent;
import qg.qgent.attachment.storage.AttachmentStorageStrategy;
import qg.qgent.attachment.storage.LocalAttachmentStorage;
import qg.qgent.auth.service.TokenService;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.support.TestIds;

/**
 * 附件服务单测：附件信息 / 分段读取 / 图片读取 / previewType 分类 / 鉴权与状态拒绝。
 * <p>
 * 使用真实 TokenService（测试密钥签发短期 access token）与 Mockito 隔离 Mapper/存储/项目访问。
 */
class AttachmentServiceTest {

    private final AttachmentMapper mapper = mock(AttachmentMapper.class);
    private final AttachmentStorageStrategy storage = mock(AttachmentStorageStrategy.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final TokenService tokens =
            new TokenService("01234567890123456789012345678901", 15, 30, 30);
    private final AttachmentService service =
            new AttachmentService(mapper, storage, access, tokens, 52428800);

    @Test
    void infoReturnsMetadataAndTokenizedContentUrlWithoutReadingBytes() {
        Long projectId = TestIds.next();
        Long actor = TestIds.next();
        Long attachmentId = TestIds.next();
        when(mapper.selectById(attachmentId))
                .thenReturn(attachment(attachmentId, projectId, "photo.png", "image/png", "READY"));

        AttachmentInfoResponse r = service.info(actor, projectId, attachmentId);

        assertThat(r.getAttachmentId()).isEqualTo(attachmentId.toString());
        assertThat(r.getFileName()).isEqualTo("photo.png");
        assertThat(r.getMediaType()).isEqualTo("image/png");
        assertThat(r.getPreviewType()).isEqualTo("IMAGE");
        assertThat(r.isPreviewable()).isTrue();
        assertThat(r.getContentUrl())
                .startsWith(
                        "/api/v1/projects/"
                                + projectId
                                + "/attachments/"
                                + attachmentId
                                + "/content?token=");
        String token =
                r.getContentUrl()
                        .substring(r.getContentUrl().indexOf("token=") + "token=".length());
        assertThat(tokens.verifyAccess(token)).isEqualTo(actor);
        assertThat(r.getExpiresAt()).isNotNull();
        verifyNoInteractions(storage);
    }

    @Test
    void infoRejectsNotReady() {
        Long projectId = TestIds.next();
        Long attachmentId = TestIds.next();
        when(mapper.selectById(attachmentId))
                .thenReturn(attachment(attachmentId, projectId, "x.txt", "text/plain", "PENDING"));

        assertThatThrownBy(() -> service.info(TestIds.next(), projectId, attachmentId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_NOT_READY");
                    assertThat(((ApiException) e).status().value()).isEqualTo(409);
                });
    }

    @Test
    void infoRejectsAttachmentNotInProject() {
        Long projectId = TestIds.next();
        Long otherProject = TestIds.next();
        Long attachmentId = TestIds.next();
        when(mapper.selectById(attachmentId))
                .thenReturn(attachment(attachmentId, otherProject, "x.txt", "text/plain", "READY"));

        assertThatThrownBy(() -> service.info(TestIds.next(), projectId, attachmentId))
                .isInstanceOf(ApiException.class)
                .satisfies(e ->
                        assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_NOT_FOUND"));
    }

    @Test
    void infoRejectsMissingAttachment() {
        Long projectId = TestIds.next();
        Long attachmentId = TestIds.next();
        when(mapper.selectById(attachmentId)).thenReturn(null);

        assertThatThrownBy(() -> service.info(TestIds.next(), projectId, attachmentId))
                .isInstanceOf(ApiException.class)
                .satisfies(e ->
                        assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_NOT_FOUND"));
    }

    @Test
    void previewMetadataReturnsTypeAndDeclaredSizeWithoutReadingBytes() {
        Long projectId = TestIds.next();
        Long actor = TestIds.next();
        Long attachmentId = TestIds.next();
        AttachmentEntity entity =
                attachment(attachmentId, projectId, "photo.png", "image/png", "READY");
        entity.setSizeBytes(102400L);
        when(mapper.selectById(attachmentId)).thenReturn(entity);

        AttachmentPreviewMetadata meta = service.previewMetadata(actor, projectId, attachmentId);

        assertThat(meta.kind()).isEqualTo(AttachmentKind.IMAGE);
        assertThat(meta.sizeBytes()).isEqualTo(102400L);
    }

    @Test
    void previewMetadataRejectsNotReady() {
        Long projectId = TestIds.next();
        Long attachmentId = TestIds.next();
        when(mapper.selectById(attachmentId))
                .thenReturn(attachment(attachmentId, projectId, "x.txt", "text/plain", "PENDING"));

        assertThatThrownBy(() -> service.previewMetadata(TestIds.next(), projectId, attachmentId))
                .isInstanceOf(ApiException.class)
                .satisfies(e ->
                        assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_NOT_READY"));
    }

    @Test
    void readTextChunkConcatenatesToOriginalContentAcrossMultibyteBoundaries() {
        Long projectId = TestIds.next();
        Long actor = TestIds.next();
        Long attachmentId = TestIds.next();
        String text = "第一行 abc\n" + "长".repeat(500) + "\n中文🙂结尾";
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        AttachmentEntity entity =
                attachment(attachmentId, projectId, "notes.txt", "text/plain", "READY");
        entity.setSizeBytes((long) payload.length);
        when(mapper.selectById(attachmentId)).thenReturn(entity);
        when(storage.loadRange(eq(entity.getObjectKey()), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    int start = Math.toIntExact(invocation.getArgument(1));
                    int end = Math.toIntExact(invocation.getArgument(2));
                    return new ByteArrayInputStream(payload, start, end - start + 1);
                });

        StringBuilder joined = new StringBuilder();
        Long cursor = 0L;
        int chunks = 0;
        while (cursor != null) {
            AttachmentTextChunk chunk =
                    service.readTextChunk(actor, projectId, attachmentId, cursor, 5);
            assertThat(chunk.offset()).isEqualTo(cursor);
            joined.append(chunk.content());
            cursor = chunk.nextOffset();
            chunks++;
        }

        assertThat(joined.toString()).isEqualTo(text);
        assertThat(chunks).isGreaterThan(1);
    }

    @Test
    void readTextChunkAtEndReturnsEmptyContentWithoutNextCursor() {
        Long projectId = TestIds.next();
        Long attachmentId = TestIds.next();
        AttachmentEntity entity =
                attachment(attachmentId, projectId, "notes.txt", "text/plain", "READY");
        entity.setSizeBytes(5L);
        when(mapper.selectById(attachmentId)).thenReturn(entity);

        AttachmentTextChunk chunk =
                service.readTextChunk(TestIds.next(), projectId, attachmentId, 5, 64);

        assertThat(chunk.content()).isEmpty();
        assertThat(chunk.nextOffset()).isNull();
    }

    @Test
    void readTextChunkRejectsOffsetPastEndAndUnsupportedTypes() {
        Long projectId = TestIds.next();
        Long attachmentId = TestIds.next();
        AttachmentEntity text =
                attachment(attachmentId, projectId, "notes.txt", "text/plain", "READY");
        text.setSizeBytes(5L);
        when(mapper.selectById(attachmentId)).thenReturn(text);

        assertThatThrownBy(
                        () -> service.readTextChunk(TestIds.next(), projectId, attachmentId, 6, 64))
                .isInstanceOf(ApiException.class)
                .satisfies(e ->
                        assertThat(((ApiException) e).code())
                                .isEqualTo("ATTACHMENT_OFFSET_INVALID"));

        AttachmentEntity pdf =
                attachment(attachmentId, projectId, "doc.pdf", "application/pdf", "READY");
        when(mapper.selectById(attachmentId)).thenReturn(pdf);
        assertThatThrownBy(
                        () -> service.readTextChunk(TestIds.next(), projectId, attachmentId, 0, 64))
                .isInstanceOf(ApiException.class)
                .satisfies(e ->
                        assertThat(((ApiException) e).code())
                                .isEqualTo("ATTACHMENT_TYPE_UNSUPPORTED"));
        verify(storage, org.mockito.Mockito.never()).loadRange(any(), anyLong(), anyLong());
    }

    @Test
    void readTextChunkReportsStreamFailure() {
        Long projectId = TestIds.next();
        Long attachmentId = TestIds.next();
        AttachmentEntity entity =
                attachment(attachmentId, projectId, "notes.txt", "text/plain", "READY");
        entity.setSizeBytes(5L);
        when(mapper.selectById(attachmentId)).thenReturn(entity);
        when(storage.loadRange(eq(entity.getObjectKey()), anyLong(), anyLong()))
                .thenReturn(
                        new InputStream() {
                            @Override
                            public int read() throws java.io.IOException {
                                throw new java.io.IOException("broken stream");
                            }
                        });

        assertThatThrownBy(
                        () -> service.readTextChunk(TestIds.next(), projectId, attachmentId, 0, 64))
                .isInstanceOf(ApiException.class)
                .satisfies(e ->
                        assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_READ_FAILED"));
    }

    @Test
    void readImageReturnsSupportedImageBytes() {
        Long projectId = TestIds.next();
        Long attachmentId = TestIds.next();
        byte[] payload = new byte[] {1, 2, 3};
        AttachmentEntity entity =
                attachment(attachmentId, projectId, "photo.png", "image/png", "READY");
        entity.setSizeBytes((long) payload.length);
        when(mapper.selectById(attachmentId)).thenReturn(entity);
        when(storage.loadContent(entity.getObjectKey(), "photo.png", "image/png"))
                .thenReturn(
                        new AttachmentContent(
                                new ByteArrayInputStream(payload), "image/png", "photo.png", 3L));

        AttachmentImage image = service.readImage(TestIds.next(), projectId, attachmentId);

        assertThat(image.fileName()).isEqualTo("photo.png");
        assertThat(image.mediaType()).isEqualTo("image/png");
        assertThat(image.bytes()).isEqualTo(payload);
    }

    @Test
    void readImageRejectsUnsupportedFormatAndOversizedBytes() {
        Long projectId = TestIds.next();
        Long attachmentId = TestIds.next();
        AttachmentEntity svg =
                attachment(attachmentId, projectId, "icon.svg", "image/svg+xml", "READY");
        when(mapper.selectById(attachmentId)).thenReturn(svg);
        assertThatThrownBy(() -> service.readImage(TestIds.next(), projectId, attachmentId))
                .isInstanceOf(ApiException.class)
                .satisfies(e ->
                        assertThat(((ApiException) e).code())
                                .isEqualTo("ATTACHMENT_IMAGE_TYPE_UNSUPPORTED"));

        AttachmentEntity declaredLarge =
                attachment(attachmentId, projectId, "huge.png", "image/png", "READY");
        declaredLarge.setSizeBytes(AttachmentService.IMAGE_BYTES_CAP + 1);
        when(mapper.selectById(attachmentId)).thenReturn(declaredLarge);
        assertThatThrownBy(() -> service.readImage(TestIds.next(), projectId, attachmentId))
                .isInstanceOf(ApiException.class)
                .satisfies(e ->
                        assertThat(((ApiException) e).code())
                                .isEqualTo("ATTACHMENT_IMAGE_TOO_LARGE"));

        AttachmentEntity actualLarge =
                attachment(attachmentId, projectId, "large.png", "image/png", "READY");
        actualLarge.setSizeBytes(3L);
        when(mapper.selectById(attachmentId)).thenReturn(actualLarge);
        when(storage.loadContent(actualLarge.getObjectKey(), "large.png", "image/png"))
                .thenReturn(
                        new AttachmentContent(
                                new ByteArrayInputStream(
                                        new byte[(int) AttachmentService.IMAGE_BYTES_CAP + 1]),
                                "image/png",
                                "large.png",
                                AttachmentService.IMAGE_BYTES_CAP + 1));
        assertThatThrownBy(() -> service.readImage(TestIds.next(), projectId, attachmentId))
                .isInstanceOf(ApiException.class)
                .satisfies(e ->
                        assertThat(((ApiException) e).code())
                                .isEqualTo("ATTACHMENT_IMAGE_TOO_LARGE"));
    }

    @Test
    void uploadBytesStoresPendingAttachmentBytes() {
        Long projectId = TestIds.next();
        Long actor = TestIds.next();
        Long attachmentId = TestIds.next();
        byte[] payload = "hello qwen".getBytes(StandardCharsets.UTF_8);
        when(mapper.selectById(attachmentId))
                .thenReturn(
                        attachment(
                                attachmentId, projectId, "doc.pdf", "application/pdf", "PENDING"));

        service.uploadBytes(actor, projectId, attachmentId, new ByteArrayInputStream(payload));

        verify(storage)
                .storeBytes(
                        eq("projects/" + projectId + "/attachments/" + attachmentId),
                        any(InputStream.class),
                        eq(10L),
                        eq(52428800L));
    }

    @Test
    void uploadBytesRejectsNotPending() {
        Long projectId = TestIds.next();
        Long attachmentId = TestIds.next();
        when(mapper.selectById(attachmentId))
                .thenReturn(attachment(attachmentId, projectId, "x.txt", "text/plain", "READY"));

        assertThatThrownBy(() ->
                        service.uploadBytes(
                                TestIds.next(),
                                projectId,
                                attachmentId,
                                new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_NOT_PENDING");
                    assertThat(((ApiException) e).status().value()).isEqualTo(409);
                });
    }

    @Test
    void uploadBytesRejectsMissingAttachment() {
        Long projectId = TestIds.next();
        Long attachmentId = TestIds.next();
        when(mapper.selectById(attachmentId)).thenReturn(null);

        assertThatThrownBy(() ->
                        service.uploadBytes(
                                TestIds.next(),
                                projectId,
                                attachmentId,
                                new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ApiException.class)
                .satisfies(e ->
                        assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_NOT_FOUND"));
    }

    @Test
    void uploadBytesRejectsExceedingMaxSize(@TempDir Path root) {
        AttachmentService small =
                new AttachmentService(
                        mapper,
                        new LocalAttachmentStorage(root.toString(), 900),
                        access,
                        tokens,
                        10);
        Long projectId = TestIds.next();
        Long attachmentId = TestIds.next();
        byte[] oversized = "more-than-ten-bytes".getBytes(StandardCharsets.UTF_8);
        when(mapper.selectById(attachmentId))
                .thenReturn(
                        attachment(attachmentId, projectId, "big.txt", "text/plain", "PENDING"));

        assertThatThrownBy(() ->
                        small.uploadBytes(
                                TestIds.next(),
                                projectId,
                                attachmentId,
                                new ByteArrayInputStream(oversized)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_TOO_LARGE");
                    assertThat(((ApiException) e).status().value()).isEqualTo(413);
                });
    }

    @Test
    void uploadBytesRejectsNonMember() {
        Long projectId = TestIds.next();
        Long actor = TestIds.next();
        Long attachmentId = TestIds.next();
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问"))
                .when(access)
                .requireProjectMember(projectId, actor);

        assertThatThrownBy(() ->
                        service.uploadBytes(
                                actor,
                                projectId,
                                attachmentId,
                                new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("FORBIDDEN"));
    }

    @Test
    void classifiesPreviewTypesByMediaTypeAndExtension() {
        assertPreviewType("photo.png", "image/png", "IMAGE");
        assertPreviewType("doc.pdf", "application/pdf", "PDF");
        assertPreviewType("notes.txt", "text/plain", "TEXT");
        assertPreviewType("config.json", "application/json", "CODE");
        assertPreviewType("Main.java", null, "CODE");
        assertPreviewType("script.sh", "application/x-sh", "CODE");
        assertPreviewType("readme.md", "text/markdown", "TEXT");
        assertPreviewType("data.bin", "application/octet-stream", "UNSUPPORTED");
    }

    @Test
    void localUploadLifecycleRequiresStoredFileBeforeConfirmation(@TempDir Path root) {
        LocalAttachmentStorage local = new LocalAttachmentStorage(root.toString(), 900);
        AttachmentService localService = new AttachmentService(mapper, local, access, tokens, 1024);
        Long actor = TestIds.next();
        Long projectId = TestIds.next();
        AttachmentCreateRequest body = new AttachmentCreateRequest();
        body.setFileName("notes.txt");
        body.setContentType("text/plain");
        body.setSizeBytes(5L);
        when(mapper.insert(any(AttachmentEntity.class)))
                .thenAnswer(invocation -> {
                    AttachmentEntity entity = invocation.getArgument(0);
                    entity.setId(TestIds.next());
                    when(mapper.selectById(entity.getId())).thenReturn(entity);
                    when(mapper.selectBatchIds(any())).thenReturn(List.of(entity));
                    return 1;
                });

        AttachmentUploadCredentialResponse credential =
                localService.createCredential(actor, projectId, body);
        Long id = Long.valueOf(credential.getAttachmentId());
        assertThat(credential.getUploadUrl())
                .isEqualTo("/api/v1/projects/" + projectId + "/attachments/" + id);
        assertThatThrownBy(() -> localService.confirmUpload(actor, projectId, id))
                .isInstanceOf(ApiException.class)
                .satisfies(e ->
                        assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_NOT_UPLOADED"));

        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        localService.uploadBytes(actor, projectId, id, new ByteArrayInputStream(data));
        assertThat(localService.confirmUpload(actor, projectId, id).getStatus()).isEqualTo("READY");
        assertThat(localService.readTextChunk(actor, projectId, id, 0, 1024).content())
                .isEqualTo("hello");
        assertThat(
                        localService
                                .messagePreviews(projectId, List.of(id.toString()))
                                .get(id.toString())
                                .kind())
                .isEqualTo(AttachmentKind.TEXT);
        assertThat(localService.info(actor, projectId, id).getContentUrl())
                .contains("/content?token=");
    }

    private void assertPreviewType(String fileName, String mediaType, String expected) {
        Long projectId = TestIds.next();
        Long attachmentId = TestIds.next();
        when(mapper.selectById(attachmentId))
                .thenReturn(attachment(attachmentId, projectId, fileName, mediaType, "READY"));
        AttachmentInfoResponse r = service.info(TestIds.next(), projectId, attachmentId);

        assertThat(r.getPreviewType())
                .as("%s (%s) 应为 %s", fileName, mediaType, expected)
                .isEqualTo(expected);
    }

    @Test
    void confirmationChecksStoredSizeBeforeMarkingReady() {
        Long project = TestIds.next(), id = TestIds.next(), actor = TestIds.next();
        AttachmentEntity entity = attachment(id, project, "file.txt", "text/plain", "PENDING");
        when(mapper.selectById(id)).thenReturn(entity);
        when(storage.objectSize(entity.getObjectKey())).thenReturn(java.util.OptionalLong.empty());
        assertThatThrownBy(() -> service.confirmUpload(actor, project, id))
                .isInstanceOf(ApiException.class)
                .satisfies(e ->
                        assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_NOT_UPLOADED"));
        when(storage.objectSize(entity.getObjectKey())).thenReturn(java.util.OptionalLong.of(9));
        assertThatThrownBy(() -> service.confirmUpload(actor, project, id))
                .isInstanceOf(ApiException.class)
                .satisfies(e ->
                        assertThat(((ApiException) e).code())
                                .isEqualTo("ATTACHMENT_SIZE_MISMATCH"));
        when(storage.objectSize(entity.getObjectKey()))
                .thenReturn(java.util.OptionalLong.of(52428801));
        assertThatThrownBy(() -> service.confirmUpload(actor, project, id))
                .isInstanceOf(ApiException.class)
                .satisfies(e ->
                        assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_TOO_LARGE"));
        assertThat(entity.getStatus()).isEqualTo("PENDING");
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never())
                .updateById(any(AttachmentEntity.class));
        when(storage.objectSize(entity.getObjectKey())).thenReturn(java.util.OptionalLong.of(10));
        assertThat(service.confirmUpload(actor, project, id).getStatus()).isEqualTo("READY");
        assertThat(service.confirmUpload(actor, project, id).getStatus()).isEqualTo("READY");
        verify(mapper).updateById(entity);
    }

    private AttachmentEntity attachment(
            Long id, Long projectId, String fileName, String mediaType, String status) {
        AttachmentEntity value = new AttachmentEntity();
        value.setId(id);
        value.setProjectId(projectId);
        value.setObjectKey("projects/" + projectId + "/attachments/" + id);
        value.setFileName(fileName);
        value.setMediaType(mediaType);
        value.setStatus(status);
        value.setSizeBytes(10L);
        return value;
    }
}
