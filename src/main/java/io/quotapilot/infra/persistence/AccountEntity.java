package io.quotapilot.infra.persistence;

import java.time.Instant;

import io.quotapilot.ledger.domain.AccountStatus;
import io.quotapilot.ledger.domain.ScopeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** [M0] 账户表。 */
@Entity
@Table(name = "accounts", uniqueConstraints = @UniqueConstraint(name = "uk_accounts_scope", columnNames = {"scope_type", "scope_id"}))
public class AccountEntity {
    @Id
    public String accountId;
    @Enumerated(EnumType.STRING)
    public ScopeType scopeType;
    public String scopeId;
    public long quotaLimitMinor;
    public String currency;
    @Enumerated(EnumType.STRING)
    public AccountStatus status;
    public Instant createdAt;

    public AccountEntity() {}

    public AccountEntity(String accountId, ScopeType scopeType, String scopeId, long quotaLimitMinor,
                         String currency, AccountStatus status, Instant createdAt) {
        this.accountId = accountId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.quotaLimitMinor = quotaLimitMinor;
        this.currency = currency;
        this.status = status;
        this.createdAt = createdAt;
    }
}
