package qg.qgent.auth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Cipher;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import qg.qgent.auth.dto.AuthTokensResponse;
import qg.qgent.auth.dto.LoginRequest;
import qg.qgent.auth.dto.RegisterRequest;
import qg.qgent.auth.service.VerificationCodeStore;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named = "QGENTS_INTEGRATION_TESTS",
        matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class FixedAccountAuthIntegrationTest {

    @LocalServerPort private int port;

    @Value("${app.rsa-private-key}")
    private Resource privateKeyResource;

    @Value("${app.rsa-key-id}")
    private String rsaKeyId;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private VerificationCodeStore verificationCodes;

    @Test
    public void testFixedAccountAuthFlow() throws Exception {
        // 1. 设置固定测试账号
        String fixedEmail = "dev-fixed-user@example.com";
        String rawPassword = "qgents-integration-test-pw";

        // 读取私钥，并动态推导出公钥，用于给密码加密
        String pem =
                new String(
                                privateKeyResource.getInputStream().readAllBytes(),
                                StandardCharsets.US_ASCII)
                        .replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "")
                        .replaceAll("\\s", "");
        byte[] privKeyBytes = Base64.getDecoder().decode(pem);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey privKey = kf.generatePrivate(new PKCS8EncodedKeySpec(privKeyBytes));
        RSAPrivateCrtKey crtKey = (RSAPrivateCrtKey) privKey;
        PublicKey pubKey =
                kf.generatePublic(
                        new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent()));
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, pubKey);
        byte[] encrypted = cipher.doFinal(rawPassword.getBytes(StandardCharsets.UTF_8));
        String encryptedPassword = Base64.getEncoder().encodeToString(encrypted);

        HttpClient httpClient = HttpClient.newHttpClient();

        // 2. 尝试注册（如果已存在则忽略失败）
        // 注册需要邮箱验证码：直接向 Redis 写入一条已知验证码（模拟邮件已发送），再带码注册。
        String testCode = "123456";
        verificationCodes.save(
                VerificationCodeStore.Purpose.REGISTER,
                fixedEmail,
                testCode,
                Duration.ofMinutes(10));

        RegisterRequest registerReq = new RegisterRequest();
        registerReq.setEmail(fixedEmail);
        registerReq.setVerificationCode(testCode);
        registerReq.setDisplayName("Fixed Dev User");
        registerReq.setPasswordKeyId(rsaKeyId);
        registerReq.setPassword(encryptedPassword);

        HttpRequest registerRequest =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/v1/auth/register"))
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        objectMapper.writeValueAsString(registerReq)))
                        .build();
        httpClient.send(registerRequest, HttpResponse.BodyHandlers.ofString());

        // 3. 执行登录获取 Token
        LoginRequest loginReq = new LoginRequest();
        loginReq.setEmail(fixedEmail);
        loginReq.setPasswordKeyId(rsaKeyId);
        loginReq.setPassword(encryptedPassword);

        HttpRequest loginRequest =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        objectMapper.writeValueAsString(loginReq)))
                        .build();

        HttpResponse<String> loginResponse =
                httpClient.send(loginRequest, HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(200, loginResponse.statusCode(), "固定账号登录失败");

        JsonNode loginRoot = objectMapper.readTree(loginResponse.body());
        AuthTokensResponse loginTokens =
                objectMapper.treeToValue(loginRoot.get("data"), AuthTokensResponse.class);

        // 4. refresh token 在 Redis 中轮换：旧令牌只能使用一次，退出后失效
        HttpResponse<String> rotated =
                httpClient.send(
                        refreshRequest(loginTokens.getRefreshToken()),
                        HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(200, rotated.statusCode(), "refresh 应成功");
        String rotatedToken =
                objectMapper.readTree(rotated.body()).get("data").get("refreshToken").asText();
        Assertions.assertNotEquals(loginTokens.getRefreshToken(), rotatedToken, "refresh 应轮换令牌");

        HttpResponse<String> replay =
                httpClient.send(
                        refreshRequest(loginTokens.getRefreshToken()),
                        HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(401, replay.statusCode(), "已消费的 refresh token 不能重放");

        HttpResponse<String> logoutResponse =
                httpClient.send(logoutRequest(rotatedToken), HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(200, logoutResponse.statusCode(), "退出应成功");

        HttpResponse<String> afterLogout =
                httpClient.send(refreshRequest(rotatedToken), HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(401, afterLogout.statusCode(), "退出后 refresh token 应失效");

        // 5. 为该固定账号检查并创建团队
        Long userId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM users WHERE email = ?", Long.class, fixedEmail);
        Assertions.assertNotNull(userId, "用户应该存在");

        Long teamId;
        try {
            teamId =
                    jdbcTemplate.queryForObject(
                            "SELECT team_id FROM team_members WHERE user_id = ? LIMIT 1",
                            Long.class,
                            userId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            // 没有团队，创建一个
            jdbcTemplate.update(
                    "INSERT INTO teams (owner_user_id, name) VALUES (?, ?)",
                    userId,
                    "Fixed Dev Team");
            teamId =
                    jdbcTemplate.queryForObject(
                            "SELECT id FROM teams WHERE owner_user_id = ? ORDER BY id DESC LIMIT 1",
                            Long.class,
                            userId);
            jdbcTemplate.update(
                    "INSERT INTO team_members (team_id, user_id, role) VALUES (?, ?, ?)",
                    teamId,
                    userId,
                    "TEAM_OWNER");
        }

        System.out.println("\n\n=======================================================");
        System.out.println("🚀🚀🚀 [开发环境固定账号] 登录成功！用于 Apifox 测试：");
        System.out.println("-------------------------------------------------------");
        System.out.println("团队 ID (teamId): " + teamId);
        System.out.println("-------------------------------------------------------");
        System.out.println("认证验证完成；敏感账号与 Token 不写入测试日志。");
        System.out.println("=======================================================\n\n");
    }

    private HttpRequest refreshRequest(String refreshToken) throws Exception {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/auth/refresh"))
                .header("Content-Type", "application/json")
                .POST(
                        HttpRequest.BodyPublishers.ofString(
                                objectMapper.writeValueAsString(
                                        java.util.Map.of("refreshToken", refreshToken))))
                .build();
    }

    private HttpRequest logoutRequest(String refreshToken) throws Exception {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/auth/logout"))
                .header("Content-Type", "application/json")
                .POST(
                        HttpRequest.BodyPublishers.ofString(
                                objectMapper.writeValueAsString(
                                        java.util.Map.of("refreshToken", refreshToken))))
                .build();
    }
}
