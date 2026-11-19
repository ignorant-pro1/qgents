package qg.qgent.github.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import qg.qgent.github.config.GitHubAppProperties;
import qg.qgent.github.service.GitHubRepositoryService;

class GitHubWebhookControllerTest {
    private final GitHubAppProperties properties = new GitHubAppProperties();
    private final GitHubRepositoryService service = mock(GitHubRepositoryService.class);
    private final GitHubWebhookController controller =
            new GitHubWebhookController(properties, service, new ObjectMapper());

    @Test
    void validDeletionIsForwardedAndInvalidSignatureHasNoEffect() throws Exception {
        properties.setWebhookSecret("test-secret");
        byte[] body =
                "{\"action\":\"deleted\",\"installation\":{\"id\":12}}"
                        .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> controller.receive(body, "sha256=invalid", "installation"))
                .hasMessage("GitHub 事件签名无效");
        verifyNoInteractions(service);
        controller.receive(body, signature(body), "installation");
        verify(service).installationChanged(12L, true);
    }

    @Test
    void repositoryChangesRefreshImportsAndPullRequestsDoNotRestoreOldWorkflow() throws Exception {
        properties.setWebhookSecret("test-secret");
        byte[] body =
                "{\"action\":\"removed\",\"installation\":{\"id\":12}}"
                        .getBytes(StandardCharsets.UTF_8);
        controller.receive(body, signature(body), "installation_repositories");
        verify(service).installationChanged(12L, false);
        controller.receive(body, signature(body), "pull_request");
        verifyNoMoreInteractions(service);
    }

    private String signature(byte[] body) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
