package io.quotapilot.ledger.domain;

import java.time.Instant;
import java.util.UUID;

import io.quotapilot.common.DomainExceptions;

/**
 * [M0] 预留（Reservation）领域对象 —— 并发「预留—结算—释放」状态机的中心（见规范 §5.1）。
 * 状态转移：RESERVED → SETTLED（正常结算）/ RELEASED（失败/取消/断连/超时到期）。
 * 所有转移必须记录 reason + evidence。
 */
public class Reservation {

    private final String holdId;
    private final String requestId;
    private final String accountId;
    private final long reservedAmountMinor;   // estimate 语义（P1）
    private final String priceVersionId;      // P5 价格快照固定
    private final String traceId;
    private final Instant createdAt;
    private final Instant expiresAt;          // 预留 TTL，超时由 sweeper 收敛

    private ReservationStatus status;
    private Instant finishedAt;
    private String finishReason;              // SETTLED / FAILED / CANCELLED / DISCONNECTED / TIMEOUT / EXPIRED

    public Reservation(String holdId, String requestId, String accountId, long reservedAmountMinor,
                       String priceVersionId, String traceId, Instant createdAt, Instant expiresAt) {
        this.holdId = holdId == null ? UUID.randomUUID().toString() : holdId;
        this.requestId = requestId;
        this.accountId = accountId;
        this.reservedAmountMinor = reservedAmountMinor;
        this.priceVersionId = priceVersionId;
        this.traceId = traceId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.status = ReservationStatus.RESERVED;
    }

    public static Reservation restore(String holdId, String requestId, String accountId, long reservedAmountMinor,
                                      String priceVersionId, String traceId, Instant createdAt, Instant expiresAt,
                                      ReservationStatus status, Instant finishedAt, String finishReason) {
        Reservation r = new Reservation(holdId, requestId, accountId, reservedAmountMinor, priceVersionId,
                traceId, createdAt, expiresAt);
        r.status = status;
        r.finishedAt = finishedAt;
        r.finishReason = finishReason;
        return r;
    }

    public boolean isExpired(Instant now) {
        return status == ReservationStatus.RESERVED && expiresAt != null && now.isAfter(expiresAt);
    }

    public void markSettled(Instant at) {
        transition(ReservationStatus.SETTLED, at, "SETTLED");
    }

    public void markReleased(Instant at, String reason) {
        transition(ReservationStatus.RELEASED, at, reason);
    }

    private void transition(ReservationStatus target, Instant at, String reason) {
        if (this.status != ReservationStatus.RESERVED) {
            throw new DomainExceptions.RequestStateConflict(requestId, "RESERVED", status.name());
        }
        this.status = target;
        this.finishedAt = at;
        this.finishReason = reason;
    }

    public String getHoldId() { return holdId; }
    public String getRequestId() { return requestId; }
    public String getAccountId() { return accountId; }
    public long getReservedAmountMinor() { return reservedAmountMinor; }
    public String getPriceVersionId() { return priceVersionId; }
    public String getTraceId() { return traceId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public ReservationStatus getStatus() { return status; }
    public Instant getFinishedAt() { return finishedAt; }
    public String getFinishReason() { return finishReason; }
}
