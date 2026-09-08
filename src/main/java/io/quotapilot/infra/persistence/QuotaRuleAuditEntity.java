package io.quotapilot.infra.persistence;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** [M1] 额度配置变更审计日志。 */
@Entity
@Table(name = "quota_rule_audit")
public class QuotaRuleAuditEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String ruleId;
    public String operator;
    public String action;
    public String detail;
    public Instant createdAt;
}
