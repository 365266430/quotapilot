package io.quotapilot.ledger.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * [M0] 账本流水（不可变）。所有金额变化必须以流水表达，禁止无痕修改余额（P3）。
 * amountMinor 语义：HOLD/SETTLE/RELEASE 恒为正数，方向由 type 决定；ADJUST 带符号（+补收/-退回）。
 * 余额重算：settledSum = ΣSETTLE + ΣADJUST；heldSum = ΣHOLD − ΣRELEASE；available = limit − settledSum − heldSum。
 */
public final class LedgerEntry {

    private final String entryId;
    private final String accountId;
    private final String requestId;       // 可空（如人工 adjust 无请求上下文）
    private final LedgerEntryType type;
    private final long amountMinor;
    private final AmountKind kind;        // P1：estimate/actual/adjustment 标注
    private final String priceVersionId;  // 可空
    private final String reason;          // 转移依据
    private final String evidenceRef;     // 证据引用（对账修正必填）
    private final String idempotencyKey;  // ADJUST 防重复入账的唯一键，可空
    private final String traceId;
    private final Instant createdAt;

    public LedgerEntry(String entryId, String accountId, String requestId, LedgerEntryType type, long amountMinor,
                       AmountKind kind, String priceVersionId, String reason, String evidenceRef,
                       String idempotencyKey, String traceId, Instant createdAt) {
        if (amountMinor == 0) {
            throw new IllegalArgumentException("账本流水金额不允许为 0");
        }
        if (type == LedgerEntryType.ADJUST && amountMinor < 0 && evidenceRef == null) {
            throw new IllegalArgumentException("ADJUST 必须携带证据引用");
        }
        this.entryId = entryId == null ? UUID.randomUUID().toString() : entryId;
        this.accountId = accountId;
        this.requestId = requestId;
        this.type = type;
        this.amountMinor = amountMinor;
        this.kind = kind;
        this.priceVersionId = priceVersionId;
        this.reason = reason;
        this.evidenceRef = evidenceRef;
        this.idempotencyKey = idempotencyKey;
        this.traceId = traceId;
        this.createdAt = createdAt;
    }

    public String getEntryId() { return entryId; }
    public String getAccountId() { return accountId; }
    public String getRequestId() { return requestId; }
    public LedgerEntryType getType() { return type; }
    public long getAmountMinor() { return amountMinor; }
    public AmountKind getKind() { return kind; }
    public String getPriceVersionId() { return priceVersionId; }
    public String getReason() { return reason; }
    public String getEvidenceRef() { return evidenceRef; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getTraceId() { return traceId; }
    public Instant getCreatedAt() { return createdAt; }
}
