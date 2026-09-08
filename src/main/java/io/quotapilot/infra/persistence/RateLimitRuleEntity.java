package io.quotapilot.infra.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** [M7] 限流规则表（每账户覆盖；0=该维度不限）。 */
@Entity
@Table(name = "rate_limit_rules")
public class RateLimitRuleEntity {
    @Id
    public String accountId;
    public long requestsPerSecond;
    public long tokensPerMinute;
    public long billingUnitsPerMinute;
}
