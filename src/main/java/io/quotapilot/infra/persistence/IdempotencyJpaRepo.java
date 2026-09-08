package io.quotapilot.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyJpaRepo extends JpaRepository<IdempotencyEntity, String> {
}
