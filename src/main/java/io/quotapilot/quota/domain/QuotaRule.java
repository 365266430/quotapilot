package io.quotapilot.quota.domain;

import io.quotapilot.ledger.domain.ScopeType;

/**
 * [M1] 额度规则（不可变）。scopeType 决定匹配维度：
 * TASK 匹配 taskId；USER_MODEL 匹配 userId+model；TEAM_MODEL 匹配 teamId+model；
 * USER 匹配 userId；TEAM 匹配 teamId；MODEL 匹配 model（全局）。
 * sharedAmongMembers=true 表示团队限额分摊到成员（限额/成员数，向上取整）。
 */
public record QuotaRule(String ruleId, ScopeType scopeType, String scopeId, String model,
                        long quotaLimitMinor, String currency, boolean sharedAmongMembers) {}
