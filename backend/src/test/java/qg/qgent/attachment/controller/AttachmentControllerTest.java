package qg.qgent.attachment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.module.SimpleModule;
import jakarta.servlet.Filter;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import qg.qgent.attachment.entity.AttachmentEntity;
import qg.qgent.attachment.mapper.AttachmentMapper;
import qg.qgent.attachment.service.AttachmentService;
import qg.qgent.attachment.storage.AttachmentContent;
import qg.qgent.attachment.storage.AttachmentStorageStrategy;
import qg.qgent.attachment.web.AttachmentResponseFactory;
import qg.qgent.auth.config.SecurityConfiguration;
import qg.qgent.auth.security.JwtAuthenticationFilter;
import qg.qgent.auth.service.TokenService;
import qg.qgent.common.web.ApiException;
import qg.qgent.common.web.GlobalExceptionHandler;
import qg.qgent.common.web.RequestIdFilter;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.support.TestIds;

/** 经过实际 Spring MVC 与项目安全过滤链验证附件信息、统一内容、范围、鉴权和资源关闭。 */
class AttachmentControllerTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private AttachmentStorageStrategy storage;
    private AttachmentEntity attachment;
    private final Long actor = TestIds.next(), project = TestIds.next(), id = TestIds.next();
    private final byte[] bytes = "hello qwen".getBytes(StandardCharsets.UTF_8);
    private final AtomicBoolean closed = new AtomicBoolean();

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "test", Map.of("app.cors-allowed-origins", "http://localhost")));
        context.register(WebConfig.class);
        context.refresh();
        mvc =
                MockMvcBuilders.webAppContextSetup(context)
                        .addFilters(
                                new RequestIdFilter(),
                                context.getBean("springSecurityFilterChain", Filter.class))
                        .build();
        storage = context.getBean(AttachmentStorageStrategy.class);
        attachment = new AttachmentEntity();
        attachment.setId(id);
        attachment.setProjectId(project);
        attachment.setObjectKey("key");
        attachment.setFileName("notes.txt");
        attachment.setMediaType("text/plain");
        attachment.setSizeBytes((long) bytes.length);
        attachment.setStatus("READY");
        when(context.getBean(AttachmentMapper.class).selectById(id)).thenReturn(attachment);
        when(storage.objectSize("key")).thenReturn(OptionalLong.of(bytes.length));
        when(storage.loadContent(any(), any(), any()))
                .thenAnswer(invocation ->
                        new AttachmentContent(
                                stream(bytes),
                                attachment.getMediaType(),
                                attachment.getFileName(),
                                (long) bytes.length));
        when(storage.loadRange(eq("key"), anyLong(), anyLong()))
                .thenAnswer(invocation ->
                        stream(
                                Arrays.copyOfRange(
                                        bytes,
                                        ((Long) invocation.getArgument(1)).intValue(),
                                        ((Long) invocation.getArgument(2)).intValue() + 1)));
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    private ByteArrayInputStream stream(byte[] data) {
        return new ByteArrayInputStream(data) {
            @Override
            public void close() {
                closed.set(true);
            }
        };
    }

    private String path() {
        return "/api/v1/projects/" + project + "/attachments/" + id;
    }

    private String contentPath() {
        return path() + "/content";
    }

    private String token() {
        return context.getBean(TokenService.class).access(actor);
    }

    @Test
    void infoReturnsMetadataAndTokenUrlWithoutOpeningFile() throws Exception {
        mvc.perform(get(path()).header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attachmentId").value(id.toString()))
                .andExpect(jsonPath("$.data.fileName").value("notes.txt"))
                .andExpect(jsonPath("$.data.mediaType").value("text/plain"))
                .andExpect(jsonPath("$.data.sizeBytes").value(bytes.length))
                .andExpect(jsonPath("$.data.previewType").value("TEXT"))
                .andExpect(jsonPath("$.data.previewable").value(true))
                .andExpect(
                        jsonPath("$.data.contentUrl")
                                .value(
                                        org.hamcrest.Matchers.startsWith(
                                                path() + "/content?token=")))
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty());
        verifyNoInteractions(storage);
    }

    @Test
    void infoRejectsQueryTokenAndPendingAttachment() throws Exception {
        mvc.perform(get(path()).param("token", token())).andExpect(status().isUnauthorized());

        attachment.setStatus("PENDING");
        mvc.perform(get(path()).header("Authorization", "Bearer " + token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ATTACHMENT_NOT_READY"));
        verifyNoInteractions(storage);
    }

    @Test
    void fullContentUsesBearerAndClosesStream() throws Exception {
        mvc.perform(get(contentPath()).header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes))
                .andExpect(header().string("Content-Length", "10"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Content-Type", "text/plain;charset=utf-8"))
                .andExpect(
                        header().string(
                                        "Content-Disposition",
                                        org.hamcrest.Matchers.startsWith("inline;")));
        assertThat(closed).isTrue();
        verify(storage).loadContent("key", "notes.txt", "text/plain");
        verify(storage, never()).loadRange(any(), anyLong(), anyLong());
    }

    @ParameterizedTest
    @CsvSource({
        "bytes=0-4,0,4,hello",
        "bytes=6-,6,9,qwen",
        "bytes=-4,6,9,qwen",
        "bytes=0-999,0,9,hello qwen"
    })
    void rangeUsesQueryTokenAndReadsOnlyRequestedRegion(
            String range, long start, long end, String expected) throws Exception {
        mvc.perform(get(contentPath()).param("token", token()).header("Range", range))
                .andExpect(status().isPartialContent())
                .andExpect(content().string(expected))
                .andExpect(header().string("Content-Range", "bytes " + start + "-" + end + "/10"))
                .andExpect(header().string("Content-Length", Long.toString(end - start + 1)));
        verify(storage).loadRange("key", start, end);
        verify(storage, never()).loadContent(any(), any(), any());
        assertThat(closed).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "bytes=10-",
                "bytes=4-2",
                "bytes=-0",
                "bytes=",
                "items=0-2",
                "bytes=0-1,3-4",
                "bytes=abc",
                "bytes=999999999999999999999999-"
            })
    void rejectsInvalidRangesWithoutOpeningContent(String range) throws Exception {
        mvc.perform(get(contentPath()).param("token", token()).header("Range", range))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Content-Range", "bytes */10"))
                .andExpect(jsonPath("$.error.code").value("ATTACHMENT_RANGE_INVALID"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        verify(storage, never()).loadContent(any(), any(), any());
        verify(storage, never()).loadRange(any(), anyLong(), anyLong());
    }

    @Test
    void rejectsMissingAndInvalidCredentials() throws Exception {
        mvc.perform(get(contentPath())).andExpect(status().isUnauthorized());
        mvc.perform(get(contentPath()).param("token", "forged"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(storage);
    }

    @Test
    void rejectsNonMembersBeforeOpeningContent() throws Exception {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问"))
                .when(context.getBean(ProjectAccessService.class))
                .requireProjectMember(project, actor);
        mvc.perform(get(contentPath()).param("token", token())).andExpect(status().isForbidden());
        verifyNoInteractions(storage);
    }

    @Test
    void rejectsPendingAttachment() throws Exception {
        attachment.setStatus("PENDING");
        mvc.perform(get(contentPath()).param("token", token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ATTACHMENT_NOT_READY"));
        verifyNoInteractions(storage);
    }

    @Test
    void downloadUsesAttachmentDispositionAndOriginalFilename() throws Exception {
        mvc.perform(
                        get(contentPath())
                                .param("download", "1")
                                .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes))
                .andExpect(header().string("Content-Type", "text/plain"))
                .andExpect(
                        header().string(
                                        "Content-Disposition",
                                        org.hamcrest.Matchers.allOf(
                                                org.hamcrest.Matchers.startsWith("attachment;"),
                                                org.hamcrest.Matchers.containsString(
                                                        "notes.txt"))));
        assertThat(closed).isTrue();
    }

    @Test
    void codePreviewUsesPlainTextAndDownloadKeepsOriginalType() throws Exception {
        attachment.setFileName("Main.java");
        attachment.setMediaType("application/octet-stream");
        mvc.perform(get(contentPath()).param("token", token()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/plain;charset=utf-8"))
                .andExpect(
                        header().string(
                                        "Content-Disposition",
                                        org.hamcrest.Matchers.startsWith("inline;")));
        mvc.perform(get(contentPath()).param("token", token()).param("download", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(
                        header().string(
                                        "Content-Disposition",
                                        org.hamcrest.Matchers.startsWith("attachment;")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/png", "application/pdf"})
    void mediaPreviewsRemainInline(String type) throws Exception {
        attachment.setMediaType(type);
        mvc.perform(get(contentPath()).param("token", token()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes))
                .andExpect(header().string("Content-Type", type))
                .andExpect(
                        header().string(
                                        "Content-Disposition",
                                        org.hamcrest.Matchers.startsWith("inline;")));
    }

    @Test
    void missingFileDoesNotOpenContent() throws Exception {
        when(storage.objectSize("key")).thenReturn(OptionalLong.empty());
        mvc.perform(get(contentPath()).param("token", token())).andExpect(status().isNotFound());
        verify(storage, never()).loadContent(any(), any(), any());
    }

    @Test
    void emptyFileRejectsRangeWithoutOpeningContent() throws Exception {
        when(storage.objectSize("key")).thenReturn(OptionalLong.of(0));
        mvc.perform(get(contentPath()).param("token", token()).header("Range", "bytes=0-"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Content-Range", "bytes */0"));
        verify(storage, never()).loadRange(any(), anyLong(), anyLong());
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({
        SecurityConfiguration.class,
        JwtAuthenticationFilter.class,
        AttachmentController.class,
        AttachmentResponseFactory.class,
        GlobalExceptionHandler.class
    })
    static class WebConfig {
        @Bean
        TokenService tokens() {
            return new TokenService("01234567890123456789012345678901", 15, 30, 30);
        }

        @Bean
        AttachmentMapper mapper() {
            return mock(AttachmentMapper.class);
        }

        @Bean
        AttachmentStorageStrategy storage() {
            return mock(AttachmentStorageStrategy.class);
        }

        @Bean
        ProjectAccessService access() {
            return mock(ProjectAccessService.class);
        }

        @Bean
        com.fasterxml.jackson.databind.Module utcLocalDateTimeModule() {
            return new SimpleModule();
        }

        @Bean
        AttachmentService attachments() {
            return new AttachmentService(mapper(), storage(), access(), tokens(), 1024);
        }
    }
}
