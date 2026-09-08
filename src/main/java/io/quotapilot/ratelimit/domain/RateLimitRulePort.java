package io.quotapilot.ratelimit.domain;

import java.util.Optional;

/**
 * [M7] 限流规则存储端口（按账户覆盖 + 系统默认兜底由 RateLimiter 处理）。
 */
public interface RateLimitRulePort {

    Optional<RateLimitRule> findByAccount(String accountId);

    RateLimitRule save(RateLimitRule rule);
}
