package io.quotapilot.infra.sweep;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.quotapilot.infra.redis.RedisLock;
import io.quotapilot.reconcile.domain.ReconciliationService;

/**
 * [M5/V1.1] 自动对账调度：按可配周期把供应商侧账单与本地账目核对并产出差额修正（幂等）。
 * 分布式锁互斥（低频临界区）；亦保留 POST /v1/admin/reconcile 手工触发。
 */
@Component
@ConditionalOnProperty(name = "quotapilot.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class ReconcileScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconcileScheduler.class);
    private static final String LOCK_KEY = "quotapilot:lock:reconcile";

    private final ReconciliationService reconciliationService;
    private final RedisLock lock;

    public ReconcileScheduler(ReconciliationService reconciliationService, RedisLock lock) {
        this.reconciliationService = reconciliationService;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${quotapilot.reconcile-interval-ms:3600000}")
    public void reconcile() {
        if (!lock.tryLock(LOCK_KEY, java.time.Duration.ofMinutes(30))) {
            return;
        }
        try {
            var report = reconciliationService.reconcile(null);
            if (report.scanned() > 0) {
                log.info("自动对账: scanned={} adjusted={} gaps={}", report.scanned(), report.adjusted(),
                        report.gaps().size());
            }
        } catch (RuntimeException e) {
            log.warn("自动对账执行异常: {}", e.toString());
        } finally {
            lock.unlock(LOCK_KEY);
        }
    }
}
