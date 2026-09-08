package io.quotapilot.quota.domain;

import io.quotapilot.ledger.domain.ScopeType;

/**
 * [M1] 解析后的生效额度：确定计费账户（accountScope*）与预算上限。
 * 团队规则语义：sharedAmongMembers=false → 共享池账户 (TEAM, teamId)，limit=团队总额；
 * sharedAmongMembers=true → 分摊到成员：计费账户为 (USER, userId)，limit=团队总额/成员数（向上取整）。
 */
public record EffectiveQuota(ScopeType scopeType, String scopeId, long limitMinor, String currency,
                             String ruleId, boolean sharedAmongMembers, ScopeType accountScopeType,
                             String accountScopeId) {}

