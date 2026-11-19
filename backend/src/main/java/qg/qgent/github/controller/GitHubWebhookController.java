package qg.qgent.github.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import qg.qgent.common.web.ApiException;
import qg.qgent.github.config.GitHubAppProperties;
import qg.qgent.github.service.GitHubRepositoryService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 同步 App 卸载、暂停和仓库授权变化；重复投递重复同步，不创建通知或工作任务。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/integrations/github")
public class GitHubWebhookController {
    private final GitHubAppProperties properties;
    private final GitHubRepositoryService service;
    private final ObjectMapper mapper;

    @PostMapping("/webhook")
    public void receive(
        @RequestBody byte[] body,
        @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
        @RequestHeader("X-GitHub-Event") String event) {
        verify(body, signature);
        if (!"installation".equals(event) && !"installation_repositories".equals(event)) return;
        try {
            var json = mapper.readTree(body);
            long id = json.path("installation").path("id").asLong();
            if (id <= 0) throw new IllegalArgumentException();
            service.installationChanged(id, "deleted".equals(json.path("action").asText()));
        } catch (java.io.IOException | IllegalArgumentException e) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST, "INVALID_GITHUB_WEBHOOK", "GitHub 事件内容不合法");
        }
    }

    private void verify(byte[] body, String signature) {
        if (properties.getWebhookSecret().isBlank())
            throw new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "GITHUB_WEBHOOK_NOT_CONFIGURED",
                "GitHub Webhook 尚未配置");
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(
                new SecretKeySpec(
                    properties.getWebhookSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            byte[] received =
                signature != null && signature.startsWith("sha256=")
                    ? HexFormat.of().parseHex(signature.substring(7))
                    : new byte[0];
            if (!MessageDigest.isEqual(mac.doFinal(body), received))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                HttpStatus.UNAUTHORIZED, "INVALID_GITHUB_SIGNATURE", "GitHub 事件签名无效");
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("无法校验 GitHub 事件签名", e);
        }
    }
}
