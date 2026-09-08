package io.quotapilot.settlement.domain;

import java.time.Instant;
import java.util.Optional;

import io.quotapilot.alert.domain.Alert;
import io.quotapilot.alert.domain.AlertEmitterPort;
import io.quotapilot.alert.domain.AlertType;
import io.quotapilot.common.Amounts;
import io.quotapilot.common.DomainExceptions;
import io.quotapilot.common.TimeService;
import io.quotapilot.ledger.domain.ExposureRecord;
import io.quotapilot.ledger.domain.ExposureRepositoryPort;
import io.quotapilot.ledger.domain.ExposureReason;
import io.quotapilot.ledger.domain.LedgerPort;
import io.quotapilot.ledger.domain.Reservation;
import io.quotapilot.ledger.domain.ReservationRepositoryPort;
import io.quotapilot.pricing.domain.PriceCatalog;
import io.quotapilot.reserve.domain.ReservationGatePort;

/**
 * [M4] 结算与释放（纯领域服务）。
 * 时序分支（规范 M4）：
 * - 成功：settle → 入账 actual，退回 estimate − actual；actual > estimate 属超预留 → 结算 + P0 告警；
 * - 失败/取消/断连：release 全额退回，但请求可能已产生外部费用 → 同事务写 ExposureRecord(PENDING)（Q8）；
 * - 超时：预留到期由 sweeper 转 Exposure，宽限期内迟到回调正常结算（本类 settle 的迟到分支）。
 * 价格结算一律按预留时 priceVersionId 快照（P5）。全程幂等（P6）。
 */
public class SettlementService {

    private final ReservationRepositoryPort reservationRepo;
    private final ExposureRepositoryPort exposureRepo;
    private final PriceCatalog priceCatalog;
    private final LedgerPort ledgerPort;
    private final ReservationGatePort gate;
    private final AlertEmitterPort alerts;
    private final TimeService time;
    private final long exposureGraceSeconds;

    public SettlementService(ReservationRepositoryPort reservationRepo, ExposureRepositoryPort exposureRepo,
                             PriceCatalog priceCatalog, LedgerPort ledgerPort, ReservationGatePort gate,
                             AlertEmitterPort alerts, TimeService time, long exposureGraceSeconds) {
        this.reservationRepo = reservationRepo;
        this.exposureRepo = exposureRepo;
        this.priceCatalog = priceCatalog;
        this.ledgerPort = ledgerPort;
        this.gate = gate;
        this.alerts = alerts;
        this.time = time;
        this.exposureGraceSeconds = exposureGraceSeconds;
    }

    /** 按实际用量结算（正常或迟到回调共用），幂等。 */
    public SettlementResult settle(String requestId, long actualUnits) {
        Reservation r = reservationRepo.findByRequestId(requestId)
                .orElseThrow(() -> new DomainExceptions.NotFound("预留不存在: " + requestId));
        long unitPrice = priceCatalog.loadVersion(r.getPriceVersionId())
                .orElseThrow(() -> new DomainExceptions.PricingUnavailable("快照版本丢失: " + r.getPriceVersionId()))
                .getPricePerUnitMinor();
        long cost = Amounts.exactMultiply(actualUnits, unitPrice);

        if (r.getStatus() == io.quotapilot.ledger.domain.ReservationStatus.SETTLED) {
            return duplicateResult(requestId);
        }

        if (r.getStatus() == io.quotapilot.ledger.domain.ReservationStatus.RESERVED) {
            LedgerPort.SettleResult sr = ledgerPort.settle(new LedgerPort.SettleCmd(
                    requestId, r.getHoldId(), r.getAccountId(), r.getReservedAmountMinor(), cost,
                    r.getPriceVersionId(), r.getTraceId()));
            gateApplySafe(() -> gate.applySettlement(r.getAccountId(), r.getReservedAmountMinor(), cost));
            if (sr.overReserve()) {
                alerts.emit(Alert.of(AlertType.OVER_RESERVE, "P0", r.getAccountId(), requestId,
                        "实际结算超过预留: actual=" + cost + " > estimate=" + r.getReservedAmountMinor()
                                + "，需校准预留估算模型",
                        time.now()));
            }
            return SettlementResult.settled(requestId, cost, sr.refundMinor(), sr.overReserve());
        }

        // RELEASED：迟到结算路径
        Optional<ExposureRecord> exposure = exposureRepo.findByRequestId(requestId);
        if (exposure.isPresent() && exposure.get().getState() == io.quotapilot.ledger.domain.ExposureState.PENDING) {
            ledgerPort.settleLate(new LedgerPort.SettleCmd(requestId, r.getHoldId(), r.getAccountId(),
                    r.getReservedAmountMinor(), cost, r.getPriceVersionId(), r.getTraceId()));
            gateApplySafe(() -> gate.applyLateSettlement(r.getAccountId(), cost));
            return SettlementResult.settled(requestId, cost, 0, false);
        }
        // 无敞口（曾判定未触达供应商）但供应商回调到达 → 按 ADJUST 补收，幂等键防重（P6）
        ledgerPort.adjust(new LedgerPort.AdjustCmd(requestId, r.getAccountId(), cost,
                "LATE_USAGE_AFTER_RELEASE", "callback:" + requestId, "settle:" + requestId, r.getTraceId()));
        gateApplySafe(() -> gate.adjustSettled(r.getAccountId(), cost));
        return SettlementResult.adjusted(requestId, cost);
    }

