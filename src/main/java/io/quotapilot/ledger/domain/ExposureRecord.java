package io.quotapilot.ledger.domain;

import java.time.Instant;
import java.util.UUID;

import io.quotapilot.common.DomainExceptions;

/**
 * [M0] 未决敞口（ExposureRecord）：「请求可能已产生外部费用」但本地尚未结算的不确定性。
 * 生命周期（规范 §5.1）：PENDING →（宽限期内回调到达）SETTLED / PENDING →（宽限期过）CLOSED_WITH_ADJUSTMENT。
 * 注意 P2：敞口的存在是诚实的——QuotaPilot 不假装能绝对阻止外部费用。
 */
public class ExposureRecord {

    private final String exposureId;
    private final String requestId;
    private final String holdId;
    private final String accountId;
    private final long estimatedAmountMinor;   // 默认按 estimate 封顶口径
    private final ExposureReason reason;       // TIMEOUT / DISCONNECT / SUPPLIER_UNKNOWN
    private final Instant createdAt;
    private final Instant graceDeadline;       // 宽限期截止

    private ExposureState state;
    private Instant resolvedAt;
    private long resolvedAmountMinor;          // SETTLED 时为实际结算额；CLOSED 时为封顶入账额
    private String evidenceRef;

    public ExposureRecord(String exposureId, String requestId, String holdId, String accountId,
                          long estimatedAmountMinor, ExposureReason reason, Instant createdAt, Instant graceDeadline) {
        this.exposureId = exposureId == null ? UUID.randomUUID().toString() : exposureId;
        this.requestId = requestId;
        this.holdId = holdId;
        this.accountId = accountId;
        this.estimatedAmountMinor = estimatedAmountMinor;
        this.reason = reason;
        this.createdAt = createdAt;
        this.graceDeadline = graceDeadline;
        this.state = ExposureState.PENDING;
    }

    public static ExposureRecord restore(String exposureId, String requestId, String holdId, String accountId,
                                         long estimatedAmountMinor, ExposureReason reason, Instant createdAt,
                                         Instant graceDeadline, ExposureState state, Instant resolvedAt,
                                         long resolvedAmountMinor, String evidenceRef) {
        ExposureRecord e = new ExposureRecord(exposureId, requestId, holdId, accountId, estimatedAmountMinor,
                reason, createdAt, graceDeadline);
        e.state = state;
        e.resolvedAt = resolvedAt;
        e.resolvedAmountMinor = resolvedAmountMinor;
        e.evidenceRef = evidenceRef;
        return e;
    }

    public boolean isPastGrace(Instant now) {
        return state == ExposureState.PENDING && graceDeadline != null && now.isAfter(graceDeadline);
    }

    public void markSettled(long actualAmountMinor, Instant at, String evidenceRef) {
        transition(ExposureState.SETTLED, at, actualAmountMinor, evidenceRef);
    }

    public void markClosedWithAdjustment(long cappedAmountMinor, Instant at, String evidenceRef) {
        transition(ExposureState.CLOSED_WITH_ADJUSTMENT, at, cappedAmountMinor, evidenceRef);
    }

    private void transition(ExposureState target, Instant at, long amount, String evidence) {
        if (this.state != ExposureState.PENDING) {
            throw new DomainExceptions.RequestStateConflict(requestId, "PENDING", state.name());
        }
        this.state = target;
        this.resolvedAt = at;
        this.resolvedAmountMinor = amount;
        this.evidenceRef = evidence;
    }

    public String getExposureId() { return exposureId; }
    public String getRequestId() { return requestId; }
    public String getHoldId() { return holdId; }
    public String getAccountId() { return accountId; }
    public long getEstimatedAmountMinor() { return estimatedAmountMinor; }
    public ExposureReason getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getGraceDeadline() { return graceDeadline; }
    public ExposureState getState() { return state; }
    public Instant getResolvedAt() { return resolvedAt; }
    public long getResolvedAmountMinor() { return resolvedAmountMinor; }
    public String getEvidenceRef() { return evidenceRef; }
}
