package io.quotapilot.infra.persistence;

import java.time.Instant;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quotapilot.common.DomainExceptions;
import io.quotapilot.common.TimeService;
import io.quotapilot.infra.idempotency.domain.IdempotencyPort;
import io.quotapilot.infra.outbox.domain.OutboxPort;
import io.quotapilot.ledger.domain.AmountKind;
import io.quotapilot.ledger.domain.LedgerEntryType;
import io.quotapilot.ledger.domain.LedgerPort;
import io.quotapilot.ledger.domain.ReservationStatus;
import io.quotapilot.ledger.domain.SettlementRecord;

/**
 * [M0/M11] 账本内核 JPA 适配器 —— 全部记账原子操作的事务边界。
 * 每个操作同事务写入：账本流水 + 状态实体 + Outbox 事件 + 幂等键（P3/P6，Q1）。
 * 幂等：requestId 唯一约束 + SettlementRecord 主键 + ADJUST 幂等键 + @Version 状态机并发保护。
 */
@Component
public class LedgerAdapter implements LedgerPort, io.quotapilot.reserve.domain.ReservationEngine.LedgerPortAccess {

    private final ReservationJpaRepo reservations;
    private final LedgerEntryJpaRepo ledgerEntries;
    private final SettlementJpaRepo settlements;
    private final ExposureJpaRepo exposures;
    private final OutboxPort outbox;
    private final IdempotencyPort idempotency;
    private final TimeService time;
    private final ObjectMapper json;

    public LedgerAdapter(ReservationJpaRepo reservations, LedgerEntryJpaRepo ledgerEntries,
                         SettlementJpaRepo settlements, ExposureJpaRepo exposures, OutboxPort outbox,
                         IdempotencyPort idempotency, TimeService time, ObjectMapper json) {
        this.reservations = reservations;
        this.ledgerEntries = ledgerEntries;
        this.settlements = settlements;
        this.exposures = exposures;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.time = time;
        this.json = json;
    }

    @Override
    @Transactional
    public HoldResult hold(HoldCmd cmd) {
        Instant now = time.now();
        if (reservations.existsByRequestId(cmd.requestId())) {
            ReservationEntity existing = reservations.findByRequestId(cmd.requestId()).orElse(null);
            if (existing != null) {
                return new HoldResult(cmd.requestId(), existing.holdId, existing.accountId,
                        existing.reservedAmountMinor, true);
            }
        }
        ReservationEntity re = new ReservationEntity();
        re.holdId = cmd.holdId() == null ? java.util.UUID.randomUUID().toString() : cmd.holdId();
        re.requestId = cmd.requestId();
        re.accountId = cmd.accountId();
        re.reservedAmountMinor = cmd.amountMinor();
        re.priceVersionId = cmd.priceVersionId();
        re.traceId = cmd.traceId();
        re.createdAt = now;
        re.expiresAt = cmd.expiresAt();
        re.status = ReservationStatus.RESERVED;
        try {
            reservations.saveAndFlush(re);
        } catch (DataIntegrityViolationException e) {
            throw new DomainExceptions.DuplicateRequest(cmd.requestId(), "reserve");
        }
        appendEntry(cmd.accountId(), cmd.requestId(), LedgerEntryType.HOLD, cmd.amountMinor(),
                AmountKind.ESTIMATE, cmd.priceVersionId(), "RESERVE", null, "reserve:" + cmd.requestId(), cmd.traceId());
        outbox.enqueue("reservation", cmd.requestId(), "reservation.reserved", payload(cmd.requestId(),
                "holdId=" + cmd.holdId() + ",amountMinor=" + cmd.amountMinor()));
        idempotency.put("reserve:" + cmd.requestId(), re.holdId);
        return new HoldResult(cmd.requestId(), re.holdId, cmd.accountId(), cmd.amountMinor(), false);
    }

