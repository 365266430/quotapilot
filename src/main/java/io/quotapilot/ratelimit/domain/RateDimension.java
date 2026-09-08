package io.quotapilot.ratelimit.domain;

/**
 * [M7] 限流维度：请求数 / Token / 计费单位。与 M3 预留语义分离（额度管「钱」、限流管「速率」）。
 */
public enum RateDimension {
    REQUESTS, TOKENS, BILLING_UNITS
}
