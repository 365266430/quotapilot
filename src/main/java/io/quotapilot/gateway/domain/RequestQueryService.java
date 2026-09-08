package io.quotapilot.gateway.domain;

import java.time.Instant;
import java.util.Optional;

import io.quotapilot.common.DomainExceptions;
import io.quotapilot.ledger.domain.ExposureRecord;
import io.quotapilot.ledger.domain.ExposureRepositoryPort;
import io.quotapilot.ledger.domain.LedgerQueryPort;
import io.quotapilot.ledger.domain.Reservation;
import io.quotapilot.ledger.domain.ReservationRepositoryPort;

/**
 * [M6] 请求状态查询（规范 §8：GET /v1/requests/{requestId} → RESERVED/SETTLED/RELEASED/EXPOSED）。
 */
public class RequestQueryService {

    private final ReservationRepositoryPort reservationRepo;
    private final LedgerQueryPort ledgerQuery;
    private final ExposureRepositoryPort exposureRepo;

    public RequestQueryService(ReservationRepositoryPort reservationRepo, LedgerQueryPort ledgerQuery,
                               ExposureRepositoryPort exposureRepo) {
        this.reservationRepo = reservationRepo;
        this.ledgerQuery = ledgerQuery;
        this.exposureRepo = exposureRepo;
    }

    public RequestView status(String requestId) {
        Reservation r = reservationRepo.findByRequestId(requestId)
                .orElseThrow(() -> new DomainExceptions.NotFound("请求不存在: " + requestId));
        Optional<io.quotapilot.ledger.domain.SettlementRecord> sr = ledgerQuery.settlementRecord(requestId);
        Optional<ExposureRecord> exp = exposureRepo.findByRequestId(requestId);
        return new RequestView(r.getRequestId(), r.getHoldId(), r.getAccountId(), r.getStatus().name(),
                r.getFinishReason(), r.getReservedAmountMinor(), r.getPriceVersionId(), r.getCreatedAt(),
                r.getExpiresAt(),
                sr.map(s -> new SettlementView(s.getStatus().name(), s.getActualAmountMinor(),
                        s.getChargedAmountMinor(), s.getRefundAmountMinor())).orElse(null),
                exp.map(e -> new ExposureView(e.getState().name(), e.getReason().name(),
                        e.getEstimatedAmountMinor(), e.getGraceDeadline())).orElse(null));
    }

    public record SettlementView(String status, long actualAmountMinor, long chargedAmountMinor,
                                 long refundAmountMinor) {}

    public record ExposureView(String state, String reason, long estimatedAmountMinor, Instant graceDeadline) {}

    public record RequestView(String requestId, String holdId, String accountId, String reservationStatus,
                              String finishReason, long reservedAmountMinor, String priceVersionId, Instant createdAt,
                              Instant expiresAt, SettlementView settlement, ExposureView exposure) {}
}