    /** [M3] 引擎落账桥接：ReservationEngine.LedgerPortAccess。 */
    @Override
    @Transactional
    public io.quotapilot.reserve.domain.ReservationEngine.LedgerPortAccess.HoldOutcome doHold(
            io.quotapilot.reserve.domain.ReservationEngine.LedgerPortAccess.HoldParams params) {
        HoldResult r = hold(new HoldCmd(params.requestId(), null, params.accountId(), params.amountMinor(),
                params.priceVersionId(), params.expiresAt(), params.traceId()));
        return new io.quotapilot.reserve.domain.ReservationEngine.LedgerPortAccess.HoldOutcome(r.holdId(),
                r.duplicate());
    }

    @Override
    @Transactional
    public SettleResult settle(SettleCmd cmd) {
        Instant now = time.now();
        ReservationEntity re = reservations.findByRequestId(cmd.requestId())
                .orElseThrow(() -> new DomainExceptions.NotFound("预留不存在: " + cmd.requestId()));
        if (re.status == ReservationStatus.SETTLED) {
            return duplicateSettle(cmd.requestId());
        }
        if (re.status == ReservationStatus.RELEASED) {
            throw new DomainExceptions.RequestStateConflict(cmd.requestId(), "RESERVED", "RELEASED(用 settleLate)");
        }
        long refund = Math.max(0, cmd.estimateMinor() - cmd.actualCostMinor());
        boolean over = cmd.actualCostMinor() > cmd.estimateMinor();
        try {
            appendEntry(cmd.accountId(), cmd.requestId(), LedgerEntryType.SETTLE, cmd.actualCostMinor(),
                    AmountKind.ACTUAL, cmd.priceVersionId(), "SETTLE", null, null, cmd.traceId());
            if (refund > 0) {
                appendEntry(cmd.accountId(), cmd.requestId(), LedgerEntryType.RELEASE, refund,
                        AmountKind.ESTIMATE, cmd.priceVersionId(), "REFUND_SURPLUS", null, null, cmd.traceId());
            }
            re.status = ReservationStatus.SETTLED;
            re.finishedAt = now;
            re.finishReason = "SETTLED";
            upsertSettlement(cmd, cmd.actualCostMinor(), cmd.actualCostMinor(), refund,
                    SettlementRecord.Status.SETTLED, now);
            outbox.enqueue("request", cmd.requestId(), "request.settled",
                    payload(cmd.requestId(), "chargedMinor=" + cmd.actualCostMinor() + ",refundMinor=" + refund));
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new DomainExceptions.DuplicateRequest(cmd.requestId(), "settle");
        }
        return new SettleResult(cmd.requestId(), cmd.actualCostMinor(), refund, over, false);
    }

    @Override
    @Transactional
    public SettleResult settleLate(SettleCmd cmd) {
        Instant now = time.now();
        ReservationEntity re = reservations.findByRequestId(cmd.requestId())
                .orElseThrow(() -> new DomainExceptions.NotFound("预留不存在: " + cmd.requestId()));
        if (re.status == ReservationStatus.SETTLED) {
            return duplicateSettle(cmd.requestId());
        }
        ExposureRecordEntity exp = exposures.findByRequestId(cmd.requestId())
                .orElseThrow(() -> new DomainExceptions.RequestStateConflict(cmd.requestId(), "PENDING 敞口", "无敞口"));
        if (exp.state != io.quotapilot.ledger.domain.ExposureState.PENDING) {
            throw new DomainExceptions.DuplicateRequest(cmd.requestId(), "settleLate");
        }
        try {
            appendEntry(cmd.accountId(), cmd.requestId(), LedgerEntryType.SETTLE, cmd.actualCostMinor(),
                    AmountKind.ACTUAL, cmd.priceVersionId(), "LATE_SETTLE", null, null, cmd.traceId());
            exp.state = io.quotapilot.ledger.domain.ExposureState.SETTLED;
            exp.resolvedAt = now;
            exp.resolvedAmountMinor = cmd.actualCostMinor();
            exp.evidenceRef = "settle:" + cmd.requestId();
            upsertSettlement(cmd, cmd.actualCostMinor(), cmd.actualCostMinor(), 0,
                    SettlementRecord.Status.SETTLED, now);
            outbox.enqueue("request", cmd.requestId(), "request.settled",
                    payload(cmd.requestId(), "late=true,chargedMinor=" + cmd.actualCostMinor()));
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new DomainExceptions.DuplicateRequest(cmd.requestId(), "settleLate");
        }
        return new SettleResult(cmd.requestId(), cmd.actualCostMinor(), 0, false, false);
    }

