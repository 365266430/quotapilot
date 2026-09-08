package io.quotapilot.infra.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.quotapilot.common.DomainExceptions;
import io.quotapilot.ledger.domain.Reservation;
import io.quotapilot.ledger.domain.ReservationRepositoryPort;
import io.quotapilot.ledger.domain.ReservationStatus;

/** [M0] 预留仓储适配器。 */
@Component
public class ReservationAdapter implements ReservationRepositoryPort {

    private final ReservationJpaRepo repo;

    public ReservationAdapter(ReservationJpaRepo repo) {
        this.repo = repo;
    }

    @Override
    @Transactional
    public void save(Reservation reservation) {
        if (repo.existsByRequestId(reservation.getRequestId())) {
            throw new DomainExceptions.DuplicateRequest(reservation.getRequestId(), "reserve");
        }
        ReservationEntity e = new ReservationEntity();
        e.holdId = reservation.getHoldId();
        e.requestId = reservation.getRequestId();
        e.accountId = reservation.getAccountId();
        e.reservedAmountMinor = reservation.getReservedAmountMinor();
        e.priceVersionId = reservation.getPriceVersionId();
        e.traceId = reservation.getTraceId();
        e.createdAt = reservation.getCreatedAt();
        e.expiresAt = reservation.getExpiresAt();
        e.status = reservation.getStatus();
        try {
            repo.saveAndFlush(e);
        } catch (DataIntegrityViolationException err) {
            throw new DomainExceptions.DuplicateRequest(reservation.getRequestId(), "reserve");
        }
    }

    @Override
    @Transactional
    public void update(Reservation reservation) {
        ReservationEntity e = repo.findById(reservation.getHoldId()).orElseThrow();
        e.status = reservation.getStatus();
        e.finishedAt = reservation.getFinishedAt();
        e.finishReason = reservation.getFinishReason();
        repo.save(e);
    }

    @Override
    public Optional<Reservation> findByRequestId(String requestId) {
        return repo.findByRequestId(requestId).map(ReservationAdapter::toDomain);
    }

    @Override
    public List<Reservation> findExpiredBefore(Instant at) {
        return repo.findByExpiresAtBeforeAndStatus(at, ReservationStatus.RESERVED).stream()
                .map(ReservationAdapter::toDomain).toList();
    }

    @Override
    public List<Reservation> findByAccount(String accountId, int limit) {
        return repo.findByAccountIdOrderByCreatedAtDesc(accountId).stream().limit(limit)
                .map(ReservationAdapter::toDomain).toList();
    }

    @Override
    public long sumActiveHolds(String accountId) {
        return repo.sumActiveHolds(accountId);
    }

    @Override
    public List<String> findSettledRequestIds(int limit) {
        return repo.findTop200ByStatusOrderByCreatedAtDesc(ReservationStatus.SETTLED).stream().limit(limit)
                .map(e -> e.requestId).toList();
    }

    static Reservation toDomain(ReservationEntity e) {
        return Reservation.restore(e.holdId, e.requestId, e.accountId, e.reservedAmountMinor, e.priceVersionId,
                e.traceId, e.createdAt, e.expiresAt, e.status, e.finishedAt, e.finishReason);
    }
}
