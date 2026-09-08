package io.quotapilot.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamMemberJpaRepo extends JpaRepository<TeamMemberEntity, Long> {
    long countByTeamId(String teamId);
}