    /** 释放预留（失败/取消/断连/超时到期）。externalRisk=true 时必须转敞口等待对账（Q6/Q8）。 */
    public SettlementResult release(String requestId, ReleaseReason reason, boolean externalRisk) {
        Reservation r = reservationRepo.findByRequestId(requestId)
                .orElseThrow(() -> new DomainExceptions.NotFound("预留不存在: " + requestId));
        if (r.getStatus() == io.quotapilot.ledger.domain.ReservationStatus.SETTLED) {
            throw new DomainExceptions.RequestStateConflict(requestId, "RESERVED|RELEASED", "SETTLED");
        }
        if (r.getStatus() == io.quotapilot.ledger.domain.ReservationStatus.RELEASED) {
            return SettlementResult.duplicate(requestId, "RELEASED", 0, r.getReservedAmountMinor());
        }
        Instant now = time.now();
        ExposureReason exposureReason = switch (reason) {
            case TIMEOUT -> ExposureReason.TIMEOUT;
            case DISCONNECTED -> ExposureReason.DISCONNECT;
            default -> ExposureReason.SUPPLIER_UNKNOWN;
        };
        LedgerPort.ReleaseResult rr = ledgerPort.release(new LedgerPort.ReleaseCmd(
                requestId, r.getHoldId(), r.getAccountId(), r.getReservedAmountMinor(), reason.name(),
                externalRisk, externalRisk ? exposureReason.name() : null,
                externalRisk ? now.plusSeconds(exposureGraceSeconds) : null, r.getTraceId()));
        gateApplySafe(() -> gate.releaseHold(r.getAccountId(), r.getReservedAmountMinor()));
        if (rr.exposureId() != null) {
            alerts.emit(Alert.of(AlertType.RECONCILE_GAP, "INFO", r.getAccountId(), requestId,
                    "请求已释放但可能已产生外部费用，转敞口等待对账: exposure=" + rr.exposureId(), now));
        }
        return new SettlementResult(requestId, "RELEASED", 0, 0, rr.refundedMinor(), false, rr.duplicate(), false);
    }

    private SettlementResult duplicateResult(String requestId) {
        return SettlementResult.duplicate(requestId, "SETTLED", 0, 0);
    }

    private void gateApplySafe(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            // Redis 更新失败不阻断结算：DB 为权威，sweeper 依据账本重建（Q1/P7）
        }
    }

    /** 供 sweeper 使用的告警出口（HOLD_LEAK / RECONCILE_GAP）。 */
    public void emitHoldLeak(Reservation r) {
        alerts.emit(Alert.of(AlertType.HOLD_LEAK, "WARN", r.getAccountId(), r.getRequestId(),
                "预留泄漏: hold=" + r.getHoldId() + " 已到期未收敛，转敞口处理", time.now()));
    }

    public void emitReconcileGap(ExposureRecord e, long cappedAmount) {
        alerts.emit(Alert.of(AlertType.RECONCILE_GAP, "WARN", e.getAccountId(), e.getRequestId(),
                "敞口宽限期届满按 estimate 封顶入账: exposure=" + e.getExposureId() + " amount=" + cappedAmount,
                time.now()));
    }

    public long getExposureGraceSeconds() {
        return exposureGraceSeconds;
    }
}
