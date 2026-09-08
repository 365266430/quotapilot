package io.quotapilot.infra.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** [M1] 团队成员表（团队限额分摊用）。 */
@Entity
@Table(name = "team_members", uniqueConstraints = @UniqueConstraint(name = "uk_team_user", columnNames = {"team_id", "user_id"}))
public class TeamMemberEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String teamId;
    public String userId;

    public TeamMemberEntity() {}

    public TeamMemberEntity(String teamId, String userId) {
        this.teamId = teamId;
        this.userId = userId;
    }
}
