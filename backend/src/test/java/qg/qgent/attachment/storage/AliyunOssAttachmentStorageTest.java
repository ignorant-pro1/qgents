package qg.qgent.attachment.storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.GetObjectRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.attachment.config.AliyunOssProperties;
import qg.qgent.common.web.ApiException;

class AliyunOssAttachmentStorageTest {
    private final OSS oss = mock(OSS.class);
    private final AliyunOssProperties properties = new AliyunOssProperties();
    private final AliyunOssAttachmentStorage storage =
            new AliyunOssAttachmentStorage(oss, properties);

    @Test
    void signsPutForTheObjectAndContentType() throws Exception {
        properties.setBucketName("test-bucket");
        properties.setPresignExpirySeconds(60);
        when(oss.generatePresignedUrl(any(GeneratePresignedUrlRequest.class)))
                .thenReturn(URI.create("https://oss.example/file?signature=test").toURL());
        Instant before = Instant.now();

        var credential = storage.createCredential("projects/p/attachments/a", "image/png");

        var request = ArgumentCaptor.forClass(GeneratePresignedUrlRequest.class);
        verify(oss).generatePresignedUrl(request.capture());
        assertThat(request.getValue().getBucketName()).isEqualTo("test-bucket");
        assertThat(request.getValue().getKey()).isEqualTo("projects/p/attachments/a");
        assertThat(request.getValue().getMethod()).isEqualTo(HttpMethod.PUT);
        assertThat(request.getValue().getContentType()).isEqualTo("image/png");
        assertThat(request.getValue().getExpiration().toInstant())
                .isBetween(before.plusSeconds(59), Instant.now().plusSeconds(61));
        assertThat(credential.getUploadUrl()).startsWith("https://oss.example/");
        assertThat(credential.getHeaders()).containsEntry("Content-Type", "image/png");
        assertThat(credential.getMethod()).isEqualTo("PUT");
    }

    @Test
    void checksObjectSizeWithoutReadingContent() {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(42);
        when(oss.getObjectMetadata(anyString(), eq("key"))).thenReturn(metadata);
        assertThat(storage.objectSize("key")).hasValue(42);
    }

    @Test
    void distinguishesMissingObjectFromOssFailure() {
        OSSException missing = new OSSException("missing", "NoSuchKey", "", "", "", "", "");
        when(oss.getObjectMetadata(anyString(), eq("missing"))).thenThrow(missing);
        assertThat(storage.objectSize("missing")).isEmpty();
        OSSException denied = new OSSException("denied", "AccessDenied", "", "", "", "", "");
        when(oss.getObjectMetadata(anyString(), eq("denied"))).thenThrow(denied);
        assertThatThrownBy(() -> storage.objectSize("denied")).isSameAs(denied);
    }

    @Test
    void rejectsBackendProxyUploads() {
        assertThatThrownBy(() ->
                        storage.storeBytes("key", new ByteArrayInputStream(new byte[1]), 1, 100))
                .isInstanceOf(ApiException.class)
                .satisfies(e ->
                        assertThat(((ApiException) e).code())
                                .isEqualTo("ATTACHMENT_DIRECT_UPLOAD_REQUIRED"));
        verifyNoInteractions(oss);
    }

    @Test
    void readsOssRangeAndReleasesTheStream() throws Exception {
        properties.setBucketName("bucket");
        OSSObject object = new OSSObject();
        var input = spy(new ByteArrayInputStream(new byte[] {1, 2, 3}));
        object.setObjectContent(input);
        when(oss.getObject(any(GetObjectRequest.class))).thenReturn(object);
        try (var stream = storage.loadRange("key", 100, 102)) {
            assertThat(stream.readAllBytes()).containsExactly((byte) 1, (byte) 2, (byte) 3);
        }
        var request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(oss).getObject(request.capture());
        assertThat(request.getValue().getRange()).containsExactly(100L, 102L);
        assertThat(request.getValue().getKey()).isEqualTo("key");
        verify(input).close();
    }
}
