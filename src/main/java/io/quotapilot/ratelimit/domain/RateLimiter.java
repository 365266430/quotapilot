package io.quotapilot.ratelimit.domain;

import java.time.Instant;

import io.quotapilot.common.DomainExceptions;
import io.quotapilot.common.TimeService;
import io.quotapilot.ledger.domain.LedgerQueryPort;
import io.quotapilot.metering.domain.UsageEventPort;

/**
 * [M7] 限流器（纯领域服务）。
 * 三维度：REQUESTS=每秒滑动窗口；TOKENS=每分钟令牌桶；BILLING_UNITS=每分钟金额令牌桶（千分之一分）。
 * 与 M3 预留语义分离、禁止互相替代（§5.4）；均基于 Redis 原子操作。
 * Redis 丢失后按 DB 权威口径重建初值（用量事件/账本流水），不永久失真。
 * 用量型维度在响应后按实际用量返还或补扣（adjust）。
 */
public class RateLimiter {

    private final RateLimitRulePort rulePort;
    private final RateLimitPort port;
    private final UsageEventPort usageEvents;
    private final LedgerQueryPort ledgerQuery;
    private final TimeService time;
    private final RateLimitRule defaults;

    public RateLimiter(RateLimitRulePort rulePort, RateLimitPort port, UsageEventPort usageEvents,
                       LedgerQueryPort ledgerQuery, TimeService time, RateLimitRule defaults) {
        this.rulePort = rulePort;
        this.port = port;
        this.usageEvents = usageEvents;
        this.ledgerQuery = ledgerQuery;
        this.time = time;
        this.defaults = defaults;
    }

    /** 预留前检查三维度；任一超限抛 RateLimited（携带 Retry-After）。请求本身计 1 个 REQUESTS 单位。 */
    public void check(String accountId, long estimatedUnits, long estimateMinor) {
        RateLimitRule rule = rulePort.findByAccount(accountId).orElse(defaults);
        if (rule.enabled(RateDimension.REQUESTS)) {
            long init = usageEvents.countByAccountSince(accountId, time.now().minusSeconds(2));
            boolean ok = port.tryAcquireWindow(key(accountId, RateDimension.REQUESTS), rule.requestsPerSecond(),
                    1L, 1L, init);
            if (!ok) {
                throw new DomainExceptions.RateLimited(RateDimension.REQUESTS.name(), 1L);
            }
        }
        if (rule.enabled(RateDimension.TOKENS)) {
            long init = usageEvents.sumQuantityByAccountSince(accountId, time.now().minusSeconds(120));
            boolean ok = port.tryAcquireBucket(key(accountId, RateDimension.TOKENS), rule.tokensPerMinute(),
                    rule.tokensPerMinute() / 60.0, Math.max(1, estimatedUnits), init);
            if (!ok) {
                throw new DomainExceptions.RateLimited(RateDimension.TOKENS.name(), retryAfterBucket(
                        rule.tokensPerMinute() / 60.0, Math.max(1, estimatedUnits)));
            }
        }
        if (rule.enabled(RateDimension.BILLING_UNITS)) {
            long init = ledgerQuery.settledSince(accountId, time.now().minusSeconds(120));
            boolean ok = port.tryAcquireBucket(key(accountId, RateDimension.BILLING_UNITS),
                    rule.billingUnitsPerMinute(), rule.billingUnitsPerMinute() / 60.0, Math.max(1, estimateMinor),
                    init);
            if (!ok) {
                throw new DomainExceptions.RateLimited(RateDimension.BILLING_UNITS.name(), retryAfterBucket(
                        rule.billingUnitsPerMinute() / 60.0, Math.max(1, estimateMinor)));
            }
        }
    }

    /** 结算后按实际用量返还/补扣（REQUESTS 不调整：一次请求计一次）。尽力而为，Redis 失败不阻断结算。
     * 预估时已扣 estimate，实际 cost < estimate → 返还差额(+actual−estimate 由调用侧表达为 estimate−actual)；
     * cost > estimate → 补扣差额（桶下限 0，不追溯追惩）。 */
    public void onSettled(String accountId, long actualUnits, long actualCostMinor, long estimatedUnits,
                          long estimateMinor) {
        RateLimitRule rule = rulePort.findByAccount(accountId).orElse(defaults);
        try {
            if (rule.enabled(RateDimension.TOKENS)) {
                port.adjustBucket(key(accountId, RateDimension.TOKENS), rule.tokensPerMinute(),
                        rule.tokensPerMinute() / 60.0, estimatedUnits - actualUnits);
            }
            if (rule.enabled(RateDimension.BILLING_UNITS)) {
                port.adjustBucket(key(accountId, RateDimension.BILLING_UNITS), rule.billingUnitsPerMinute(),
                        rule.billingUnitsPerMinute() / 60.0, estimateMinor - actualCostMinor);
            }
        } catch (RuntimeException ignored) {
            // Redis 不可用时跳过：DB 权威重建会兜底
        }
    }

    /** 释放（未产生实际用量）时全额返还预留扣减。 */
    public void onReleased(String accountId, long estimatedUnits, long estimateMinor) {
        RateLimitRule rule = rulePort.findByAccount(accountId).orElse(defaults);
        try {
            if (rule.enabled(RateDimension.TOKENS)) {
                port.adjustBucket(key(accountId, RateDimension.TOKENS), rule.tokensPerMinute(),
                        rule.tokensPerMinute() / 60.0, estimatedUnits);
            }
            if (rule.enabled(RateDimension.BILLING_UNITS)) {
                port.adjustBucket(key(accountId, RateDimension.BILLING_UNITS), rule.billingUnitsPerMinute(),
                        rule.billingUnitsPerMinute() / 60.0, estimateMinor);
            }
        } catch (RuntimeException ignored) {
            // 尽力而为
        }
    }

    private long retryAfterBucket(double rate, long requested) {
        if (rate <= 0) {
            return 60L;
        }
        return Math.max(1, (long) Math.ceil(requested / rate));
    }

    private String key(String accountId, RateDimension dim) {
        return "ratelimit:" + accountId + ":" + dim.name();
    }
}
