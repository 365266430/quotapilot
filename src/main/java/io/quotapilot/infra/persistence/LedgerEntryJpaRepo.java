package io.quotapilot.infra.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.quotapilot.ledger.domain.LedgerEntryType;

public interface LedgerEntryJpaRepo extends JpaRepository<LedgerEntryEntity, String> {

    List<LedgerEntryEntity> findByAccountIdOrderByCreatedAtAsc(String accountId);

    long countByAccountId(String accountId);

    Optional<LedgerEntryEntity> findByIdempotencyKey(String idempotencyKey);

    @Query("select e.type as type, coalesce(sum(e.amountMinor),0) as value from LedgerEntryEntity e where e.accountId = :a group by e.type")
    List<TypeSum> sumByAccount(@Param("a") String accountId);

    @Query("select coalesce(sum(e.amountMinor),0) from LedgerEntryEntity e where e.requestId = :r and e.type in (io.quotapilot.ledger.domain.LedgerEntryType.SETTLE, io.quotapilot.ledger.domain.LedgerEntryType.ADJUST)")
    long netChargedByRequest(@Param("r") String requestId);

    @Query("select distinct e.accountId from LedgerEntryEntity e where e.createdAt > :since")
    List<String> recentActiveAccountIds(@Param("since") Instant since);

    @Query("select coalesce(sum(e.amountMinor),0) from LedgerEntryEntity e where e.accountId = :a and e.type = io.quotapilot.ledger.domain.LedgerEntryType.SETTLE and e.createdAt > :since")
    long settledSince(@Param("a") String accountId, @Param("since") Instant since);

    interface TypeSum {
        LedgerEntryType getType();

        Long getValue();
    }
}
