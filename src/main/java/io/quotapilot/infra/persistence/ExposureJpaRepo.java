package io.quotapilot.infra.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExposureJpaRepo extends JpaRepository<ExposureRecordEntity, String> {

    Optional<ExposureRecordEntity> findByRequestId(String requestId);

    List<ExposureRecordEntity> findByStateAndGraceDeadlineBefore(io.quotapilot.ledger.domain.ExposureState state, Instant at);

    long countByAccountIdAndState(String accountId, io.quotapilot.ledger.domain.ExposureState state);

    List<ExposureRecordEntity> findByAccountIdAndState(String accountId, io.quotapilot.ledger.domain.ExposureState state);
}
