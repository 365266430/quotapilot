package io.quotapilot.dashboard.domain;

import java.time.Instant;
import java.util.List;

import io.quotapilot.alert.domain.Alert;
import io.quotapilot.alert.domain.AlertEmitterPort;
import io.quotapilot.alert.domain.AlertStorePort;
import io.quotapilot.alert.domain.AlertType;
import io.quotapilot.common.TimeService;
import io.quotapilot.ledger.domain.AccountPort;
import io.quotapilot.ledger.domain.ExposureRecord;
import io.quotapilot.ledger.domain.ExposureRepositoryPort;
import io.quotapilot.ledger.domain.LedgerEntryType;
import io.quotapilot.ledger.domain.LedgerQueryPort;
import io.quotapilot.ledger.domain.ReservationRepositoryPort;

/**
 * [M9] 实时预算面板（最小实现）：每账户 limit/settled/held/exposure/available 四值视图 + 内置告警。
 * 权威口径：DB 账本重算（P3/§5.2 第 4 点），Redis 仅作热路径。
 */
public class DashboardService {

    private final AccountPort accountPort;
    private final LedgerQueryPort ledgerQuery;
    private final ReservationRepositoryPort reservationRepo;
    private final ExposureRepositoryPort exposureRepo;
    private final AlertEmitterPort alerts;
    private final AlertStorePort alertStore;
    private final TimeService time;
    private final long lowAvailableWarnPct;     // 可用额度低于 X% 告警
    private final long exposureBacklogThreshold; // 敞口积压阈值
    private final long alertCooldownSeconds;     // 告警抑制窗口

    public DashboardService(AccountPort accountPort, LedgerQueryPort ledgerQuery,
                            ReservationRepositoryPort reservationRepo, ExposureRepositoryPort exposureRepo,
                            AlertEmitterPort alerts, AlertStorePort alertStore, TimeService time,
                            long lowAvailableWarnPct, long exposureBacklogThreshold, long alertCooldownSeconds) {
        this.accountPort = accountPort;
        this.ledgerQuery = ledgerQuery;
        this.reservationRepo = reservationRepo;
        this.exposureRepo = exposureRepo;
        this.alerts = alerts;
        this.alertStore = alertStore;
        this.time = time;
        this.lowAvailableWarnPct = lowAvailableWarnPct;
        this.exposureBacklogThreshold = exposureBacklogThreshold;
        this.alertCooldownSeconds = alertCooldownSeconds;
    }

    public AccountBalanceView balance(String accountId) {
        AccountPort.AccountSnapshot account = accountPort.find(accountId)
                .orElseThrow(() -> new io.quotapilot.common.DomainExceptions.NotFound("账户不存在: " + accountId));
        long settled = ledgerQuery.sumsByType(accountId)
                .getOrDefault(LedgerEntryType.SETTLE, 0L)
                + ledgerQuery.sumsByType(accountId).getOrDefault(LedgerEntryType.ADJUST, 0L);
        long held = reservationRepo.sumActiveHolds(accountId);
        List<ExposureRecord> open = exposureRepo.findOpenByAccount(accountId);
        long exposureOpen = open.stream().mapToLong(ExposureRecord::getEstimatedAmountMinor).sum();
        long available = account.quotaLimitMinor() - settled - held;
        checkAvailableLow(account, available);
        checkExposureBacklog(account, open.size());
        return new AccountBalanceView(accountId, account.scopeType().name(), account.scopeId(),
                account.quotaLimitMinor(), settled, held, exposureOpen, open.size(), available, account.currency());
    }

    public List<Alert> recentAlerts(int limit) {
        return alertStore.recent(limit);
    }

    private void checkAvailableLow(AccountPort.AccountSnapshot account, long available) {
        long limit = account.quotaLimitMinor();
        if (available < 0) {
            emitSuppressed(AlertType.AVAILABLE_LOW, "P0", account.accountId(), null,
                    "可用额度为负（预留与对账窗口内的超支敞口）: available=" + available);
            return;
        }
        if (limit > 0 && available * 100 < limit * lowAvailableWarnPct) {
            emitSuppressed(AlertType.AVAILABLE_LOW, "WARN", account.accountId(), null,
                    "可用额度低于阈值: available=" + available + " / limit=" + limit);
        }
    }

    private void checkExposureBacklog(AccountPort.AccountSnapshot account, int openCount) {
        if (openCount > exposureBacklogThreshold) {
            emitSuppressed(AlertType.EXPOSURE_BACKLOG, "WARN", account.accountId(), null,
                    "未决敞口积压: " + openCount + " > " + exposureBacklogThreshold);
        }
    }

    /** 告警抑制：同(type,accountId) 冷却窗口内不重复发送，但恢复后可再次告警。 */
    private void emitSuppressed(AlertType type, String severity, String accountId, String requestId, String message) {
        Instant since = time.now().minusSeconds(alertCooldownSeconds);
        if (alertStore.countSince(type, accountId, since) > 0) {
            return;
        }
        alerts.emit(Alert.of(type, severity, accountId, requestId, message, time.now()));
    }
}
