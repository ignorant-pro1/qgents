package qg.qgent.auth.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Redis 保存邮箱验证码摘要：同用途同邮箱只保留最新一条，带 TTL，一次性消费。
 */
@Component
public class VerificationCodeStore {
    private static final String PREFIX = "auth:code:";
    // 比较并删除：错误验证码不消费有效值，成功后不能重放。
    private static final DefaultRedisScript<Long> CONSUME =
        new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

    public enum Purpose {
        REGISTER("register"),
        PASSWORD_RESET("reset");

        private final String key;

        Purpose(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    private final StringRedisTemplate redis;
    private final TokenService tokens;

    public VerificationCodeStore(StringRedisTemplate redis, TokenService tokens) {
        this.redis = redis;
        this.tokens = tokens;
    }

    public void save(Purpose purpose, String email, String code, Duration ttl) {
        redis.opsForValue().set(key(purpose, email), tokens.digest(code), ttl);
    }

    public boolean consume(Purpose purpose, String email, String code) {
        Long removed = redis.execute(CONSUME, List.of(key(purpose, email)), tokens.digest(code));
        return removed != null && removed > 0;
    }

    private String key(Purpose purpose, String email) {
        return PREFIX + purpose.key + ":" + email;
    }
}
