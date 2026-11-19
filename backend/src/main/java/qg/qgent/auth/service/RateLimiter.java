package qg.qgent.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按请求指纹和时间窗口限流；Redis 不可用时使用本地计数。
 */
@Component
public class RateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private final StringRedisTemplate redis;
    private final ConcurrentHashMap<String, LocalWindow> fallback = new ConcurrentHashMap<>();

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 检查是否允许请求通过
     *
     * @param scope       限流场景
     * @param fingerprint 请求指纹（如 IP、用户 ID 等）
     * @param limit       限流阈值
     * @param window      限流窗口（如 1 秒）
     * @return 是否允许请求通过
     */
    public boolean allow(String scope, String fingerprint, int limit, Duration window) {
        // 某个场景下某个指纹的请求次数
        String key = "rate:" + scope + ":" + fingerprintHash(fingerprint);
        try {
            Long value = redis.opsForValue().increment(key);
            // 如果是第一次请求，设置过期时间为窗口时间
            if (value != null && value == 1) redis.expire(key, window);
            return value == null || value <= limit;
        } catch (RuntimeException e) {
            log.warn("Redis rate limiter unavailable; using local fallback for scope={}", scope);
            return allowLocal(key, limit, window);
        }
    }

    // 使用本地内存作为 Redis 不可用时的回退限流机制
    private boolean allowLocal(String key, int limit, Duration window) {
        long now = System.currentTimeMillis();
        LocalWindow current =
            fallback.compute(
                key,
                (ignored, old) -> {
                    // 如果是第一次或者过期，就重置计数器
                    if (old == null || old.expiresAt < now)
                        return new LocalWindow(
                            new AtomicInteger(1), now + window.toMillis());
                    old.count.incrementAndGet();
                    return old;
                });
        // 计数窗口过多时清理已过期记录。
        if (fallback.size() > 10_000)
            fallback.entrySet().removeIf(e -> e.getValue().expiresAt < now);
        return current.count.get() <= limit;
    }

    // 计算指纹的哈希值
    private String fingerprintHash(String value) {
        try {
            return HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // Redis 不可用时使用的内存计数窗口。
    private record LocalWindow(AtomicInteger count, long expiresAt) {
    }
}
