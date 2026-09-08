package io.quotapilot.ledger.domain;

import java.time.Instant;

/**
 * [M0] 账本内核端口：全部记账原子操作，由基础设施层实现（JPA 事务边界 + Outbox 同事务，见 P3/P4/P6）。
 * 幂等保证：requestId 唯一约束，重复调用返回首次结果（DuplicateRequest 异常由调用方捕获处理）。
 */
public interface LedgerPort {

    /** 预留落账：同事务写 Reservation(RESERVED) + LedgerEntry(HOLD, ESTIMATE) + Outbox 事件。 */
    HoldResult hold(HoldCmd cmd);

    /** 正常结算：SETTLE(actual) 入账 + RELEASE(estimate−actual) 退回余量；超预留时全入账并标记 overReserve。 */
    SettleResult settle(SettleCmd cmd);

    /** 迟到结算（敞口 PENDING 期）：预留已释放，仅补记 SETTLE(actual) 并将敞口置为 SETTLED。 */
    SettleResult settleLate(SettleCmd cmd);

    /** 释放：RELEASE(estimate) 全额退回 + Reservation→RELEASED；externalRisk=true 时同事务写 ExposureRecord(PENDING)（Q8）。 */
    ReleaseResult release(ReleaseCmd cmd);

    /** 对账修正入账：ADJUST(+/-) 流水，必须携带证据引用与幂等键（P6）。 */
    AdjustResult adjust(AdjustCmd cmd);

    /** 敞口宽限期届满收敛：按 estimate 封顶 ADJUST 入账，ExposureRecord → CLOSED_WITH_ADJUSTMENT（同事务）。 */
    AdjustResult closeExposure(String exposureId, String traceId);

    // ---- 命令/结果 ----

    record HoldCmd(String requestId, String holdId, String accountId, long amountMinor, String priceVersionId,
                   Instant expiresAt, String traceId) {}

    record HoldResult(String requestId, String holdId, String accountId, long amountMinor, boolean duplicate) {}

    record SettleCmd(String requestId, String holdId, String accountId, long estimateMinor, long actualCostMinor,
                     String priceVersionId, String traceId) {}

    record SettleResult(String requestId, long chargedMinor, long refundMinor, boolean overReserve, boolean duplicate) {}

    record ReleaseCmd(String requestId, String holdId, String accountId, long amountMinor, String reason,
                      boolean externalRisk, String exposureReason, Instant graceDeadline, String traceId) {}

    record ReleaseResult(String requestId, long refundedMinor, String exposureId, boolean duplicate) {}

    record AdjustCmd(String requestId, String accountId, long amountMinor, String reason, String evidenceRef,
                     String idempotencyKey, String traceId) {}

    record AdjustResult(String entryId, long amountMinor, boolean duplicate) {}
}
