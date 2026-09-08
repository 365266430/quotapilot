package io.quotapilot.infra.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.quotapilot.alert.domain.Alert;
import io.quotapilot.alert.domain.AlertStorePort;
import io.quotapilot.alert.domain.AlertType;

/** [M9] 告警存储适配器。 */
@Component
public class AlertStoreAdapter implements AlertStorePort {

    private final AlertJpaRepo repo;

    public AlertStoreAdapter(AlertJpaRepo repo) {
        this.repo = repo;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(Alert alert) {
        AlertEntity e = new AlertEntity();
        e.type = alert.type();
        e.severity = alert.severity();
        e.accountId = alert.accountId();
        e.requestId = alert.requestId();
        e.message = alert.message();
        e.createdAt = alert.at();
        repo.save(e);
    }

    @Override
    public List<Alert> recent(int limit) {
        return repo.findTop50ByOrderByCreatedAtDesc().stream().limit(limit)
                .map(e -> new Alert(e.type, e.severity, e.accountId, e.requestId, e.message, e.createdAt))
                .toList();
    }

    @Override
    public long countSince(AlertType type, String accountId, Instant since) {
        return repo.countByTypeAndAccountIdAndCreatedAtAfter(type, accountId, since);
    }
}
