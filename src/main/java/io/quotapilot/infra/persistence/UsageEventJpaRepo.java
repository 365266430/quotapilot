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
}
