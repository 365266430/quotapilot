package io.quotapilot.quota.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import io.quotapilot.common.Amounts;
import io.quotapilot.ledger.domain.ScopeType;

/**
 * [M1] 默认额度解析器（纯领域逻辑）。
 * 规则匹配后按「团队限额分摊到成员」换算（sharedAmongMembers），并支持系统默认额度兜底。
 */
public class DefaultQuotaPolicyResolver implements QuotaPolicyResolver {

    private final QuotaRulePort rulePort;
    private final long systemDefaultLimitMinor;
    private final String defaultCurrency;

    public DefaultQuotaPolicyResolver(QuotaRulePort rulePort, long systemDefaultLimitMinor, String defaultCurrency) {
        this.rulePort = rulePort;
        this.systemDefaultLimitMinor = systemDefaultLimitMinor;
        this.defaultCurrency = defaultCurrency;
    }

    @Override
    public Optional<EffectiveQuota> resolve(ScopeContext ctx) {
        List<QuotaRule> rules = rulePort.listActive();
        return matchTask(rules, ctx)
                .or(() -> matchUserModel(rules, ctx))
                .or(() -> matchTeamModel(rules, ctx))
                .or(() -> matchUser(rules, ctx))
                .or(() -> matchTeam(rules, ctx))
                .or(() -> matchModel(rules, ctx))
                .or(() -> systemDefault());
    }

    private Optional<EffectiveQuota> systemDefault() {
        if (systemDefaultLimitMinor <= 0) {
            return Optional.empty();
        }
        return Optional.of(new EffectiveQuota(ScopeType.USER, "*system-default*", systemDefaultLimitMinor,
                defaultCurrency, "system-default", false, ScopeType.USER, "*system-default*"));
    }

    private Optional<EffectiveQuota> matchTask(List<QuotaRule> rules, ScopeContext ctx) {
        if (ctx.taskId() == null) {
            return Optional.empty();
        }
        return first(rules, ScopeType.TASK, ctx.taskId(), null, false, ctx);
    }

    private Optional<EffectiveQuota> matchUserModel(List<QuotaRule> rules, ScopeContext ctx) {
        if (ctx.userId() == null || ctx.model() == null) {
            return Optional.empty();
        }
        return first(rules, ScopeType.USER_MODEL, ctx.userId(), ctx.model(), false, ctx);
    }

    private Optional<EffectiveQuota> matchTeamModel(List<QuotaRule> rules, ScopeContext ctx) {
        if (ctx.teamId() == null || ctx.model() == null) {
            return Optional.empty();
        }
        return first(rules, ScopeType.TEAM_MODEL, ctx.teamId(), ctx.model(), true, ctx);
    }

    private Optional<EffectiveQuota> matchUser(List<QuotaRule> rules, ScopeContext ctx) {
        if (ctx.userId() == null) {
            return Optional.empty();
        }
        return first(rules, ScopeType.USER, ctx.userId(), null, false, ctx);
    }

    private Optional<EffectiveQuota> matchTeam(List<QuotaRule> rules, ScopeContext ctx) {
        if (ctx.teamId() == null) {
            return Optional.empty();
        }
        return first(rules, ScopeType.TEAM, ctx.teamId(), null, true, ctx);
    }

    private Optional<EffectiveQuota> matchModel(List<QuotaRule> rules, ScopeContext ctx) {
        if (ctx.model() == null) {
            return Optional.empty();
        }
        return first(rules, ScopeType.MODEL, ctx.model(), null, false, ctx);
    }

    private Optional<EffectiveQuota> first(List<QuotaRule> rules, ScopeType type, String scopeId, String model,
                                           boolean allowShare, ScopeContext ctx) {
        return rules.stream()
                .filter(r -> r.scopeType() == type)
                .filter(r -> scopeId.equals(r.scopeId()))
                .filter(r -> model == null || model.equals(r.model()))
                .max(Comparator.comparing(QuotaRule::quotaLimitMinor))
                .map(r -> toEffective(r, ctx));
    }

    private EffectiveQuota toEffective(QuotaRule rule, ScopeContext ctx) {
        long limit = rule.quotaLimitMinor();
        ScopeType accountScopeType = rule.scopeType();
        String accountScopeId = rule.scopeId();
        if (rule.sharedAmongMembers()) {
            // 团队限额分摊到成员：每成员独立账户 (USER, userId)，限额 = 团队总额 / 成员数（向上取整）
            long members = Math.max(1, rulePort.countTeamMembers(rule.scopeId()));
            limit = Amounts.avgCeil(limit, members);
            if (ctx.userId() != null) {
                accountScopeType = ScopeType.USER;
                accountScopeId = ctx.userId();
            }
        }
        return new EffectiveQuota(rule.scopeType(), rule.scopeId(), limit, rule.currency(), rule.ruleId(),
                rule.sharedAmongMembers(), accountScopeType, accountScopeId);
    }
}
