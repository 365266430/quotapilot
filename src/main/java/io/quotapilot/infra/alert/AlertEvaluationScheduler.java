package io.quotapilot.infra.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.quotapilot.dashboard.domain.DashboardService;
import io.quotapilot.infra.redis.RedisLock;
import io.quotapilot.ledger.domain.AccountPort;

/**
 * [M9/V1.1] 实时告警评估调度：周期性扫描全部账户触发内置告警规则
 * （可用额度低于 X%、敞口积压等），与面板查询触发的评估互补；告警带抑制窗口。
 */
@Component
@ConditionalOnProperty(name = "quotapilot.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class AlertEvaluationScheduler {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluationScheduler.class);
    private static final String LOCK_KEY = "quotapilot:lock:alert-eval";

    private final AccountPort accountPort;
    private final DashboardService dashboardService;
    private final RedisLock lock;

    public AlertEvaluationScheduler(AccountPort accountPort, DashboardService dashboardService, RedisLock lock) {
        this.accountPort = accountPort;
        this.dashboardService = dashboardService;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${quotapilot.alert-eval-interval-ms:30000}")
    public void evaluate() {
        if (!lock.tryLock(LOCK_KEY, java.time.Duration.ofMinutes(5))) {
            return;
        }
        try {
            int checked = 0;
            for (String accountId : accountPort.allAccountIds()) {
                try {
                    dashboardService.balance(accountId); // 内部触发 AVAILABLE_LOW / EXPOSURE_BACKLOG 评估
                    checked++;
                } catch (RuntimeException ignored) {
                    // 单账户失败不影响整体
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("告警评估完成: {} 账户", checked);
            }
        } catch (RuntimeException e) {
            log.warn("告警评估执行异常: {}", e.toString());
        } finally {
            lock.unlock(LOCK_KEY);
        }
    }
}
