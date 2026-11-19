package qg.qgent.github.client;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.auth0.jwt.JWT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import qg.qgent.github.config.GitHubAppProperties;

class GitHubAppClientTest {
    @TempDir Path temp;

    @Test
    void installationTokenUsesAppJwtAndRestrictsRepository() throws Exception {
        var properties = properties();
        var builder = RestClient.builder().baseUrl("https://api.github.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.github.com/app/installations/12/access_tokens"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    var jwt =
                            JWT.decode(request.getHeaders().getFirst("Authorization").substring(7));
                    assertThat(jwt.getAlgorithm()).isEqualTo("RS256");
                    assertThat(jwt.getIssuer()).isEqualTo("123");
                })
                .andExpect(content().json("{\"repository_ids\":[34]}"))
                .andRespond(
                        withSuccess(
                                "{\"token\":\"installation-token\"}", MediaType.APPLICATION_JSON));
        assertThat(new GitHubAppClient(properties, builder.build()).installationToken(12L, 34L))
                .isEqualTo("installation-token");
        server.verify();
    }

    @Test
    void repositoryListFollowsPagination() throws Exception {
        var builder = RestClient.builder().baseUrl("https://api.github.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.github.com/app/installations/12/access_tokens"))
                .andRespond(withSuccess("{\"token\":\"token\"}", MediaType.APPLICATION_JSON));
        String row =
                "{\"id\":34,\"full_name\":\"owner/repo\",\"default_branch\":\"main\",\"private\":true}";
        server.expect(
                        requestTo(
                                "https://api.github.com/installation/repositories?per_page=100&page=1"))
                .andExpect(header("Authorization", "Bearer token"))
                .andRespond(
                        withSuccess(
                                "{\"repositories\":["
                                        + String.join(",", java.util.Collections.nCopies(100, row))
                                        + "]}",
                                MediaType.APPLICATION_JSON));
        server.expect(
                        requestTo(
                                "https://api.github.com/installation/repositories?per_page=100&page=2"))
                .andRespond(withSuccess("{\"repositories\":[]}", MediaType.APPLICATION_JSON));
        var rows = new GitHubAppClient(properties(), builder.build()).repositories(12L);
        assertThat(rows).hasSize(100);
        assertThat(rows.getFirst().fullName()).isEqualTo("owner/repo");
        server.verify();
    }

    @Test
    void unconfiguredAppDoesNotNeedPrivateKeyAtStartup() {
        var app = new GitHubAppClient(new GitHubAppProperties(), RestClient.create());
        assertThatThrownBy(() -> app.installation(12)).hasMessage("GitHub App 尚未配置");
    }

    private GitHubAppProperties properties() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var key = temp.resolve("app.pem");
        // 每次运行现生成密钥，仓库里不存私钥。
        try (var writer = new JcaPEMWriter(Files.newBufferedWriter(key))) {
            writer.writeObject(generator.generateKeyPair().getPrivate());
        }
        var result = new GitHubAppProperties();
        result.setAppId("123");
        result.setSlug("qgents");
        result.setPrivateKeyPath(key.toString());
        return result;
    }
}
