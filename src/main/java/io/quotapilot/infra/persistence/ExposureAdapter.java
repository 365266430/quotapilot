package io.quotapilot.infra.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.quotapilot.ledger.domain.ExposureRecord;
import io.quotapilot.ledger.domain.ExposureRepositoryPort;
import io.quotapilot.ledger.domain.ExposureState;

/** [M0] 敞口仓储适配器。 */
@Component
public class ExposureAdapter implements ExposureRepositoryPort {

    private final ExposureJpaRepo repo;

    public ExposureAdapter(ExposureJpaRepo repo) {
        this.repo = repo;
    }

    @Override
    @Transactional
    public void save(ExposureRecord exposure) {
        repo.saveAndFlush(toEntity(exposure));
    }

    @Override
    @Transactional
    public void update(ExposureRecord exposure) {
        ExposureRecordEntity e = repo.findById(exposure.getExposureId()).orElseThrow();
        e.state = exposure.getState();
        e.resolvedAt = exposure.getResolvedAt();
        e.resolvedAmountMinor = exposure.getResolvedAmountMinor();
        e.evidenceRef = exposure.getEvidenceRef();
        repo.save(e);
    }

    @Override
    public Optional<ExposureRecord> findByRequestId(String requestId) {
        return repo.findByRequestId(requestId).map(ExposureAdapter::toDomain);
    }

    @Override
    public Optional<ExposureRecord> findById(String exposureId) {
        return repo.findById(exposureId).map(ExposureAdapter::toDomain);
    }

    @Override
    public List<ExposureRecord> findPendingPastGrace(Instant now) {
        return repo.findByStateAndGraceDeadlineBefore(ExposureState.PENDING, now).stream()
                .map(ExposureAdapter::toDomain).toList();
    }

    @Override
    public long countOpen(String accountId) {
        return repo.countByAccountIdAndState(accountId, ExposureState.PENDING);
    }

    @Override
    public List<ExposureRecord> findOpenByAccount(String accountId) {
        return repo.findByAccountIdAndState(accountId, ExposureState.PENDING).stream()
                .map(ExposureAdapter::toDomain).toList();
    }

    static ExposureRecord toDomain(ExposureRecordEntity e) {
        return ExposureRecord.restore(e.exposureId, e.requestId, e.holdId, e.accountId, e.estimatedAmountMinor,
                e.reason, e.createdAt, e.graceDeadline, e.state, e.resolvedAt, e.resolvedAmountMinor, e.evidenceRef);
    }

    static ExposureRecordEntity toEntity(ExposureRecord d) {
        ExposureRecordEntity e = new ExposureRecordEntity();
        e.exposureId = d.getExposureId();
        e.requestId = d.getRequestId();
        e.holdId = d.getHoldId();
        e.accountId = d.getAccountId();
        e.estimatedAmountMinor = d.getEstimatedAmountMinor();
        e.reason = d.getReason();
        e.state = d.getState();
        e.createdAt = d.getCreatedAt();
        e.graceDeadline = d.getGraceDeadline();
        e.resolvedAt = d.getResolvedAt();
        e.resolvedAmountMinor = d.getResolvedAmountMinor();
        e.evidenceRef = d.getEvidenceRef();
        return e;
    }
}
