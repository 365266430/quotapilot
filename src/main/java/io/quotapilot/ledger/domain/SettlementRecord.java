package io.quotapilot.ledger.domain;

import java.time.Instant;

/**
 * [M0] 结算记录：一次请求的最终结算口径（actual/charged/refund 三值分立）。
 */
public final class SettlementRecord {

    public enum Status { SETTLED, RELEASED }

    private final String requestId;
    private final String holdId;
    private final String accountId;
    private final long actualAmountMinor;   // 实际用量按快照价折算
    private final long chargedAmountMinor;  // 实际入账（SETTLE 金额）
    private final long refundAmountMinor;   // 释放退回（预留余量或全额）
    private final Status status;
    private final String priceVersionId;
    private final String traceId;
    private final Instant createdAt;

    public SettlementRecord(String requestId, String holdId, String accountId, long actualAmountMinor,
                            long chargedAmountMinor, long refundAmountMinor, Status status,
                            String priceVersionId, String traceId, Instant createdAt) {
        this.requestId = requestId;
        this.holdId = holdId;
        this.accountId = accountId;
        this.actualAmountMinor = actualAmountMinor;
        this.chargedAmountMinor = chargedAmountMinor;
        this.refundAmountMinor = refundAmountMinor;
        this.status = status;
        this.priceVersionId = priceVersionId;
        this.traceId = traceId;
        this.createdAt = createdAt;
    }

    public String getRequestId() { return requestId; }
    public String getHoldId() { return holdId; }
    public String getAccountId() { return accountId; }
    public long getActualAmountMinor() { return actualAmountMinor; }
    public long getChargedAmountMinor() { return chargedAmountMinor; }
    public long getRefundAmountMinor() { return refundAmountMinor; }
    public Status getStatus() { return status; }
    public String getPriceVersionId() { return priceVersionId; }
    public String getTraceId() { return traceId; }
    public Instant getCreatedAt() { return createdAt; }
}
