package io.quotapilot.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TeamMemberJpaRepo extends JpaRepository<TeamMemberEntity, Long> {
    long countByTeamId(String teamId);

    List<TeamMemberEntity> findByTeamId(String teamId);
}
