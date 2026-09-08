package io.quotapilot.infra.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.quotapilot.common.TimeService;
import io.quotapilot.ledger.domain.AccountPort;
import io.quotapilot.ledger.domain.AccountStatus;
import io.quotapilot.ledger.domain.ScopeType;

/** [M0/M1] 账户适配器：getOrCreate 以 (scopeType, scopeId) 唯一约束保证原子供给。 */
@Component
public class AccountAdapter implements AccountPort {

    private final AccountJpaRepo repo;
    private final TimeService time;
    private final io.quotapilot.ledger.domain.LedgerQueryPort ledgerQuery;

    public AccountAdapter(AccountJpaRepo repo, TimeService time, io.quotapilot.ledger.domain.LedgerQueryPort ledgerQuery) {
        this.repo = repo;
        this.time = time;
        this.ledgerQuery = ledgerQuery;
    }

    @Override
    @Transactional
    public AccountSnapshot getOrCreate(ScopeType scopeType, String scopeId, long initialLimitMinor, String currency) {
        Optional<AccountEntity> found = repo.findByScopeTypeAndScopeId(scopeType, scopeId);
        if (found.isPresent()) {
            return toSnapshot(found.get());
        }
        AccountEntity e = new AccountEntity(UUID.randomUUID().toString(), scopeType, scopeId, initialLimitMinor,
                currency, AccountStatus.ACTIVE, time.now());
        try {
            repo.saveAndFlush(e);
            return toSnapshot(e);
        } catch (DataIntegrityViolationException race) {
            return toSnapshot(repo.findByScopeTypeAndScopeId(scopeType, scopeId).orElseThrow(() -> race));
        }
    }

    @Override
    public Optional<AccountSnapshot> find(String accountId) {
        return repo.findById(accountId).map(AccountAdapter::toSnapshot);
    }

    @Override
    public Optional<AccountSnapshot> findByScope(ScopeType scopeType, String scopeId) {
        return repo.findByScopeTypeAndScopeId(scopeType, scopeId).map(AccountAdapter::toSnapshot);
    }

    @Override
    public long settledMinor(String accountId) {
        var sums = ledgerQuery.sumsByType(accountId);
        return sums.getOrDefault(io.quotapilot.ledger.domain.LedgerEntryType.SETTLE, 0L)
                + sums.getOrDefault(io.quotapilot.ledger.domain.LedgerEntryType.ADJUST, 0L);
    }

    @Override
    @Transactional
    public void updateStatus(String accountId, AccountStatus status) {
        AccountEntity e = repo.findById(accountId).orElseThrow();
        e.status = status;
        repo.save(e);
    }

    @Override
    @Transactional
    public void updateLimit(String accountId, long newLimitMinor) {
        AccountEntity e = repo.findById(accountId).orElseThrow();
        e.quotaLimitMinor = newLimitMinor;
        repo.save(e);
    }

    @Override
    public List<String> allAccountIds() {
        return repo.findAll().stream().map(e -> e.accountId).toList();
    }

    private static AccountSnapshot toSnapshot(AccountEntity e) {
        return new AccountSnapshot(e.accountId, e.scopeType, e.scopeId, e.quotaLimitMinor, e.currency, e.status);
    }
}
