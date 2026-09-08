package io.quotapilot.dashboard.domain;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.quotapilot.alert.domain.Alert;
import io.quotapilot.alert.domain.AlertType;
import io.quotapilot.common.TimeService;
import io.quotapilot.ledger.domain.LedgerEntryType;
import io.quotapilot.ledger.domain.LedgerQueryPort;
import io.quotapilot.ledger.domain.ReservationRepositoryPort;
import io.quotapilot.ledger.domain.ScopeType;
import io.quotapilot.support.FakeLedger;
import io.quotapilot.support.Support.FakeAccounts;
import io.quotapilot.support.Support.FakeAlertStore;
import io.quotapilot.support.Support.MutableTime;
import io.quotapilot.support.Support.RecordingAlerts;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [M9 验收] 告警可被抑制与恢复：冷却窗口内同(type,accountId)不重复告警；窗口过后可再次告警。
 */
class M9AlertSuppressionTest {

    @Test
    void 冷却窗口内抑制_窗口过后恢复告警() {
        MutableTime time = new MutableTime();
        FakeAccounts accounts = new FakeAccounts();
        FakeLedger ledger = new FakeLedger(time);
        RecordingAlerts emitter = new RecordingAlerts();
        FakeAlertStore store = new FakeAlertStore();
        var exposureRepo = ledger.exposurePort();
        // 组合出口模拟生产 AlertHub 语义：先落库（抑制判断依据）再发出
        io.quotapilot.alert.domain.AlertEmitterPort hubLike = alert -> {
            store.save(alert);
            emitter.emit(alert);
        };

        String accountId = accounts.getOrCreate(ScopeType.USER, "u-sup", 100L, "CNY").accountId();
        // 构造「可用额度 5%」场景：HOLD 95 / limit 100
        ledger.entries.add(new io.quotapilot.ledger.domain.LedgerEntry("e1", accountId, "r1",
                io.quotapilot.ledger.domain.LedgerEntryType.HOLD, 95L, io.quotapilot.ledger.domain.AmountKind.ESTIMATE,
                null, "RESERVE", null, null, "tr", time.now()));
        ledger.reservations.put("r1", new io.quotapilot.ledger.domain.Reservation("h1", "r1", accountId, 95L,
                "pv", "tr", time.now(), time.now().plusSeconds(60)));

        DashboardService service = new DashboardService(accounts, (LedgerQueryPort) ledger,
                (ReservationRepositoryPort) ledger, exposureRepo, hubLike, store, time, 20L, 2L, 300L);

        // 第一次评估：可用 5% < 20% → 告警
        service.balance(accountId);
        assertThat(emitter.alerts).hasSize(1);
        // 同窗口内再评估 → 抑制（不重复）
        service.balance(accountId);
        service.balance(accountId);
        assertThat(emitter.alerts).hasSize(1);
        // 冷却窗口（300s）过后 → 恢复告警
        time.advanceSeconds(301);
        service.balance(accountId);
        assertThat(emitter.alerts).hasSize(2);
        assertThat(emitter.alerts.get(1).type()).isEqualTo(AlertType.AVAILABLE_LOW);
        // 底层口径未受影响
        assertThat(((LedgerQueryPort) ledger).sumsByType(accountId).get(LedgerEntryType.HOLD)).isEqualTo(95L);
    }
}
