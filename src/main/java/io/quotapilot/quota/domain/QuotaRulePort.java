package io.quotapilot.quota.domain;

import java.util.List;

/**
 * [M1] 额度规则存储端口。规则变更必须落审计日志（实现方负责 audit 表）。
 */
public interface QuotaRulePort {

    QuotaRule saveRule(QuotaRule rule, String operator, String traceId);

    List<QuotaRule> listActive();

    /** 团队成员数（团队限额分摊用）。 */
    long countTeamMembers(String teamId);

    void addMember(String teamId, String userId);

    /** [V1.1] 团队成员列表（限额迁移用）。 */
    java.util.List<String> listMembers(String teamId);
}
