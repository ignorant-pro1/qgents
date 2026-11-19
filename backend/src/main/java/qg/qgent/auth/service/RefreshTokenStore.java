package qg.qgent.auth.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Redis 保存 refresh token：auth:refresh:&lt;摘要&gt; → 用户 ID，并按用户维护摘要集合以支持撤销全部设备。
 * Redis 不可用时直接抛出，不退回本地状态。
 */
@Component
public class RefreshTokenStore {
    private static final String TOKEN_PREFIX = "auth:refresh:";
    private static final String USER_PREFIX = "auth:user-refresh:";

    private final StringRedisTemplate redis;
    private final TokenService tokens;

    public RefreshTokenStore(StringRedisTemplate redis, TokenService tokens) {
        this.redis = redis;
        this.tokens = tokens;
    }

    public void save(Long userId, String token) {
        String digest = tokens.digest(token);
        String userKey = USER_PREFIX + userId;
        redis.opsForValue().set(TOKEN_PREFIX + digest, userId.toString(), tokens.refreshTtl());
        redis.opsForSet().add(userKey, digest);
        redis.expire(userKey, tokens.refreshTtl());
    }

    /**
     * 消费令牌并返回所属用户；同一令牌的并发刷新只有一次能取到值。
     */
    public Long consume(String token) {
        String digest = tokens.digest(token);
        String userId = redis.opsForValue().getAndDelete(TOKEN_PREFIX + digest);
        if (userId == null) {
            return null;
        }
        redis.opsForSet().remove(USER_PREFIX + userId, digest);
        return Long.valueOf(userId);
    }

    /**
     * 退出登录：撤销指定令牌。
     */
    public void revoke(String token) {
        String digest = tokens.digest(token);
        String userId = redis.opsForValue().getAndDelete(TOKEN_PREFIX + digest);
        if (userId != null) {
            redis.opsForSet().remove(USER_PREFIX + userId, digest);
        }
    }

    /**
     * 撤销用户的全部 refresh token（如密码重置），已轮换的旧摘要直接忽略。
     */
    public void revokeAll(Long userId) {
        String userKey = USER_PREFIX + userId;
        Set<String> digests = redis.opsForSet().members(userKey);
        if (digests != null) {
            digests.forEach(digest -> redis.delete(TOKEN_PREFIX + digest));
        }
        redis.delete(userKey);
    }
}
