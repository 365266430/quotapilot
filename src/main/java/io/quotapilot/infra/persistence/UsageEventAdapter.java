package io.quotapilot.infra.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.quotapilot.metering.domain.UsageEvent;
import io.quotapilot.metering.domain.UsageEventPort;

/** [M8/P6/Q3] 用量事件适配器：唯一约束幂等，重复投递返回 false 不抛错。 */
@Component
public class UsageEventAdapter implements UsageEventPort {

    private final UsageEventJpaRepo repo;

    public UsageEventAdapter(UsageEventJpaRepo repo) {
        this.repo = repo;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean record(UsageEvent event) {
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
        } catch (DataIntegrityViolationException dup) {
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
        return repo.findByAccountIdOrderByOccurredAtDesc(accountId, PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size))))
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
