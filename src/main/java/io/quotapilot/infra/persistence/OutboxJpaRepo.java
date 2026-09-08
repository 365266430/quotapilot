package io.quotapilot.infra.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxJpaRepo extends JpaRepository<OutboxEntity, Long> {
    List<OutboxEntity> findTop100ByStatusOrderByCreatedAtAsc(String status);

    List<OutboxEntity> findTop50ByStatusOrderByCreatedAtAsc(String status);
}
