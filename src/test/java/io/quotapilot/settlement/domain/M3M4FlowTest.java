package io.quotapilot.settlement.domain;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quotapilot.alert.domain.Alert;
import io.quotapilot.alert.domain.AlertType;
import io.quotapilot.common.Amounts;
import io.quotapilot.common.DomainExceptions;
import io.quotapilot.common.TimeService;
import io.quotapilot.ledger.domain.ExposureState;
import io.quotapilot.ledger.domain.LedgerEntryType;
import io.quotapilot.ledger.domain.ReservationStatus;
import io.quotapilot.metering.domain.CallbackService;
import io.quotapilot.metering.domain.UsageEvent;
import io.quotapilot.metering.domain.UsageEventPort;
import io.quotapilot.metering.domain.UsageSource;
import io.quotapilot.pricing.domain.PriceCatalog;
import io.quotapilot.pricing.domain.Sku;
import io.quotapilot.quota.domain.DefaultQuotaPolicyResolver;
import io.quotapilot.quota.domain.QuotaRule;
import io.quotapilot.reserve.domain.ReservationEngine;
import io.quotapilot.reserve.domain.ReserveCommand;
import io.quotapilot.reserve.domain.ReserveResult;
import io.quotapilot.support.FakeGate;
import io.quotapilot.support.FakeLedger;
import io.quotapilot.support.Support.FakeAccounts;
import io.quotapilot.support.Support.FakePrices;
import io.quotapilot.support.Support.FakeRules;
import io.quotapilot.support.Support.MutableTime;
import io.quotapilot.support.Support.RecordingAlerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [M3/M4/M11] 领域流程测试（内存假实现）：预留/结算/释放三分支、sweeper 收敛、回调幂等、
 * 账本回放一致性（P3）、DB 失败补偿（Q1）、超预留告警。
 */
class M3M4FlowTest {

    MutableTime time;
    FakeRules rules;
    FakeAccounts accounts;
    FakePrices prices;
    FakeGate gate;
    FakeLedger ledger;
    RecordingAlerts alerts;
    ReservationEngine engine;
    SettlementService settlement;
    SweeperService sweeper;
    CallbackService callbacks;
    Sku sku = new Sku("gpt", io.quotapilot.pricing.domain.UsageType.TOKEN);
    String accountId;

    @BeforeEach
    void setUp() {
        time = new MutableTime();
        rules = new FakeRules();
        accounts = new FakeAccounts();
        prices = new FakePrices();
        gate = new FakeGate();
        ledger = new FakeLedger(time);
        alerts = new RecordingAlerts();
        rules.saveRule(new QuotaRule("r-user", io.quotapilot.ledger.domain.ScopeType.USER, "u1", null, 10_000L,
                "CNY", false), "op", "tr");
        var catalog = new PriceCatalog(prices);
        catalog.publish(sku, 1L, "CNY", time.now());
        DefaultQuotaPolicyResolver resolver = new DefaultQuotaPolicyResolver(rules, 0, "CNY");
        ReservationEngine.LedgerPortAccess ledgerAccess = params -> {
            var r = ledger.hold(new io.quotapilot.ledger.domain.LedgerPort.HoldCmd(params.requestId(), null,
                    params.accountId(), params.amountMinor(), params.priceVersionId(), params.expiresAt(),
                    params.traceId()));
            return new ReservationEngine.LedgerPortAccess.HoldOutcome(r.holdId(), r.duplicate());
        };
        engine = new ReservationEngine(resolver, accounts, catalog, gate, ledgerAccess, ledger, Optional.empty(),
                time, 60L, "CNY");
        settlement = new SettlementService(ledger, ledger.exposurePort(), catalog, ledger, gate, alerts, time, 300L);
        sweeper = new SweeperService(ledger, ledger.exposurePort(), settlement, ledger, gate, time);
        callbacks = new CallbackService(new MemoryUsageEvents(), settlement, time);
        accountId = accounts.getOrCreate(io.quotapilot.ledger.domain.ScopeType.USER, "u1", 10_000L, "CNY").accountId();
        gate.init(accountId, 10_000L);
    }

    ReserveResult reserve(String requestId, long units) {
        return engine.reserve(new ReserveCommand(requestId,
                new ReserveCommand.ScopeValues("u1", null, null), "gpt", "TOKEN", units, 0, "tr-" + requestId));
    }

