package qg.qgent.attachment.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.attachment.dto.AttachmentCreateRequest;
import qg.qgent.attachment.dto.AttachmentInfoResponse;
import qg.qgent.attachment.dto.AttachmentStatusResponse;
import qg.qgent.attachment.service.AttachmentService;
import qg.qgent.attachment.web.AttachmentResponseFactory;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.RequestIdFilter;

import java.io.IOException;

/**
 * 附件上传、确认、信息与内容读取入口。
 */
@RestController
@RequestMapping("/api/v1")
public class AttachmentController {

    private final AttachmentService attachmentService;
    private final AttachmentResponseFactory responses;

    public AttachmentController(
        AttachmentService attachmentService, AttachmentResponseFactory responses) {
        this.attachmentService = attachmentService;
        this.responses = responses;
    }

    @Operation(summary = "申请附件上传", description = "按存储配置返回 OSS 直传或后端 PUT 地址；上传完成后调用确认接口。")
    @PostMapping("/projects/{projectId}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> createCredential(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @Valid @RequestBody AttachmentCreateRequest body,
        HttpServletRequest request) {
        return ok(attachmentService.createCredential(userId, projectId, body), request);
    }

    @Operation(summary = "确认附件上传完成", description = "客户端上传文件后调用，服务端检查文件存在及实际大小后置 READY；重复确认返回成功。")
    @PostMapping("/projects/{projectId}/attachments/{attachmentId}/confirm")
    public ApiResponse<?> confirm(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @PathVariable Long attachmentId,
        HttpServletRequest request) {
        AttachmentStatusResponse data =
            attachmentService.confirmUpload(userId, projectId, attachmentId);
        return ok(data, request);
    }

    @Operation(
        summary = "上传本地存储附件",
        description = "仅本地磁盘模式接收文件字节；OSS 模式使用申请接口返回的直传地址。随后调用 confirm 置 READY。")
    @PutMapping("/projects/{projectId}/attachments/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void upload(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @PathVariable Long attachmentId,
        HttpServletRequest request)
        throws IOException {
        attachmentService.uploadBytes(userId, projectId, attachmentId, request.getInputStream());
    }

    @Operation(
        summary = "获取附件信息与短期内容地址",
        description = "返回附件元数据、预览类型和带 token 的短期内容地址；只读取元数据，不读取文件内容。")
    @GetMapping("/projects/{projectId}/attachments/{attachmentId}")
    public ApiResponse<AttachmentInfoResponse> info(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @PathVariable Long attachmentId,
        HttpServletRequest request) {
        return ok(attachmentService.info(userId, projectId, attachmentId), request);
    }

    @Operation(
        summary = "获取附件内容（预览或下载）",
        description = "默认内联预览，download=1 以附件下载；支持 Authorization 头或 ?token= 鉴权与单段 HTTP Range。")
    @GetMapping("/projects/{projectId}/attachments/{attachmentId}/content")
    public ResponseEntity<?> content(
        @AuthenticationPrincipal Long userId,
        @PathVariable Long projectId,
        @PathVariable Long attachmentId,
        @RequestParam(name = "download", required = false, defaultValue = "false")
        boolean download,
        @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
        HttpServletRequest request) {
        return responses.content(
            attachmentService.contentResource(userId, projectId, attachmentId),
            download,
            rangeHeader,
            request);
    }

    private <T> ApiResponse<T> ok(T data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
