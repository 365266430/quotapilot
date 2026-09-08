package io.quotapilot.ratelimit.domain;

/**
 * [M7] 限流规则（每账户）。0 = 该维度不限流。
 * REQUESTS：每秒请求数（滑动窗口 1s）；TOKENS：每分钟 Token（令牌桶）；BILLING_UNITS：每分钟计费金额（令牌桶，单位=千分之一分）。
 */
public record RateLimitRule(String accountId, long requestsPerSecond, long tokensPerMinute,
                            long billingUnitsPerMinute) {

    public boolean enabled(RateDimension dim) {
        return switch (dim) {
            case REQUESTS -> requestsPerSecond > 0;
            case TOKENS -> tokensPerMinute > 0;
            case BILLING_UNITS -> billingUnitsPerMinute > 0;
        };
    }
}
