package io.quotapilot.infra.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quotapilot.common.TimeService;
import io.quotapilot.quota.domain.QuotaRule;
import io.quotapilot.quota.domain.QuotaRulePort;

/** [M1] 额度规则适配器：规则变更同事务写审计日志。 */
@Component
public class QuotaRuleAdapter implements QuotaRulePort {

    private final QuotaRuleJpaRepo rules;
    private final QuotaRuleAuditJpaRepo audits;
    private final TeamMemberJpaRepo teamMembers;
    private final TimeService time;
    private final ObjectMapper json;

    public QuotaRuleAdapter(QuotaRuleJpaRepo rules, QuotaRuleAuditJpaRepo audits, TeamMemberJpaRepo teamMembers,
                            TimeService time, ObjectMapper json) {
        this.rules = rules;
        this.audits = audits;
        this.teamMembers = teamMembers;
        this.time = time;
        this.json = json;
    }

    @Override
    @Transactional
    public QuotaRule saveRule(QuotaRule rule, String operator, String traceId) {
        QuotaRuleEntity e = rules.findById(rule.ruleId()).orElse(null);
        String action = e == null ? "CREATE" : "UPDATE";
        if (e == null) {
            e = new QuotaRuleEntity();
            e.ruleId = rule.ruleId();
            e.createdAt = time.now();
        }
        e.scopeType = rule.scopeType();
        e.scopeId = rule.scopeId();
        e.model = rule.model();
        e.quotaLimitMinor = rule.quotaLimitMinor();
        e.currency = rule.currency();
        e.sharedAmongMembers = rule.sharedAmongMembers();
        rules.save(e);
        QuotaRuleAuditEntity audit = new QuotaRuleAuditEntity();
        audit.ruleId = rule.ruleId();
        audit.operator = operator;
        audit.action = action;
        audit.detail = "limit=" + rule.quotaLimitMinor() + ",shared=" + rule.sharedAmongMembers()
                + ",traceId=" + traceId;
        audit.createdAt = time.now();
        audits.save(audit);
        return rule;
    }

    @Override
    public List<QuotaRule> listActive() {
        return rules.findAll().stream().map(QuotaRuleAdapter::toDomain).toList();
    }

    @Override
    public long countTeamMembers(String teamId) {
        return teamMembers.countByTeamId(teamId);
    }

    @Override
    @Transactional
    public void addMember(String teamId, String userId) {
        try {
            teamMembers.saveAndFlush(new TeamMemberEntity(teamId, userId));
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // 幂等：重复添加成员忽略
        }
    }

    static QuotaRule toDomain(QuotaRuleEntity e) {
        return new QuotaRule(e.ruleId, e.scopeType, e.scopeId, e.model, e.quotaLimitMinor, e.currency,
                e.sharedAmongMembers);
    }
}
