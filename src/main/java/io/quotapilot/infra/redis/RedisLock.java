package io.quotapilot.infra.redis;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** [M11] 分布式锁：仅用于 sweeper 对账任务互斥等低频临界区（热路径禁止加锁）。 */
@Component
public class RedisLock {

    private static final String UNLOCK_LUA = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> unlockScript = new DefaultRedisScript<>();

    public RedisLock(StringRedisTemplate redis) {
        this.redis = redis;
        unlockScript.setScriptText(UNLOCK_LUA);
        unlockScript.setResultType(Long.class);
    }

    public boolean tryLock(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean ok = redis.opsForValue().setIfAbsent(key, token, ttl);
        return Boolean.TRUE.equals(ok);
    }

    public void unlock(String key) {
        String token = redis.opsForValue().get(key);
        if (token != null) {
            redis.execute(unlockScript, List.of(key), token);
        }
    }
}
