package io.quotapilot.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RateLimitRuleJpaRepo extends JpaRepository<RateLimitRuleEntity, String> {
}
