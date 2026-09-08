package io.quotapilot.reconcile.domain;

import java.util.List;

import io.quotapilot.supplier.domain.SupplierChargeStorePort;

/**
 * [M5] 对账服务：把「供应商侧真实用量账单」与「本地账目」核对，产出差额修正，收敛未决敞口。
 * 差异分类（规范 M5）：
 * - 供应商有、本地无预留 → SUPPLIER_BILLED_NO_RESERVATION（ADJUST 补收）；
 * - 供应商有、本地已结算 → 按净额差 ADJUST（多补/退）；
 * - 供应商有、本地已释放 → 迟到结算路径（敞口 SETTLED 或 ADJUST 补收）；
 * - 本地有、供应商无 → UNBILLED_LOCALLY 仅分类告警（保守，不自动退款）。
 * 幂等：所有 ADJUST 携带稳定幂等键 reconcile:{supplierRequestId}:{classify}，重复对账不重复入账（P6）。
 */
public class ReconciliationService {

    private final SupplierChargeStorePort supplierCharges;
    private final io.quotapilot.ledger.domain.ReservationRepositoryPort reservationRepo;
    private final io.quotapilot.ledger.domain.LedgerPort ledgerPort;
    private final io.quotapilot.ledger.domain.LedgerQueryPort ledgerQuery;
    private final io.quotapilot.settlement.domain.SettlementService settlementService;
    private final io.quotapilot.metering.domain.UsageEventPort usageEvents;
    private final io.quotapilot.alert.domain.AlertEmitterPort alerts;
    private final io.quotapilot.common.TimeService time;

    public ReconciliationService(SupplierChargeStorePort supplierCharges,
                                 io.quotapilot.ledger.domain.ReservationRepositoryPort reservationRepo,
                                 io.quotapilot.ledger.domain.LedgerPort ledgerPort,
                                 io.quotapilot.ledger.domain.LedgerQueryPort ledgerQuery,
                                 io.quotapilot.settlement.domain.SettlementService settlementService,
                                 io.quotapilot.metering.domain.UsageEventPort usageEvents,
                                 io.quotapilot.alert.domain.AlertEmitterPort alerts,
                                 io.quotapilot.common.TimeService time) {
        this.supplierCharges = supplierCharges;
        this.reservationRepo = reservationRepo;
        this.ledgerPort = ledgerPort;
        this.ledgerQuery = ledgerQuery;
        this.settlementService = settlementService;
        this.usageEvents = usageEvents;
        this.alerts = alerts;
        this.time = time;
    }

    public record Adjustment(String requestId, String classify, long deltaMinor, String idempotencyKey) {}

    public record ReconciliationReport(int scanned, int adjusted, List<Adjustment> adjustments, List<String> gaps) {}

    public ReconciliationReport reconcile(String accountId) {
        List<SupplierChargeStorePort.SupplierCharge> charges = accountId == null
                ? supplierCharges.listAll()
                : supplierCharges.findByAccount(accountId);
        java.util.List<Adjustment> adjustments = new java.util.ArrayList<>();
        java.util.List<String> gaps = new java.util.ArrayList<>();
        int adjusted = 0;
        for (SupplierChargeStorePort.SupplierCharge charge : charges) {
            // 供应商用量事件先幂等留痕（对账证据链）
            usageEvents.record(new io.quotapilot.metering.domain.UsageEvent(null, charge.requestId(),
                    charge.supplierRequestId(), charge.accountId(), charge.model() + "|TOKEN", "TOKEN",
                    charge.units(), charge.billedAt(),
                    io.quotapilot.metering.domain.UsageSource.SUPPLIER_CALLBACK, charge.seq(), null));

            var reservation = reservationRepo.findByRequestId(charge.requestId());
            if (reservation.isEmpty()) {
                adjusted += adjust(charge, charge.amountMinor(), "SUPPLIER_BILLED_NO_RESERVATION", adjustments);
                continue;
            }
            var res = reservation.get();
            switch (res.getStatus()) {
                case RESERVED -> {
                    // 在途：留给正常结算/超时 sweeper，不动账
                }
                case SETTLED -> {
                    long localNet = ledgerQuery.netChargedByRequest(charge.requestId());
                    long delta = charge.amountMinor() - localNet;
                    if (delta > 0) {
                        adjusted += adjust(charge, delta, "SUPPLIER_BILLED_MORE", adjustments);
                    } else if (delta < 0) {
                        adjusted += adjust(charge, delta, "SUPPLIER_BILLED_LESS", adjustments);
                    }
                }
                case RELEASED -> {
                    // 已释放但供应商确实计费（Q8）→ 迟到结算/补收路径（幂等）
                    settlementService.settle(charge.requestId(), charge.units());
                }
                default -> {
                }
            }
        }
        // 本地已结算但供应商无账单 → 仅分类告警（保守不退款）
        gaps = findLocalOnlySettled(charges);
        if (!gaps.isEmpty()) {
            alerts.emit(io.quotapilot.alert.domain.Alert.of(
                    io.quotapilot.alert.domain.AlertType.RECONCILE_GAP, "INFO", accountId, null,
                    "对账发现本地已结算但供应商无账单: " + gaps, time.now()));
        }
        return new ReconciliationReport(charges.size(), adjusted, List.copyOf(adjustments), List.copyOf(gaps));
    }

    private int adjust(SupplierChargeStorePort.SupplierCharge charge, long delta, String classify,
                       List<Adjustment> out) {
        String idemKey = "reconcile:" + charge.supplierRequestId() + ":" + classify;
        try {
            io.quotapilot.ledger.domain.LedgerPort.AdjustResult r = ledgerPort.adjust(
                    new io.quotapilot.ledger.domain.LedgerPort.AdjustCmd(charge.requestId(), charge.accountId(),
                            delta, "RECONCILE_" + classify, "supplier:" + charge.supplierRequestId(), idemKey, null));
            out.add(new Adjustment(charge.requestId(), classify, r.amountMinor(), idemKey));
            return r.duplicate() ? 0 : 1;
        } catch (io.quotapilot.common.DomainExceptions.IdempotencyConflict e) {
            return 0;
        }
    }

    private List<String> findLocalOnlySettled(List<SupplierChargeStorePort.SupplierCharge> charges) {
        java.util.Set<String> supplierRequestIds = charges.stream()
                .map(SupplierChargeStorePort.SupplierCharge::requestId)
                .collect(java.util.stream.Collectors.toSet());
        return reservationRepo.findSettledRequestIds(1000).stream()
                .filter(id -> !supplierRequestIds.contains(id))
                .toList();
    }
}
