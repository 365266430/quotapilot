package io.quotapilot.infra.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import io.quotapilot.metering.domain.UsageEvent;
import io.quotapilot.metering.domain.UsageEventPort;

/**
 * [M8/P6/Q3] 用量事件适配器：唯一约束 (requestId, source, seq) 幂等。
 * 事务语义：预检查 + 独立仓储事务（调用方无外层事务；禁止嵌套 REQUIRES_NEW 以免并发耗尽连接池）。
 */
@Component
public class UsageEventAdapter implements UsageEventPort {

    private final UsageEventJpaRepo repo;

    public UsageEventAdapter(UsageEventJpaRepo repo) {
        this.repo = repo;
    }

    @Override
    public boolean record(UsageEvent event) {
        if (repo.existsByRequestIdAndSourceAndSeq(event.requestId(), event.source(), event.seq())) {
            return false; // 重复事件已安全丢弃（P6/Q3）
        }
        UsageEventEntity e = new UsageEventEntity();
        e.usageEventId = event.usageEventId();
        e.requestId = event.requestId();
        e.supplierRequestId = event.supplierRequestId();
        e.accountId = event.accountId();
        e.sku = event.sku();
        e.usageType = event.usageType();
        e.quantity = event.quantity();
        e.occurredAt = event.occurredAt();
        e.source = event.source();
        e.seq = event.seq();
        e.traceId = event.traceId();
        try {
            repo.saveAndFlush(e);
            return true;
        } catch (DataIntegrityViolationException race) {
            // 预检查与插入之间的并发重复：视为重复事件（无外层事务，约束冲突已随仓储事务回滚）
            return false;
        }
    }

    @Override
    public Optional<UsageEvent> find(String requestId, io.quotapilot.metering.domain.UsageSource source, long seq) {
        return repo.findByRequestId(requestId).stream()
                .filter(e -> e.source == source && e.seq == seq)
                .findFirst().map(UsageEventAdapter::toDomain);
    }

    @Override
    public List<UsageEvent> findByRequestId(String requestId) {
        return repo.findByRequestId(requestId).stream().map(UsageEventAdapter::toDomain).toList();
    }

    @Override
    public List<UsageEvent> pageByAccount(String accountId, int page, int size) {
        return repo.findByAccountIdOrderByOccurredAtDesc(accountId,
                        PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size))))
                .getContent().stream().map(UsageEventAdapter::toDomain).toList();
    }

    @Override
    public long countByAccount(String accountId) {
        return repo.countByAccountId(accountId);
    }

    static UsageEvent toDomain(UsageEventEntity e) {
        return new UsageEvent(e.usageEventId, e.requestId, e.supplierRequestId, e.accountId, e.sku, e.usageType,
                e.quantity, e.occurredAt, e.source, e.seq, e.traceId);
    }
}
