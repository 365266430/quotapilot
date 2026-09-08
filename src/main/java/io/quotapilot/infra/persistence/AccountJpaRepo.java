package io.quotapilot.infra.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountJpaRepo extends JpaRepository<AccountEntity, String> {
    Optional<AccountEntity> findByScopeTypeAndScopeId(io.quotapilot.ledger.domain.ScopeType scopeType, String scopeId);
}
