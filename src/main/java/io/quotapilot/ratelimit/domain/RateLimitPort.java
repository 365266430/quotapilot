package io.quotapilot.ratelimit.domain;

/**
 * [M7] 限流原子门端口（Redis Lua 实现，检查+扣减一步完成）。
 * 拒绝时返回 Retry-After 秒数（>-0 语义见实现）；通过返回剩余量。
 * initUsed：键不存在（Redis 重启/丢失）时按 DB 权威口径初始化已用量——不永久失真。
 */
public interface RateLimitPort {

    /** 滑动窗口（请求数维度）。@return true=通过。 */
    boolean tryAcquireWindow(String key, long limit, long windowSeconds, long units, long initCount);

    /** 令牌桶（Token/计费单位维度）。@return true=通过。 */
    boolean tryAcquireBucket(String key, long capacity, double refillPerSecond, long units, long initUsed);

    /** 令牌桶返还(+)/补扣(−)：返还封顶至容量，补扣下限 0。 */
    void adjustBucket(String key, long capacity, double refillPerSecond, long delta);
}
