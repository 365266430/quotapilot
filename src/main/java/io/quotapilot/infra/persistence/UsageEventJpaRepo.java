package io.quotapilot.infra.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UsageEventJpaRepo extends JpaRepository<UsageEventEntity, String> {
    List<UsageEventEntity> findByRequestId(String requestId);

    boolean existsByRequestIdAndSourceAndSeq(String requestId, io.quotapilot.metering.domain.UsageSource source, long seq);

    Page<UsageEventEntity> findByAccountIdOrderByOccurredAtDesc(String accountId, Pageable pageable);

    long countByAccountId(String accountId);

    long countByAccountIdAndOccurredAtAfter(String accountId, java.time.Instant since);

    @org.springframework.data.jpa.repository.Query("select coalesce(sum(e.quantity),0) from UsageEventEntity e where e.accountId = :a and e.occurredAt > :since")
    long sumQuantityByAccountSince(@org.springframework.data.repository.query.Param("a") String accountId,
                                   @org.springframework.data.repository.query.Param("since") java.time.Instant since);
}
