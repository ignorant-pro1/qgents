package qg.qgent.attachment.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import qg.qgent.attachment.service.AttachmentKind;
import qg.qgent.attachment.service.AttachmentService;
import qg.qgent.attachment.service.AttachmentService.AttachmentImage;
import qg.qgent.attachment.service.AttachmentService.AttachmentPreviewMetadata;
import qg.qgent.attachment.service.AttachmentService.AttachmentTextChunk;
import qg.qgent.common.web.ApiException;

class ReadAttachmentToolTest {
    private final AttachmentService attachments = mock(AttachmentService.class);
    private final List<Staged> staged = new ArrayList<>();
    private final ReadAttachmentTool tool =
            new ReadAttachmentTool(
                    attachments,
                    1L,
                    2L,
                    (attachmentId, fileName, media) ->
                            staged.add(new Staged(attachmentId, fileName, media)));

    @Test
    void textAttachmentReturnsChunkWithContinuationCursor() {
        when(attachments.previewMetadata(1L, 2L, 9L))
                .thenReturn(new AttachmentPreviewMetadata(AttachmentKind.TEXT, 100L));
        when(attachments.readTextChunk(1L, 2L, 9L, 0L, ReadAttachmentTool.TEXT_CHUNK_BYTES))
                .thenReturn(
                        new AttachmentTextChunk("notes.txt", "text/plain", 100L, 0L, "hello", 5L));

        Map<String, Object> result = tool.readAttachment("9", null);

        assertThat(result)
                .containsEntry("ok", true)
                .containsEntry("fileName", "notes.txt")
                .containsEntry("content", "hello")
                .containsEntry("offset", 0L)
                .containsEntry("nextOffset", 5L)
                .containsEntry("hasMore", true);
    }

    @Test
    void imageAttachmentIsStagedWithoutPuttingBytesInToolResult() {
        byte[] bytes = "image".getBytes(StandardCharsets.UTF_8);
        when(attachments.previewMetadata(1L, 2L, 9L))
                .thenReturn(new AttachmentPreviewMetadata(AttachmentKind.IMAGE, 5L));
        when(attachments.readImage(1L, 2L, 9L))
                .thenReturn(new AttachmentImage("photo.png", "image/png", bytes));

        Map<String, Object> result = tool.readAttachment("9", null);

        assertThat(result)
                .containsEntry("ok", true)
                .containsEntry("imagePrepared", true)
                .doesNotContainKey("content");
        assertThat(staged).hasSize(1);
        assertThat(staged.getFirst().attachmentId()).isEqualTo("9");
        assertThat(staged.getFirst().fileName()).isEqualTo("photo.png");
        assertThat(staged.getFirst().media().getMimeType().toString()).isEqualTo("image/png");
    }

    @Test
    void unsupportedAttachmentTypePropagatesServiceFailure() {
        when(attachments.previewMetadata(1L, 2L, 9L))
                .thenReturn(new AttachmentPreviewMetadata(AttachmentKind.PDF, 100L));
        when(attachments.readTextChunk(1L, 2L, 9L, 0L, ReadAttachmentTool.TEXT_CHUNK_BYTES))
                .thenThrow(
                        new ApiException(
                                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                                "ATTACHMENT_TYPE_UNSUPPORTED",
                                "暂不支持读取该附件类型（PDF）"));

        assertThatThrownBy(() -> tool.readAttachment("9", null))
                .isInstanceOf(ApiException.class)
                .satisfies(exception ->
                        assertThat(((ApiException) exception).code())
                                .isEqualTo("ATTACHMENT_TYPE_UNSUPPORTED"));
        assertThat(staged).isEmpty();
    }

    @Test
    void invalidAttachmentIdIsRejectedBeforeReading() {
        assertThatThrownBy(() -> tool.readAttachment("not-an-id", null))
                .isInstanceOf(ApiException.class)
                .satisfies(exception ->
                        assertThat(((ApiException) exception).code())
                                .isEqualTo("ATTACHMENT_ID_INVALID"));
        verifyNoInteractions(attachments);
    }

    @Test
    void offsetIsPassedThroughForContinuation() {
        when(attachments.previewMetadata(1L, 2L, 9L))
                .thenReturn(new AttachmentPreviewMetadata(AttachmentKind.CODE, 100L));
        when(attachments.readTextChunk(1L, 2L, 9L, 5L, ReadAttachmentTool.TEXT_CHUNK_BYTES))
                .thenReturn(
                        new AttachmentTextChunk(
                                "Main.java", "text/x-java", 100L, 5L, "tail", null));

        Map<String, Object> result = tool.readAttachment("9", 5L);

        assertThat(result).containsEntry("content", "tail").containsEntry("hasMore", false);
        assertThat(result.get("nextOffset")).isNull();
        verify(attachments).readTextChunk(1L, 2L, 9L, 5L, ReadAttachmentTool.TEXT_CHUNK_BYTES);
    }

    private record Staged(
            String attachmentId, String fileName, org.springframework.ai.content.Media media) {}
}
