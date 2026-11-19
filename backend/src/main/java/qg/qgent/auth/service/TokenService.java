package qg.qgent.auth.service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 签发和校验 JWT 访问令牌，生成刷新令牌及令牌摘要。
 */
@Component
public class TokenService {
    private final Algorithm algorithm;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final Duration resetTtl;
    private final SecureRandom random = new SecureRandom();

    public TokenService(
        @Value("${app.jwt-secret}") String secret,
        @Value("${app.access-token-minutes:15}") long accessMinutes,
        @Value("${app.refresh-token-days:30}") long refreshDays,
        @Value("${app.reset-token-minutes:30}") long resetMinutes) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalStateException("JWT_SECRET至少需要32字节");
        algorithm = Algorithm.HMAC256(secret);
        accessTtl = Duration.ofMinutes(accessMinutes);
        refreshTtl = Duration.ofDays(refreshDays);
        resetTtl = Duration.ofMinutes(resetMinutes);
    }

    // 生成 access token
    public String access(Long userId) {
        Instant now = Instant.now();
        return JWT.create()
            .withIssuer("qgents")
            .withAudience("qgents-api")
            .withJWTId(UUID.randomUUID().toString())
            .withSubject(userId.toString())
            .withIssuedAt(now) // 签发时间
            .withExpiresAt(now.plus(accessTtl)) // 过期时间
            .sign(algorithm);
    }

    // 验证 access token 并返回用户ID
    public Long verifyAccess(String token) {
        try {
            return Long.valueOf(
                JWT.require(algorithm)
                    .withIssuer("qgents")
                    .withAudience("qgents-api")
                    .build()
                    .verify(token)
                    .getSubject());
        } catch (JWTVerificationException | IllegalArgumentException e) {
            return null;
        }
    }

    // 生成随机的 refresh token
    public String opaque() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    // 计算 refresh token / 验证码的 SHA-256 摘要（十六进制），作为 Redis key 和存储值
    public String digest(String value) {
        return HexFormat.of().formatHex(hash(value));
    }

    public byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // 获取 refresh token 的有效期
    public Duration refreshTtl() {
        return refreshTtl;
    }

    // 获取密码重置验证码的有效期
    public Duration resetTtl() {
        return resetTtl;
    }

    public long accessSeconds() {
        return accessTtl.toSeconds();
    }

    // 获取 refresh token 的过期时间（秒）
    public long refreshSeconds() {
        return refreshTtl.toSeconds();
    }
}
