package io.quotapilot.gateway.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.quotapilot.alert.domain.Alert;
import io.quotapilot.alert.domain.AlertType;
import io.quotapilot.dashboard.domain.AccountBalanceView;
import io.quotapilot.dashboard.domain.DashboardService;
import io.quotapilot.ledger.domain.ExposureState;
import io.quotapilot.ledger.domain.ExposureRepositoryPort;
import io.quotapilot.ledger.domain.LedgerEntry;
import io.quotapilot.ledger.domain.LedgerEntryType;
import io.quotapilot.ledger.domain.LedgerQueryPort;
import io.quotapilot.ledger.domain.ReservationRepositoryPort;
import io.quotapilot.ledger.domain.ReservationStatus;
import io.quotapilot.ledger.domain.SettlementRecord;
import io.quotapilot.metering.domain.CallbackService;
import io.quotapilot.metering.domain.UsageEventPort;
import io.quotapilot.metering.domain.UsageSource;
import io.quotapilot.pricing.domain.PriceCatalog;
import io.quotapilot.pricing.domain.Sku;
import io.quotapilot.settlement.domain.SweeperService;
import io.quotapilot.supplier.domain.SupplierChargeStorePort;
import io.quotapilot.supplier.mock.MockSupplier;
import io.quotapilot.infra.persistence.OutboxJpaRepo;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [M4/M5/M8/M9/M10 端到端] 故障注入验收（H2 + 真实 Redis + MockSupplier）：
 * 成功结算、超预留告警、Q2 超时已计费（两种回调时序）、Q3 重复回调、Q8 释放后仍计费+宽限期封顶关闭、
 * 账本回放一致性、Outbox 同事务落库。
 */
@SpringBootTest
class FaultInjectionEndToEndIT {

    static final String MODEL = "e2e-" + UUID.randomUUID();

    @Autowired GatewayOrchestrator orchestrator;
    @Autowired MockSupplier mock;
    @Autowired PriceCatalog priceCatalog;
    @Autowired io.quotapilot.quota.domain.QuotaRulePort rulePort;
    @Autowired ExposureRepositoryPort exposureRepo;
    @Autowired LedgerQueryPort ledgerQuery;
    @Autowired ReservationRepositoryPort reservationRepo;
    @Autowired SupplierChargeStorePort supplierCharges;
    @Autowired SweeperService sweeper;
    @Autowired DashboardService dashboard;
    @Autowired CallbackService callbacks;
    @Autowired UsageEventPort usageEvents;
    @Autowired OutboxJpaRepo outboxRepo;
    @Autowired io.quotapilot.ledger.domain.AccountPort accountPort;

    static String user;
    String accountId;

    @BeforeAll
    static void setup(@Autowired PriceCatalog catalog, @Autowired io.quotapilot.quota.domain.QuotaRulePort rules) {
        user = "e2e-user-" + UUID.randomUUID();
        rules.saveRule(new io.quotapilot.quota.domain.QuotaRule("rule-e2e", io.quotapilot.ledger.domain.ScopeType.USER,
                user, null, 100_000L, "CNY", false), "test", "tr");
        catalog.publish(new Sku(MODEL, io.quotapilot.pricing.domain.UsageType.TOKEN), 1L, "CNY", Instant.now());
    }

    private String acct() {
        if (accountId == null) {
            accountId = accountPort.findByScope(io.quotapilot.ledger.domain.ScopeType.USER, user)
                    .orElseThrow().accountId();
        }
        return accountId;
    }

    private GatewayResult execute(String requestId, long units) {
        return orchestrator.execute(new GatewayRequest(requestId, user, null, null, MODEL, units, null, false, 0,
                null));
    }

