package qg.qgent.notification.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.RequestIdFilter;
import qg.qgent.notification.dto.PushDeviceRegistrationRequest;
import qg.qgent.notification.service.PushNotificationService;

import java.util.Map;

/**
 * 移动端 FCM 设备注册与注销接口。Token 不在响应中回显。
 */
@RestController
@RequestMapping("/api/v1/push/devices")
@RequiredArgsConstructor
public class PushDeviceController {
    private final PushNotificationService push;

    /**
     * 注册或刷新当前用户的一台 Android/iOS 安装实例。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> register(
        @AuthenticationPrincipal Long userId,
        @Valid @RequestBody PushDeviceRegistrationRequest body,
        HttpServletRequest request) {
        return ApiResponse.ok(push.register(userId, body), requestId(request));
    }

    /**
     * 停用当前用户指定的安装实例。
     */
    @DeleteMapping("/{installationId}")
    public ApiResponse<?> unregister(
        @AuthenticationPrincipal Long userId,
        @PathVariable String installationId,
        HttpServletRequest request) {
        push.unregister(userId, installationId);
        return ApiResponse.ok(Map.of(), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
    }
}
