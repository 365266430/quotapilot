package io.quotapilot.infra.persistence;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.quotapilot.ratelimit.domain.RateLimitRule;
import io.quotapilot.ratelimit.domain.RateLimitRulePort;

/** [M7] 限流规则适配器。 */
@Component
public class RateLimitRuleAdapter implements RateLimitRulePort {

    private final RateLimitRuleJpaRepo repo;

    public RateLimitRuleAdapter(RateLimitRuleJpaRepo repo) {
        this.repo = repo;
    }

    @Override
    public Optional<RateLimitRule> findByAccount(String accountId) {
        return repo.findById(accountId).map(e -> new RateLimitRule(e.accountId, e.requestsPerSecond,
                e.tokensPerMinute, e.billingUnitsPerMinute));
    }

    @Override
    @Transactional
    public RateLimitRule save(RateLimitRule rule) {
        RateLimitRuleEntity e = repo.findById(rule.accountId()).orElseGet(() -> {
            RateLimitRuleEntity n = new RateLimitRuleEntity();
            n.accountId = rule.accountId();
            return n;
        });
        e.requestsPerSecond = rule.requestsPerSecond();
        e.tokensPerMinute = rule.tokensPerMinute();
        e.billingUnitsPerMinute = rule.billingUnitsPerMinute();
        repo.save(e);
        return rule;
    }
}
