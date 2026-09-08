package io.quotapilot.infra.persistence;

import java.time.Instant;

import io.quotapilot.ledger.domain.ScopeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** [M1] 额度规则表。 */
@Entity
@Table(name = "quota_rules")
public class QuotaRuleEntity {
    @Id
    public String ruleId;
    @Enumerated(EnumType.STRING)
    public ScopeType scopeType;
    public String scopeId;
    public String model;
    public long quotaLimitMinor;
    public String currency;
    public boolean sharedAmongMembers;
    public Instant createdAt;
}
