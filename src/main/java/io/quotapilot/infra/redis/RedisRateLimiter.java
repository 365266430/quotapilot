package io.quotapilot.infra.redis;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import io.quotapilot.ratelimit.domain.RateLimitPort;

/**
 * [M7] Redis 限流原子门（Redis 3.0 兼容：多字段写用 HMSET）。
 * REQUESTS：ZSET 滑动窗口（成员=唯一事件 id，score=毫秒时间戳）；
 * TOKENS / BILLING_UNITS：令牌桶（HMSET tokens/ts，按流逝时间补充）。
 * 键缺失时以 initUsed/initCount（DB 权威口径）初始化——Redis 重启不永久失真。
 */
@Component
public class RedisRateLimiter implements RateLimitPort {

    private static final String WINDOW_LUA = """
            local k = KEYS[1]
            local now = tonumber(ARGV[1])
            local windowMs = tonumber(ARGV[2]) * 1000
            local limit = tonumber(ARGV[3])
            local units = tonumber(ARGV[4])
            local member = ARGV[5]
            local initCount = tonumber(ARGV[6])
            if redis.call('EXISTS', k) == 0 and initCount > 0 then
              for i = 1, initCount do
                redis.call('ZADD', k, now, 'rebuild-' .. tostring(i) .. '-' .. member)
              end
            end
            redis.call('ZREMRANGEBYSCORE', k, '-inf', now - windowMs)
            local count = redis.call('ZCARD', k)
            if count + units > limit then
              redis.call('PEXPIRE', k, windowMs * 2)
              return 0
            end
            redis.call('ZADD', k, now, member)
            redis.call('PEXPIRE', k, windowMs * 2)
            return 1
            """;

    private static final String BUCKET_LUA = """
            local k = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local requested = tonumber(ARGV[3])
            local now = tonumber(ARGV[4])
            local initUsed = tonumber(ARGV[5])
            local st = redis.call('HMGET', k, 'tokens', 'ts')
            local tokens = tonumber(st[1])
            local ts = tonumber(st[2])
            if tokens == nil then
              tokens = capacity - initUsed
              if tokens < 0 then tokens = 0 end
              ts = now
            end
            local elapsed = (now - ts) / 1000
            if elapsed < 0 then elapsed = 0 end
            tokens = math.min(capacity, tokens + elapsed * rate)
            redis.call('HMSET', k, 'tokens', tokens, 'ts', now)
            redis.call('EXPIRE', k, 3600)
            if tokens < requested then
              return 0
            end
            redis.call('HSET', k, 'tokens', tokens - requested)
            return 1
            """;

    private static final String ADJUST_LUA = """
            local k = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local delta = tonumber(ARGV[3])
            local now = tonumber(ARGV[4])
            local st = redis.call('HMGET', k, 'tokens', 'ts')
            local tokens = tonumber(st[1])
            local ts = tonumber(st[2])
            if tokens == nil then
              tokens = capacity
              ts = now
            end
            local elapsed = (now - ts) / 1000
            if elapsed < 0 then elapsed = 0 end
            tokens = math.min(capacity, tokens + elapsed * rate)
            tokens = tokens + delta
            if tokens > capacity then tokens = capacity end
            if tokens < 0 then tokens = 0 end
            redis.call('HMSET', k, 'tokens', tokens, 'ts', now)
            redis.call('EXPIRE', k, 3600)
            return 1
            """;

    private final StringRedisTemplate redis;
    private final io.quotapilot.common.TimeService time;
    private final DefaultRedisScript<Long> windowScript = script(WINDOW_LUA);
    private final DefaultRedisScript<Long> bucketScript = script(BUCKET_LUA);
    private final DefaultRedisScript<Long> adjustScript = script(ADJUST_LUA);

    public RedisRateLimiter(StringRedisTemplate redis, io.quotapilot.common.TimeService time) {
        this.redis = redis;
        this.time = time;
    }

    @Override
    public boolean tryAcquireWindow(String key, long limit, long windowSeconds, long units, long initCount) {
        Long r = redis.execute(windowScript, List.of(key), String.valueOf(time.now().toEpochMilli()),
                String.valueOf(windowSeconds), String.valueOf(limit), String.valueOf(units),
                java.util.UUID.randomUUID().toString(), String.valueOf(Math.min(initCount, limit)));
        return r != null && r == 1;
    }

    @Override
    public boolean tryAcquireBucket(String key, long capacity, double refillPerSecond, long units, long initUsed) {
        Long r = redis.execute(bucketScript, List.of(key), String.valueOf(capacity), String.valueOf(refillPerSecond),
                String.valueOf(units), String.valueOf(time.now().toEpochMilli()), String.valueOf(initUsed));
        return r != null && r == 1;
    }

    @Override
    public void adjustBucket(String key, long capacity, double refillPerSecond, long delta) {
        redis.execute(adjustScript, List.of(key), String.valueOf(capacity), String.valueOf(refillPerSecond),
                String.valueOf(delta), String.valueOf(time.now().toEpochMilli()));
    }

    private static DefaultRedisScript<Long> script(String lua) {
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setScriptText(lua);
        s.setResultType(Long.class);
        return s;
    }
}
