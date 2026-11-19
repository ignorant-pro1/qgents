package qg.qgent.github.client;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.JsonNode;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import qg.qgent.common.web.ApiException;
import qg.qgent.github.config.GitHubAppProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 使用 App 私钥获取安装信息、授权仓库和短期访问凭证。
 */
@Component
public class GitHubAppClient {
    private final GitHubAppProperties properties;
    private final RestClient http;
    private final com.fasterxml.jackson.databind.ObjectMapper json =
        new com.fasterxml.jackson.databind.ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired
    public GitHubAppClient(GitHubAppProperties properties) {
        this(properties, httpBuilder().build());
    }

    GitHubAppClient(GitHubAppProperties properties, RestClient http) {
        this.properties = properties;
        this.http = http;
    }

    public static RestClient.Builder httpBuilder() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder()
            .baseUrl("https://api.github.com")
            .requestFactory(factory)
            .defaultHeader("Accept", "application/vnd.github+json")
            .defaultHeader("X-GitHub-Api-Version", "2026-03-10");
    }

    public void requireConfigured() {
        if (!properties.configured())
            throw new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_APP_NOT_CONFIGURED", "GitHub App 尚未配置");
    }

    public Installation installation(long id) {
        JsonNode json = get("/app/installations/" + id, appToken());
        return new Installation(
            id,
            json.path("account").path("login").asText(),
            json.path("account").path("type").asText(),
            json.hasNonNull("suspended_at"));
    }

    public List<Repository> repositories(long installationId) {
        String token = installationToken(installationId, null);
        List<Repository> repositories = new ArrayList<>();
        for (int page = 1; ; page++) {
            JsonNode rows =
                get("/installation/repositories?per_page=100&page=" + page, token)
                    .path("repositories");
            if (!rows.isArray()) throw unavailable();
            for (JsonNode row : rows)
                repositories.add(
                    new Repository(
                        row.path("id").asLong(),
                        row.path("full_name").asText(),
                        row.path("default_branch").asText(),
                        row.path("html_url").asText(),
                        row.path("private").asBoolean()));
            if (rows.size() < 100) return repositories;
        }
    }

    /**
     * 仓库 ID 非空时凭证只授权该仓库；每次操作重新取得短期凭证，避免长 loop 使用过期值。
     */
    public String installationToken(long installationId, Long repositoryId) {
        try {
            var request =
                http.post()
                    .uri("/app/installations/{id}/access_tokens", installationId)
                    .header("Authorization", "Bearer " + appToken());
            if (repositoryId != null) request.body(Map.of("repository_ids", List.of(repositoryId)));
            JsonNode response = json.readTree(request.retrieve().body(String.class));
            if (response == null || response.path("token").asText().isBlank()) throw unavailable();
            return response.path("token").asText();
        } catch (RestClientException e) {
            throw GitHubApiException.from(e);
        } catch (java.io.IOException | IllegalArgumentException e) {
            throw unavailable();
        }
    }

    private JsonNode get(String path, String token) {
        try {
            JsonNode response =
                json.readTree(
                    http.get()
                        .uri(path)
                        .header("Authorization", "Bearer " + token)
                        .retrieve()
                        .body(String.class));
            if (response == null) throw unavailable();
            return response;
        } catch (RestClientException e) {
            throw GitHubApiException.from(e);
        } catch (java.io.IOException | IllegalArgumentException e) {
            throw unavailable();
        }
    }

    private String appToken() {
        requireConfigured();
        try (var reader = Files.newBufferedReader(Path.of(properties.getPrivateKeyPath()));
             var parser = new PEMParser(reader)) {
            Object pem = parser.readObject();
            var converter = new JcaPEMKeyConverter();
            RSAPrivateKey key =
                (RSAPrivateKey)
                    (pem instanceof PEMKeyPair pair
                        ? converter.getKeyPair(pair).getPrivate()
                        : converter.getPrivateKey((PrivateKeyInfo) pem));
            Instant now = Instant.now();
            return JWT.create()
                .withIssuer(properties.getAppId())
                .withIssuedAt(now.minusSeconds(30))
                .withExpiresAt(now.plusSeconds(540))
                .sign(Algorithm.RSA256(null, key));
        } catch (Exception e) {
            throw new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "GITHUB_APP_PRIVATE_KEY_INVALID",
                "GitHub App 私钥无法使用");
        }
    }

    private ApiException unavailable() {
        return new ApiException(
            HttpStatus.BAD_GATEWAY,
            "GITHUB_APP_REQUEST_FAILED",
            "GitHub App 请求失败，请检查安装授权或稍后重试");
    }

    public record Installation(
        long installationId, String accountLogin, String accountType, boolean suspended) {
    }

    public record Repository(
        long repositoryId,
        String fullName,
        String defaultBranch,
        String htmlUrl,
        boolean privateRepository) {
    }
}
