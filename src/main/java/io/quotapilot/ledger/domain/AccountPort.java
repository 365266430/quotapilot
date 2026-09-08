package io.quotapilot.ledger.domain;

/**
 * [M0/M1] 账户端口：getOrCreate 保证「规则→账户」的原子供给；limit/status 变更需触发 Redis 对账（调用方负责）。
 */
public interface AccountPort {

    AccountSnapshot getOrCreate(ScopeType scopeType, String scopeId, long initialLimitMinor, String currency);

    java.util.Optional<AccountSnapshot> find(String accountId);

    java.util.Optional<AccountSnapshot> findByScope(ScopeType scopeType, String scopeId);

    void updateStatus(String accountId, AccountStatus status);

    void updateLimit(String accountId, long newLimitMinor);

    java.util.List<String> allAccountIds();

    /** 当前已结算口径（QuotaService 变更限额后做 Redis reconcile 用）。 */
    long settledMinor(String accountId);

    record AccountSnapshot(String accountId, ScopeType scopeType, String scopeId, long quotaLimitMinor,
                           String currency, AccountStatus status) {}
}
