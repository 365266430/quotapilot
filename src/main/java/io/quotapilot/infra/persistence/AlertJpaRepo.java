package io.quotapilot.infra.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertJpaRepo extends JpaRepository<AlertEntity, Long> {

    List<AlertEntity> findTop50ByOrderByCreatedAtDesc();

    long countByTypeAndAccountIdAndCreatedAtAfter(io.quotapilot.alert.domain.AlertType type, String accountId, Instant since);
}
