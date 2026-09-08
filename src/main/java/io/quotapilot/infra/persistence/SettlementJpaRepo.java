package io.quotapilot.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementJpaRepo extends JpaRepository<SettlementRecordEntity, String> {
}