    @Override
    @Transactional
    public ReleaseResult release(ReleaseCmd cmd) {
        Instant now = time.now();
        ReservationEntity re = reservations.findByRequestId(cmd.requestId())
                .orElseThrow(() -> new DomainExceptions.NotFound("预留不存在: " + cmd.requestId()));
        if (re.status == ReservationStatus.SETTLED) {
            throw new DomainExceptions.RequestStateConflict(cmd.requestId(), "RESERVED|RELEASED", "SETTLED");
        }
        if (re.status == ReservationStatus.RELEASED) {
            return new ReleaseResult(cmd.requestId(), re.reservedAmountMinor, exposureIdOf(cmd.requestId()), true);
        }
        try {
            appendEntry(cmd.accountId(), cmd.requestId(), LedgerEntryType.RELEASE, cmd.amountMinor(),
                    AmountKind.ESTIMATE, re.priceVersionId, cmd.reason(), null, null, cmd.traceId());
            re.status = ReservationStatus.RELEASED;
            re.finishedAt = now;
            re.finishReason = cmd.reason();
            upsertSettlement(new SettleCmd(cmd.requestId(), cmd.holdId(), cmd.accountId(), cmd.amountMinor(), 0,
                    re.priceVersionId, cmd.traceId()), 0, 0, cmd.amountMinor(),
                    SettlementRecord.Status.RELEASED, now);
            String exposureId = null;
            if (cmd.externalRisk()) {
                // Q8：释放 ≠ 无事 —— 同事务写入敞口等待对账（P2 诚实计量）
                ExposureRecordEntity exp = exposures.findByRequestId(cmd.requestId()).orElse(null);
                if (exp == null) {
                    exp = new ExposureRecordEntity();
                    exp.exposureId = java.util.UUID.randomUUID().toString();
                    exp.requestId = cmd.requestId();
                    exp.holdId = cmd.holdId();
                    exp.accountId = cmd.accountId();
                    exp.estimatedAmountMinor = cmd.amountMinor();
                    exp.reason = io.quotapilot.ledger.domain.ExposureReason.valueOf(cmd.exposureReason());
                    exp.state = io.quotapilot.ledger.domain.ExposureState.PENDING;
                    exp.createdAt = now;
                    exp.graceDeadline = cmd.graceDeadline();
                    exposures.save(exp);
                }
                exposureId = exp.exposureId;
            }
            outbox.enqueue("request", cmd.requestId(), "request.released",
                    payload(cmd.requestId(), "reason=" + cmd.reason() + ",externalRisk=" + cmd.externalRisk()));
            return new ReleaseResult(cmd.requestId(), cmd.amountMinor(), exposureId, false);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new DomainExceptions.DuplicateRequest(cmd.requestId(), "release");
        }
    }

    @Override
    @Transactional
    public AdjustResult adjust(AdjustCmd cmd) {
        if (cmd.idempotencyKey() != null) {
            Optional<LedgerEntryEntity> existing = ledgerEntries.findByIdempotencyKey(cmd.idempotencyKey());
            if (existing.isPresent()) {
                return new AdjustResult(existing.get().entryId, existing.get().amountMinor, true);
            }
        }
        String entryId;
        try {
            entryId = appendEntry(cmd.accountId(), cmd.requestId(), LedgerEntryType.ADJUST, cmd.amountMinor(),
                    AmountKind.ADJUSTMENT, null, cmd.reason(), cmd.evidenceRef(), cmd.idempotencyKey(), cmd.traceId());
        } catch (DataIntegrityViolationException e) {
            throw new DomainExceptions.DuplicateRequest(cmd.requestId(), "adjust");
        }
        outbox.enqueue("ledger", cmd.requestId() == null ? cmd.accountId() : cmd.requestId(), "ledger.adjusted",
                payload(cmd.requestId(), "amountMinor=" + cmd.amountMinor() + ",reason=" + cmd.reason()));
        return new AdjustResult(entryId, cmd.amountMinor(), false);
    }

