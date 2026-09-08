package io.quotapilot.infra.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationJpaRepo extends JpaRepository<ReservationEntity, String> {

    Optional<ReservationEntity> findByRequestId(String requestId);

    List<ReservationEntity> findByExpiresAtBeforeAndStatus(Instant at, io.quotapilot.ledger.domain.ReservationStatus status);

    List<ReservationEntity> findByAccountIdOrderByCreatedAtDesc(String accountId);

    @Query("select coalesce(sum(r.reservedAmountMinor),0) from ReservationEntity r where r.accountId = :a and r.status = io.quotapilot.ledger.domain.ReservationStatus.RESERVED")
    long sumActiveHolds(@Param("a") String accountId);

    List<ReservationEntity> findTop200ByStatusOrderByCreatedAtDesc(io.quotapilot.ledger.domain.ReservationStatus status);

    boolean existsByRequestId(String requestId);
}
