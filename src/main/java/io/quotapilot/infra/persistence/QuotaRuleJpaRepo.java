package io.quotapilot.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuotaRuleJpaRepo extends JpaRepository<QuotaRuleEntity, String> {
}
