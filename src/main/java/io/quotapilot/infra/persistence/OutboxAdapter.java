package io.quotapilot.infra.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.quotapilot.common.TimeService;
import io.quotapilot.infra.outbox.domain.OutboxMessage;
import io.quotapilot.infra.outbox.domain.OutboxPort;

/** [M11] Outbox 写入适配器：随业务事务同事务持久化（调用方事务内）。 */
@Component
public class OutboxAdapter implements OutboxPort {

    private final OutboxJpaRepo repo;
    private final TimeService time;

    public OutboxAdapter(OutboxJpaRepo repo, TimeService time) {
        this.repo = repo;
        this.time = time;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String aggregateType, String aggregateId, String eventType, String payloadJson) {
        OutboxEntity e = new OutboxEntity();
        e.aggregateType = aggregateType;
        e.aggregateId = aggregateId;
        e.eventType = eventType;
        e.payloadJson = payloadJson;
        e.status = "PENDING";
        e.attempts = 0;
        e.createdAt = time.now();
        repo.save(e);
    }

    public static OutboxMessage toMessage(OutboxEntity e) {
        return new OutboxMessage(String.valueOf(e.id), e.aggregateType, e.aggregateId, e.eventType, e.payloadJson);
    }
}