    @Test
    void 预留_结算_退回余量_账本可回放且余额一致() {
        ReserveResult r = reserve("req-1", 1000L);
        assertThat(r.estimateMinor()).isEqualTo(1000L);
        assertThat(gate.accounts.get(accountId).held()).isEqualTo(1000L);

        SettlementResult s = settlement.settle("req-1", 700L);
        assertThat(s.chargedMinor()).isEqualTo(700L);
        assertThat(s.refundMinor()).isEqualTo(300L);
        assertThat(gate.accounts.get(accountId).held()).isEqualTo(0L);
        assertThat(gate.accounts.get(accountId).settled()).isEqualTo(700L);

        // 回放审计：重算余额与当前值一致（P3）
        // 口径：settled = ΣSETTLE + ΣADJUST；held = max(0, ΣHOLD − ΣSETTLE − ΣRELEASE)
        var sums = ledger.sumsByType(accountId);
        long settledSum = sums.get(LedgerEntryType.SETTLE) + sums.get(LedgerEntryType.ADJUST);
        long heldSum = Math.max(0, sums.get(LedgerEntryType.HOLD) - sums.get(LedgerEntryType.SETTLE)
                - sums.get(LedgerEntryType.RELEASE));
        long available = 10_000L - settledSum - heldSum;
        assertThat(available).isEqualTo(9_300L);
        assertThat(ledger.reservations.get("req-1").getStatus()).isEqualTo(ReservationStatus.SETTLED);
    }

    @Test
    void 预算不足拒绝_不残留任何流水或预留() {
        assertThatThrownBy(() -> reserve("req-poor", 20_000L))
                .isInstanceOf(DomainExceptions.QuotaExceeded.class);
        assertThat(ledger.entries).isEmpty();
        assertThat(ledger.reservations).isEmpty();
        assertThat(gate.accounts.get(accountId).held()).isEqualTo(0L);
    }

    @Test
    void Q1_DB落账失败_立即补偿释放Redis() {
        ledger.failNextHold = true;
        assertThatThrownBy(() -> reserve("req-dbfail", 500L))
                .isInstanceOf(DomainExceptions.HoldFailed.class);
        assertThat(gate.accounts.get(accountId).held()).isEqualTo(0L); // 补偿释放
        assertThat(ledger.entries).isEmpty();
    }

    @Test
    void 超预留_全额入账并触发P0告警() {
        reserve("req-over", 1000L);
        SettlementResult s = settlement.settle("req-over", 1200L);
        assertThat(s.chargedMinor()).isEqualTo(1200L);
        assertThat(s.overReserve()).isTrue();
        assertThat(alerts.alerts).anyMatch(a -> a.type() == AlertType.OVER_RESERVE && "P0".equals(a.severity()));
    }

    @Test
    void 失败释放_转敞口_迟到回调正常结算_Q8() {
        reserve("req-fail", 1000L);
        SettlementResult rel = settlement.release("req-fail", ReleaseReason.FAILED, true);
        assertThat(rel.refundMinor()).isEqualTo(1000L);
        assertThat(gate.accounts.get(accountId).held()).isEqualTo(0L);
        assertThat(ledger.exposures.get("req-fail").getState()).isEqualTo(ExposureState.PENDING);

        // 迟到回调：宽限期内到达 → 正常结算（Q2）
        SettlementResult late = settlement.settle("req-fail", 800L);
        assertThat(late.chargedMinor()).isEqualTo(800L);
        assertThat(ledger.exposures.get("req-fail").getState()).isEqualTo(ExposureState.SETTLED);
        // 幂等：重复结算返回首次结果
        SettlementResult again = settlement.settle("req-fail", 800L);
        assertThat(again.duplicate()).isTrue();
    }

