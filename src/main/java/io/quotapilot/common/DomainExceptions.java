package io.quotapilot.common;

/**
 * [公共] 领域异常族。API 层统一映射为结构化错误（错误码 + 人类可读信息 + traceId）。
 */
public final class DomainExceptions {

    private DomainExceptions() {}

    /** 预算不足/额度拒绝 → 402 QUOTA_EXCEEDED。 */
    public static class QuotaExceeded extends RuntimeException {
        public final String accountId;
        public final long neededMinor;
        public final long availableMinor;

        public QuotaExceeded(String accountId, long neededMinor, long availableMinor) {
            super("QUOTA_EXCEEDED: accountId=" + accountId + " 需要=" + neededMinor + " 可用=" + availableMinor);
            this.accountId = accountId;
            this.neededMinor = neededMinor;
            this.availableMinor = availableMinor;
        }
    }

    /** 账户被硬止损 BLOCKED → 402 ACCOUNT_BLOCKED。 */
    public static class AccountBlocked extends RuntimeException {
        public AccountBlocked(String accountId) {
            super("ACCOUNT_BLOCKED: " + accountId);
        }
    }

    /** 未配置额度且无系统默认 → 403 NO_QUOTA_CONFIGURED。 */
    public static class NoQuotaConfigured extends RuntimeException {
        public NoQuotaConfigured(String scopeDesc) {
            super("NO_QUOTA_CONFIGURED: " + scopeDesc);
        }
    }

    /** 无法解析价格快照 → 409 PRICING_UNAVAILABLE。 */
    public static class PricingUnavailable extends RuntimeException {
        public PricingUnavailable(String sku) {
            super("PRICING_UNAVAILABLE: " + sku);
        }
    }

    /** 预留基础设施异常（Redis/DB）→ 503 HOLD_FAILED，调用方应重试。 */
    public static class HoldFailed extends RuntimeException {
        public HoldFailed(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    /** 重复请求（requestId 唯一约束）→ 返回首次结果而非报错，仅在冲突检测路径抛出。 */
    public static class DuplicateRequest extends RuntimeException {
        public DuplicateRequest(String requestId, String opType) {
            super("DUPLICATE_REQUEST: " + opType + " requestId=" + requestId);
        }
    }

    /** DB 账本写入失败（非重复键）。 */
    public static class LedgerWriteFailed extends RuntimeException {
        public LedgerWriteFailed(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    /** 请求状态机冲突（如对已 SETTLED 的请求重复取消）→ 409 REQUEST_STATE。 */
    public static class RequestStateConflict extends RuntimeException {
        public RequestStateConflict(String requestId, String expected, String actual) {
            super("REQUEST_STATE_CONFLICT: requestId=" + requestId + " 期望=" + expected + " 实际=" + actual);
        }
    }

    /** 资源不存在 → 404。 */
    public static class NotFound extends RuntimeException {
        public NotFound(String msg) {
            super(msg);
        }
    }

    /** 幂等键冲突但内容不同（同 key 不同语义）→ 409。 */
    public static class IdempotencyConflict extends RuntimeException {
        public IdempotencyConflict(String key) {
            super("IDEMPOTENCY_CONFLICT: " + key);
        }
    }
}