    @Override
    @Transactional
    public AdjustResult closeExposure(String exposureId, String traceId) {
        Instant now = time.now();
        ExposureRecordEntity exp = exposures.findById(exposureId)
                .orElseThrow(() -> new DomainExceptions.NotFound("敞口不存在: " + exposureId));
        if (exp.state != io.quotapilot.ledger.domain.ExposureState.PENDING) {
            throw new DomainExceptions.DuplicateRequest(exp.requestId, "closeExposure");
        }
        try {
            String idemKey = "exposure-close:" + exposureId;
            String entryId = appendEntry(exp.accountId, exp.requestId, LedgerEntryType.ADJUST,
                    exp.estimatedAmountMinor, AmountKind.ADJUSTMENT, null, "EXPOSURE_CLOSED",
                    "exposure:" + exposureId, idemKey, traceId);
            exp.state = io.quotapilot.ledger.domain.ExposureState.CLOSED_WITH_ADJUSTMENT;
            exp.resolvedAt = now;
            exp.resolvedAmountMinor = exp.estimatedAmountMinor;
            exp.evidenceRef = "exposure:" + exposureId;
            outbox.enqueue("exposure", exposureId, "exposure.closed",
                    payload(exp.requestId, "cappedMinor=" + exp.estimatedAmountMinor));
            return new AdjustResult(entryId, exp.estimatedAmountMinor, false);
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException e) {
            throw new DomainExceptions.DuplicateRequest(exp.requestId, "closeExposure");
        }
    }

    // ---- 内部工具 ----

    private String appendEntry(String accountId, String requestId, LedgerEntryType type, long amountMinor,
                               AmountKind kind, String priceVersionId, String reason, String evidenceRef,
                               String idempotencyKey, String traceId) {
        LedgerEntryEntity e = new LedgerEntryEntity();
        e.entryId = java.util.UUID.randomUUID().toString();
        e.accountId = accountId;
        e.requestId = requestId;
        e.type = type;
        e.amountMinor = amountMinor;
        e.kind = kind;
        e.priceVersionId = priceVersionId;
        e.reason = reason;
        e.evidenceRef = evidenceRef;
        e.idempotencyKey = idempotencyKey;
        e.traceId = traceId;
        e.createdAt = time.now();
        ledgerEntries.saveAndFlush(e);
        return e.entryId;
    }

    private void upsertSettlement(SettleCmd cmd, long actual, long charged, long refund,
                                  SettlementRecord.Status status, Instant now) {
        SettlementRecordEntity sr = settlements.findById(cmd.requestId()).orElse(null);
        if (sr == null) {
            sr = new SettlementRecordEntity();
            sr.requestId = cmd.requestId();
            sr.holdId = cmd.holdId();
            sr.accountId = cmd.accountId();
            sr.priceVersionId = cmd.priceVersionId();
            sr.traceId = cmd.traceId();
            sr.createdAt = now;
        }
        sr.actualAmountMinor = actual;
        sr.chargedAmountMinor = charged;
        sr.refundAmountMinor = refund;
        sr.status = status;
        settlements.save(sr);
    }

    private SettleResult duplicateSettle(String requestId) {
        return settlements.findById(requestId)
                .map(sr -> new SettleResult(requestId, sr.chargedAmountMinor, sr.refundAmountMinor, false, true))
                .orElse(new SettleResult(requestId, 0, 0, false, true));
    }

    private String exposureIdOf(String requestId) {
        return exposures.findByRequestId(requestId).map(e -> e.exposureId).orElse(null);
    }

    private String payload(String requestId, String detail) {
        return "{\"requestId\":\"" + requestId + "\",\"" + detail.replace('=', ':').replace(',', ',') + "\"}";
    }
}