    @Test
    void 超时到期_sweeper转敞口_宽限期过按estimate封顶关闭() {
        reserve("req-timeout", 1000L);
        time.advanceSeconds(61); // TTL 60s
        int handled = sweeper.sweepExpiredReservations();
        assertThat(handled).isEqualTo(1);
        assertThat(ledger.reservations.get("req-timeout").getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(ledger.exposures.get("req-timeout").getState()).isEqualTo(ExposureState.PENDING);
        assertThat(alerts.alerts).anyMatch(a -> a.type() == AlertType.HOLD_LEAK);

        time.advanceSeconds(301); // 宽限期 300s
        int closed = sweeper.sweepExpiredExposures();
        assertThat(closed).isEqualTo(1);
        assertThat(ledger.exposures.get("req-timeout").getState()).isEqualTo(ExposureState.CLOSED_WITH_ADJUSTMENT);
        // estimate 封顶 ADJUST 已入账
        assertThat(ledger.sumsByType(accountId).get(LedgerEntryType.ADJUST)).isEqualTo(1000L);
        assertThat(alerts.alerts).anyMatch(a -> a.type() == AlertType.RECONCILE_GAP);
        // 幂等：再次 sweep 不重复处理
        assertThat(sweeper.sweepExpiredExposures()).isEqualTo(0);
    }

    @Test
    void 重复回调幂等_仅入账一次() {
        reserve("req-cb", 1000L);
        settlement.settle("req-cb", 900L); // 正常结算（供应商回调 seq=0 已由网关路径处理）
        // 同一用量回调重复到达（Q3）：DUPLICATE 且不产生第二条流水
        var first = callbacks.ingest("mock", new CallbackService.CallbackPayload("req-cb", "sup-1", 900L, 0L, null));
        var second = callbacks.ingest("mock", new CallbackService.CallbackPayload("req-cb", "sup-1", 900L, 0L, null));
        assertThat(first.status()).isEqualTo(CallbackService.Status.ACCEPTED);
        assertThat(second.status()).isEqualTo(CallbackService.Status.DUPLICATE);
        long settleEntries = ledger.allEntries(accountId).stream()
                .filter(e -> e.getType() == LedgerEntryType.SETTLE).count();
        assertThat(settleEntries).isEqualTo(1);
    }

    @Test
    void 对账_供应商账单多于本地_自动产出差额并收敛() {
        reserve("req-recon", 1000L);
        settlement.settle("req-recon", 600L);
        // 供应商账单 800 > 本地 600 → 差额 200 自动补收
        ledger.exposures.clear();
        long before = ledger.sumsByType(accountId).get(LedgerEntryType.ADJUST);
        io.quotapilot.supplier.domain.SupplierChargeStorePort charges = chargesWith("req-recon", 800L);
        io.quotapilot.reconcile.domain.ReconciliationService svc =
                new io.quotapilot.reconcile.domain.ReconciliationService(charges, ledger, ledger, ledger,
                        settlement, new MemoryUsageEvents(), alerts, time);
        var report = svc.reconcile(accountId);
        assertThat(report.adjusted()).isEqualTo(1);
        assertThat(ledger.sumsByType(accountId).get(LedgerEntryType.ADJUST)).isEqualTo(before + 200L);
        // 重复对账不重复入账（P6）
        svc.reconcile(accountId);
        assertThat(ledger.sumsByType(accountId).get(LedgerEntryType.ADJUST)).isEqualTo(before + 200L);
    }

    private io.quotapilot.supplier.domain.SupplierChargeStorePort chargesWith(String requestId, long amountMinor) {
        return new io.quotapilot.supplier.domain.SupplierChargeStorePort() {
            @Override
            public void save(SupplierCharge charge) {
            }

            @Override
            public java.util.Optional<SupplierCharge> findByRequestId(String rid) {
                return Optional.empty();
            }

            @Override
            public java.util.List<SupplierCharge> listAll() {
                return java.util.List.of(new io.quotapilot.supplier.domain.SupplierChargeStorePort.SupplierCharge(
                        "sup-" + requestId, requestId, accountId, "gpt", amountMinor, amountMinor, 1L, time.now()));
            }

            @Override
            public java.util.List<SupplierCharge> findByAccount(String acct) {
                return accountId.equals(acct) ? listAll() : java.util.List.of();
            }

            @Override
            public long nextSeq(String rid) {
                return 2L;
            }
        };
    }

    /** 内存用量事件存储（幂等键 requestId+source+seq）。 */
    static class MemoryUsageEvents implements UsageEventPort {
        final AtomicReference<java.util.List<UsageEvent>> store = new AtomicReference<>(new java.util.ArrayList<>());

        @Override
        public synchronized boolean record(UsageEvent event) {
            java.util.List<UsageEvent> list = store.get();
            boolean dup = list.stream().anyMatch(e ->
                    e.requestId().equals(event.requestId()) && e.source() == event.source() && e.seq() == event.seq());
            if (dup) {
                return false;
            }
            list.add(event);
            return true;
        }

        @Override
        public java.util.Optional<UsageEvent> find(String requestId, UsageSource source, long seq) {
            return store.get().stream().filter(e -> e.requestId().equals(requestId) && e.source() == source
                    && e.seq() == seq).findFirst();
        }

        @Override
        public java.util.List<UsageEvent> findByRequestId(String requestId) {
            return store.get().stream().filter(e -> e.requestId().equals(requestId)).toList();
        }

        @Override
        public java.util.List<UsageEvent> pageByAccount(String accountId, int page, int size) {
            return java.util.List.of();
        }

        @Override
        public long countByAccount(String accountId) {
            return 0;
        }
    }
}