    @Test
    void 成功路径_预留_调用_结算_明细_Outbox() {
        String id = "ok-" + UUID.randomUUID();
        GatewayResult r = execute(id, 1000L);
        assertThat(r.status()).isEqualTo(GatewayResult.SUCCEEDED);
        assertThat(r.chargedMinor()).isEqualTo(1000L);
        assertThat(r.refundMinor()).isEqualTo(0L);

        // M8 明细（网关解析用量，source=MOCK）
        assertThat(usageEvents.findByRequestId(id).stream()
                .anyMatch(e -> e.source() == UsageSource.MOCK && e.quantity() == 1000L)).isTrue();
        // Outbox 与业务同事务落库（P11/DB 成功 ⇒ 事件必达）
        assertThat(outboxRepo.count()).isPositive();
        // 供应商侧账本已计费
        assertThat(supplierCharges.findByRequestId(id)).isPresent();
        // 状态查询
        RequestQueryService.RequestView view = new RequestQueryService(reservationRepo, ledgerQuery, exposureRepo)
                .status(id);
        assertThat(view.reservationStatus()).isEqualTo("SETTLED");
        assertThat(view.settlement().chargedAmountMinor()).isEqualTo(1000L);
    }

    @Test
    void 超预留_按实际入账并触发P0告警() {
        String id = "drift-" + UUID.randomUUID();
        mock.injectDrift(id, 50); // actual = 1500 > estimate 1000
        GatewayResult r = execute(id, 1000L);
        assertThat(r.status()).isEqualTo(GatewayResult.SUCCEEDED);
        assertThat(r.chargedMinor()).isEqualTo(1500L);
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(dashboard.recentAlerts(50))
                        .anyMatch(a -> a.type() == AlertType.OVER_RESERVE && id.equals(a.requestId())));
    }

    @Test
    void Q2a_超时但供应商已完成_回调先到_结算获胜_释放静默失败() {
        String id = "t2a-" + UUID.randomUUID();
        mock.injectFault(id, MockSupplier.Fault.TIMEOUT); // 供应商完成计费并立即回调
        GatewayResult r = execute(id, 800L);
        // 回调先于超时处理到达 → 结算竞态获胜，请求最终 SETTLED
        assertThat(r.status()).isEqualTo(GatewayResult.SUCCEEDED);
        assertThat(r.chargedMinor()).isEqualTo(800L);
        assertThat(reservationRepo.findByRequestId(id).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.SETTLED);
        assertThat(exposureRepo.findByRequestId(id)).isEmpty(); // 无需敞口：费用已收敛
    }

    @Test
    void Q2b_超时已计费_释放先到_迟到回调在宽限期内正常结算() {
        String id = "t2b-" + UUID.randomUUID();
        mock.injectFault(id, MockSupplier.Fault.TIMEOUT);
        mock.injectLateCallback(id, 500); // 回调迟到 500ms（宽限期 2s 内）
        GatewayResult r = execute(id, 900L);
        assertThat(r.status()).isEqualTo(GatewayResult.RELEASED);
        assertThat(r.exposureId()).isNotNull();
        assertThat(exposureRepo.findByRequestId(id).orElseThrow().getState()).isEqualTo(ExposureState.PENDING);
        // 用户此时预算已退回（held 释放），敞口待对账 —— 诚实计量：不假装无费用

        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            var exp = exposureRepo.findByRequestId(id).orElseThrow();
            assertThat(exp.getState()).isEqualTo(ExposureState.SETTLED); // 迟到回调 → 正常结算
            assertThat(exp.getResolvedAmountMinor()).isEqualTo(900L);
        });
        assertThat(ledgerQuery.netChargedByRequest(id)).isEqualTo(900L);
    }

    @Test
    void Q3_同一用量回调重复到达_安全丢弃不重复入账() {
        String id = "dup-" + UUID.randomUUID();
        execute(id, 500L); // 正常结算 500
        long before = ledgerQuery.netChargedByRequest(id);
        var first = callbacks.ingest("mock", new CallbackService.CallbackPayload(id, "sup-dup", 500L, 1L, null));
        var second = callbacks.ingest("mock", new CallbackService.CallbackPayload(id, "sup-dup", 500L, 1L, null));
        assertThat(first.status()).isEqualTo(CallbackService.Status.ACCEPTED); // 新 seq → ADJUST 幂等键防重
        assertThat(second.status()).isEqualTo(CallbackService.Status.DUPLICATE);
        assertThat(ledgerQuery.netChargedByRequest(id)).isEqualTo(before); // 金额未重复入账
        // usage_events 幂等键 (requestId, source, seq)：不产生重复行
        assertThat(usageEvents.findByRequestId(id).stream()
                .filter(e -> e.source() == UsageSource.SUPPLIER_CALLBACK && e.seq() == 1L).count()).isEqualTo(1);
    }

    @Test
    void Q8_释放后敞口_宽限期过按estimate封顶关闭并告警() {
        String id = "q8-" + UUID.randomUUID();
        mock.injectFault(id, MockSupplier.Fault.FAIL_AFTER_DISPATCH); // 已发出、结果未知、无回调
        GatewayResult r = execute(id, 700L);
        assertThat(r.status()).isEqualTo(GatewayResult.RELEASED);
        assertThat(r.exposureId()).isNotNull();
        assertThat(reservationRepo.findByRequestId(id).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RELEASED);

        // 宽限期 2s 后 sweep → CLOSED_WITH_ADJUSTMENT（estimate 封顶）
        Awaitility.await().pollInterval(300, TimeUnit.MILLISECONDS).atMost(8, TimeUnit.SECONDS)
                .until(() -> exposureRepo.findByRequestId(id).orElseThrow().isPastGrace(Instant.now()));
        int closed = sweeper.sweepExpiredExposures();
        assertThat(closed).isGreaterThanOrEqualTo(1);
        var exp = exposureRepo.findByRequestId(id).orElseThrow();
        assertThat(exp.getState()).isEqualTo(ExposureState.CLOSED_WITH_ADJUSTMENT);
        assertThat(exp.getResolvedAmountMinor()).isEqualTo(700L);
        // ADJUST 封顶入账（迟到结算未再发生）
        assertThat(ledgerQuery.allEntries(acct()).stream()
                .anyMatch(e -> e.getType() == LedgerEntryType.ADJUST && e.getAmountMinor() == 700L
                        && id.equals(e.getRequestId()))).isTrue();
        assertThat(dashboard.recentAlerts(50)).anyMatch(a -> a.type() == AlertType.RECONCILE_GAP);
    }

    @Test
    void 面板与账本回放一致_P3权威口径() {
        String id = "replay-" + UUID.randomUUID();
        execute(id, 300L);
        AccountBalanceView v = dashboard.balance(acct());
        var sums = ledgerQuery.sumsByType(acct());
        long settledSum = sums.get(LedgerEntryType.SETTLE) + sums.get(LedgerEntryType.ADJUST);
        long heldSum = Math.max(0, sums.get(LedgerEntryType.HOLD) - sums.get(LedgerEntryType.SETTLE)
                - sums.get(LedgerEntryType.RELEASE));
        assertThat(v.settledMinor()).isEqualTo(settledSum);
        assertThat(v.heldMinor()).isEqualTo(reservationRepo.sumActiveHolds(acct()));
        assertThat(v.availableMinor()).isEqualTo(100_000L - settledSum - v.heldMinor());
        // 全量流水可回放：条数一致、每条金额非零
        List<LedgerEntry> entries = ledgerQuery.allEntries(acct());
        assertThat(entries).allMatch(e -> e.getAmountMinor() != 0);
        assertThat(ledgerQuery.countEntries(acct())).isEqualTo(entries.size());
    }

    @Test
    void Redis与DB对账_收敛无漂移() {
        String id = "sync-" + UUID.randomUUID();
        execute(id, 400L);
        int corrected = sweeper.reconcileRedis(accountPort, ledgerQuery);
        // 收敛后再次对账应零纠正
        int second = sweeper.reconcileRedis(accountPort, ledgerQuery);
        assertThat(second).isEqualTo(0);
        AccountBalanceView v = dashboard.balance(acct());
        assertThat(v.availableMinor()).isGreaterThanOrEqualTo(0L);
    }
}
