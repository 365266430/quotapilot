package io.quotapilot.settlement.domain;

import java.time.Instant;
import java.util.List;

import io.quotapilot.common.TimeService;
import io.quotapilot.ledger.domain.ExposureRecord;
import io.quotapilot.ledger.domain.ExposureRepositoryPort;
import io.quotapilot.ledger.domain.LedgerPort;
import io.quotapilot.ledger.domain.Reservation;
import io.quotapilot.ledger.domain.ReservationRepositoryPort;
import io.quotapilot.reserve.domain.ReservationGatePort;

/**
 * [M11/P7] 补偿调度（sweeper）领域逻辑，调度器在基础设施层周期驱动：
 * 1. 过期未结算的 Reservation → 转 ExposureRecord（宽限期），不允许悬挂；
 * 2. 超宽限期的 Exposure → CLOSED_WITH_ADJUSTMENT（estimate 封顶入账 + 告警）；
 * 3. Redis 与 DB 权威账本对账重建（Q1）。
 * 全部操作幂等：重复扫描安全（P6）。
 */
public class SweeperService {

    private final ReservationRepositoryPort reservationRepo;
    private final ExposureRepositoryPort exposureRepo;
    private final SettlementService settlementService;
    private final LedgerPort ledgerPort;
    private final ReservationGatePort gate;
    private final TimeService time;

    public SweeperService(ReservationRepositoryPort reservationRepo, ExposureRepositoryPort exposureRepo,
                          SettlementService settlementService, LedgerPort ledgerPort, ReservationGatePort gate,
                          TimeService time) {
        this.reservationRepo = reservationRepo;
        this.exposureRepo = exposureRepo;
        this.settlementService = settlementService;
        this.ledgerPort = ledgerPort;
        this.gate = gate;
        this.time = time;
    }

    /** 过期预留 → 释放 + 敞口（结果未知，可能已计费）。幂等：release 状态机防重。 */
    public int sweepExpiredReservations() {
        Instant now = time.now();
        List<Reservation> expired = reservationRepo.findExpiredBefore(now);
        int handled = 0;
        for (Reservation r : expired) {
            try {
                settlementService.release(r.getRequestId(), ReleaseReason.TIMEOUT, true);
                settlementService.emitHoldLeak(r);
                handled++;
            } catch (RuntimeException e) {
                // 单条失败不影响整体，下轮重扫
            }
        }
        return handled;
    }

    /** 超宽限期敞口 → 封顶关闭（Q2）。幂等：exposure 状态机 + ADJUST 幂等键防重。 */
    public int sweepExpiredExposures() {
        Instant now = time.now();
        List<ExposureRecord> overdue = exposureRepo.findPendingPastGrace(now);
        int handled = 0;
        for (ExposureRecord e : overdue) {
            try {
                ledgerPort.closeExposure(e.getExposureId(), null);
                settlementService.emitReconcileGap(e, e.getEstimatedAmountMinor());
                handled++;
            } catch (RuntimeException ex) {
                // 幂等冲突或暂时失败，下轮重扫
            }
        }
        return handled;
    }

    /** Redis 账本对账（DB 权威 → Redis 重建/纠正，Q1 第 3、4 点）。返回纠正的账户数。 */
    public int reconcileRedis(io.quotapilot.ledger.domain.AccountPort accountPort,
                              io.quotapilot.ledger.domain.LedgerQueryPort ledgerQuery) {
        Instant since = time.now().minusSeconds(3600);
        List<String> accountIds = ledgerQuery.recentActiveAccountIds(since);
        int corrected = 0;
        for (String accountId : accountIds) {
            try {
                long limit = accountPort.find(accountId).map(io.quotapilot.ledger.domain.AccountPort.AccountSnapshot::quotaLimitMinor).orElse(0L);
                long settled = ledgerQuery.sumsByType(accountId)
                        .getOrDefault(io.quotapilot.ledger.domain.LedgerEntryType.SETTLE, 0L)
                        + ledgerQuery.sumsByType(accountId).getOrDefault(io.quotapilot.ledger.domain.LedgerEntryType.ADJUST, 0L);
                long held = reservationRepo.sumActiveHolds(accountId);
                if (gate.reconcile(accountId, limit, settled, held)) {
                    corrected++;
                }
            } catch (RuntimeException e) {
                // 下轮重试
            }
        }
        return corrected;
    }
}
