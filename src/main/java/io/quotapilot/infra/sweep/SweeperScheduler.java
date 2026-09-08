package io.quotapilot.infra.sweep;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.quotapilot.infra.redis.RedisLock;
import io.quotapilot.ledger.domain.AccountPort;
import io.quotapilot.ledger.domain.LedgerQueryPort;
import io.quotapilot.settlement.domain.SweeperService;

/**
 * [M11/P7] 补偿调度器：周期驱动 sweeper（过期预留→敞口、超宽限期敞口→封顶关闭、Redis 对账重建）。
 * 以分布式锁互斥（低频临界区）；可经 quotapilot.scheduler.enabled=false 关闭（测试手工触发）。
 */
@Component
@ConditionalOnProperty(name = "quotapilot.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SweeperScheduler {

    private static final Logger log = LoggerFactory.getLogger(SweeperScheduler.class);
    private static final String LOCK_KEY = "quotapilot:lock:sweeper";

    private final SweeperService sweeperService;
    private final AccountPort accountPort;
    private final LedgerQueryPort ledgerQuery;
    private final RedisLock lock;
    private final io.quotapilot.infra.outbox.OutboxDispatcher outboxDispatcher;

    public SweeperScheduler(SweeperService sweeperService, AccountPort accountPort, LedgerQueryPort ledgerQuery,
                            RedisLock lock, io.quotapilot.infra.outbox.OutboxDispatcher outboxDispatcher) {
        this.sweeperService = sweeperService;
        this.accountPort = accountPort;
        this.ledgerQuery = ledgerQuery;
        this.lock = lock;
        this.outboxDispatcher = outboxDispatcher;
    }

    @Scheduled(fixedDelayString = "${quotapilot.sweep-interval-ms:10000}")
    public void sweep() {
        if (!lock.tryLock(LOCK_KEY, java.time.Duration.ofSeconds(30))) {
            return;
        }
        try {
            int holds = sweeperService.sweepExpiredReservations();
            int exposures = sweeperService.sweepExpiredExposures();
            int rebuilt = sweeperService.reconcileRedis(accountPort, ledgerQuery);
            outboxDispatcher.requeueFailed(); // P7：Outbox 滞留消息重驱动（DB 成功 ⇒ 事件必达）
            if (holds + exposures + rebuilt > 0) {
                log.info("sweeper: 过期预留={} 敞口收敛={} Redis重建={}", holds, exposures, rebuilt);
            }
        } catch (RuntimeException e) {
            log.warn("sweeper 执行异常: {}", e.toString());
        } finally {
            lock.unlock(LOCK_KEY);
        }
    }
}
