package io.quotapilot.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuotaRuleAuditJpaRepo extends JpaRepository<QuotaRuleAuditEntity, Long> {
}
