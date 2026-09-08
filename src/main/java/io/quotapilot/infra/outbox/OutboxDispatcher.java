package io.quotapilot.infra.outbox;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.quotapilot.infra.outbox.domain.OutboxMessage;
import io.quotapilot.infra.persistence.OutboxAdapter;
import io.quotapilot.infra.persistence.OutboxEntity;
import io.quotapilot.infra.persistence.OutboxJpaRepo;

/**
 * [M11] Outbox 投递器 + 进程内事件通道。
 * 保证「DB 成功 ⇒ 事件必达」：轮询 PENDING → 投递到已注册消费者 → 标记 SENT；失败重试。
 * 通道语义为 at-least-once，消费方必须幂等（P6）。
 */
@Component
@ConditionalOnProperty(name = "quotapilot.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxJpaRepo repo;
    private final List<Consumer<OutboxMessage>> listeners = new CopyOnWriteArrayList<>();

    public OutboxDispatcher(OutboxJpaRepo repo) {
        this.repo = repo;
    }

    public void addListener(Consumer<OutboxMessage> listener) {
        listeners.add(listener);
    }

    @Scheduled(fixedDelayString = "${quotapilot.outbox-poll-ms:1000}")
    public void dispatchPending() {
        List<OutboxEntity> batch = repo.findTop100ByStatusOrderByCreatedAtAsc("PENDING");
        for (OutboxEntity e : batch) {
            OutboxMessage msg = OutboxAdapter.toMessage(e);
            try {
                deliver(msg);
                e.status = "SENT";
                e.dispatchedAt = java.time.Instant.now();
                repo.save(e);
            } catch (RuntimeException err) {
                e.attempts = e.attempts + 1;
                if (e.attempts >= 20) {
                    log.error("Outbox 事件投递失败超过上限 id={} type={}", e.id, e.eventType, err);
                    e.status = "FAILED";
                }
                repo.save(e);
            }
        }
    }

    /** 单事件投递（独立事务读取，避免长事务）。 */
    @Transactional(readOnly = true)
    public void deliverAllPendingOnce() {
        dispatchPending();
    }

    /**
     * [M11/P7] sweeper 重驱动：FAILED 滞留消息（投递连续失败 20 次）重置为 PENDING 重新投递。
     * 仅重驱创建超过 10 分钟的消息，避免与正常重试竞争；DB 成功 ⇒ 事件必达的最终兜底。
     */
    @Transactional
    public int requeueFailed() {
        java.util.List<OutboxEntity> failed = repo.findTop50ByStatusOrderByCreatedAtAsc("FAILED");
        java.time.Instant threshold = java.time.Instant.now().minusSeconds(600);
        int requeued = 0;
        for (OutboxEntity e : failed) {
            if (e.createdAt != null && e.createdAt.isBefore(threshold)) {
                e.status = "PENDING";
                e.attempts = 0;
                repo.save(e);
                requeued++;
            }
        }
        if (requeued > 0) {
            log.warn("Outbox 重驱动: {} 条 FAILED 消息已重置为 PENDING", requeued);
        }
        return requeued;
    }

    private void deliver(OutboxMessage msg) {
        for (Consumer<OutboxMessage> listener : listeners) {
            try {
                listener.accept(msg);
            } catch (RuntimeException e) {
                log.warn("事件消费者异常 type={} id={}: {}", msg.eventType(), msg.id(), e.toString());
            }
        }
    }
}
