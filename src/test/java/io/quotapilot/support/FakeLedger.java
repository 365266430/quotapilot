package io.quotapilot.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import io.quotapilot.common.DomainExceptions;
import io.quotapilot.common.TimeService;
import io.quotapilot.ledger.domain.ExposureRecord;
import io.quotapilot.ledger.domain.ExposureReason;
import io.quotapilot.ledger.domain.ExposureRepositoryPort;
import io.quotapilot.ledger.domain.ExposureState;
import io.quotapilot.ledger.domain.LedgerEntry;
import io.quotapilot.ledger.domain.LedgerEntryType;
import io.quotapilot.ledger.domain.LedgerPort;
import io.quotapilot.ledger.domain.LedgerQueryPort;
import io.quotapilot.ledger.domain.Reservation;
import io.quotapilot.ledger.domain.ReservationRepositoryPort;
import io.quotapilot.ledger.domain.ReservationStatus;
import io.quotapilot.ledger.domain.SettlementRecord;

/**
 * [测试支撑] 内存版账本内核假实现：语义与 JPA 适配器一致（requestId 唯一、状态机、ADJUST 幂等键）。
 * 仅用于纯领域逻辑单测；幂等与并发的权威验证由 H2/Redis 集成测试承担。
 */
public class FakeLedger implements LedgerPort, LedgerQueryPort, ReservationRepositoryPort {

    public final Map<String, Reservation> reservations = new LinkedHashMap<>();
    public final Map<String, ExposureRecord> exposures = new LinkedHashMap<>();
    public final List<LedgerEntry> entries = new ArrayList<>();
    public final Map<String, SettlementRecord> settlements = new LinkedHashMap<>();
    public final List<String> outboxEvents = new ArrayList<>();
    public final AtomicLong entrySeq = new AtomicLong();

    private final TimeService time;
    public boolean failNextHold = false;

    public FakeLedger(TimeService time) {
        this.time = time;
    }

    @Override
    public synchronized HoldResult hold(HoldCmd cmd) {
        if (failNextHold) {
            failNextHold = false;
            throw new DomainExceptions.LedgerWriteFailed("模拟 DB 写入失败", null);
        }
        Reservation existing = reservations.get(cmd.requestId());
        if (existing != null) {
            return new HoldResult(cmd.requestId(), existing.getHoldId(), existing.getAccountId(),
                    existing.getReservedAmountMinor(), true);
        }
        String holdId = cmd.holdId() == null ? "hold-" + cmd.requestId() : cmd.holdId();
        Reservation r = new Reservation(holdId, cmd.requestId(), cmd.accountId(), cmd.amountMinor(),
                cmd.priceVersionId(), cmd.traceId(), time.now(), cmd.expiresAt());
        reservations.put(cmd.requestId(), r);
        append(cmd.accountId(), cmd.requestId(), LedgerEntryType.HOLD, cmd.amountMinor(), io.quotapilot.ledger.domain.AmountKind.ESTIMATE,
                cmd.priceVersionId(), "RESERVE", null, null, cmd.traceId());
        outboxEvents.add("reservation.reserved:" + cmd.requestId());
        return new HoldResult(cmd.requestId(), holdId, cmd.accountId(), cmd.amountMinor(), false);
    }

    @Override
    public synchronized SettleResult settle(SettleCmd cmd) {
        Reservation r = mustFind(cmd.requestId());
        if (r.getStatus() == ReservationStatus.SETTLED) {
            SettlementRecord sr = settlements.get(cmd.requestId());
            return new SettleResult(cmd.requestId(), sr == null ? 0 : sr.getChargedAmountMinor(),
                    sr == null ? 0 : sr.getRefundAmountMinor(), false, true);
        }
        if (r.getStatus() == ReservationStatus.RELEASED) {
            throw new DomainExceptions.RequestStateConflict(cmd.requestId(), "RESERVED", "RELEASED");
        }
        long refund = Math.max(0, cmd.estimateMinor() - cmd.actualCostMinor());
        boolean over = cmd.actualCostMinor() > cmd.estimateMinor();
        append(cmd.accountId(), cmd.requestId(), LedgerEntryType.SETTLE, cmd.actualCostMinor(),
                io.quotapilot.ledger.domain.AmountKind.ACTUAL, cmd.priceVersionId(), "SETTLE", null, null, cmd.traceId());
        if (refund > 0) {
            append(cmd.accountId(), cmd.requestId(), LedgerEntryType.RELEASE, refund,
                    io.quotapilot.ledger.domain.AmountKind.ESTIMATE, cmd.priceVersionId(), "REFUND_SURPLUS", null, null,
                    cmd.traceId());
        }
        r.markSettled(time.now());
        settlements.put(cmd.requestId(), new SettlementRecord(cmd.requestId(), cmd.holdId(), cmd.accountId(),
                cmd.actualCostMinor(), cmd.actualCostMinor(), refund, SettlementRecord.Status.SETTLED,
                cmd.priceVersionId(), cmd.traceId(), time.now()));
        outboxEvents.add("request.settled:" + cmd.requestId());
        return new SettleResult(cmd.requestId(), cmd.actualCostMinor(), refund, over, false);
    }

    @Override
    public synchronized SettleResult settleLate(SettleCmd cmd) {
        Reservation r = mustFind(cmd.requestId());
        if (r.getStatus() == ReservationStatus.SETTLED) {
            SettlementRecord sr = settlements.get(cmd.requestId());
            return new SettleResult(cmd.requestId(), sr == null ? 0 : sr.getChargedAmountMinor(), 0, false, true);
        }
        ExposureRecord exp = exposures.get(cmd.requestId());
        if (exp == null || exp.getState() != ExposureState.PENDING) {
            throw new DomainExceptions.RequestStateConflict(cmd.requestId(), "PENDING 敞口", "无敞口");
        }
        append(cmd.accountId(), cmd.requestId(), LedgerEntryType.SETTLE, cmd.actualCostMinor(),
                io.quotapilot.ledger.domain.AmountKind.ACTUAL, cmd.priceVersionId(), "LATE_SETTLE", null, null, cmd.traceId());
        exp.markSettled(cmd.actualCostMinor(), time.now(), "settle:" + cmd.requestId());
        settlements.put(cmd.requestId(), new SettlementRecord(cmd.requestId(), cmd.holdId(), cmd.accountId(),
                cmd.actualCostMinor(), cmd.actualCostMinor(), 0, SettlementRecord.Status.SETTLED,
                cmd.priceVersionId(), cmd.traceId(), time.now()));
        outboxEvents.add("request.settled:" + cmd.requestId());
        return new SettleResult(cmd.requestId(), cmd.actualCostMinor(), 0, false, false);
    }

    @Override
    public synchronized ReleaseResult release(ReleaseCmd cmd) {
        Reservation r = mustFind(cmd.requestId());
        if (r.getStatus() == ReservationStatus.SETTLED) {
            throw new DomainExceptions.RequestStateConflict(cmd.requestId(), "RESERVED|RELEASED", "SETTLED");
        }
        if (r.getStatus() == ReservationStatus.RELEASED) {
            ExposureRecord e = exposures.get(cmd.requestId());
            return new ReleaseResult(cmd.requestId(), r.getReservedAmountMinor(),
                    e == null ? null : e.getExposureId(), true);
        }
        append(cmd.accountId(), cmd.requestId(), LedgerEntryType.RELEASE, cmd.amountMinor(),
                io.quotapilot.ledger.domain.AmountKind.ESTIMATE, r.getPriceVersionId(), cmd.reason(), null, null, cmd.traceId());
        r.markReleased(time.now(), cmd.reason());
        settlements.put(cmd.requestId(), new SettlementRecord(cmd.requestId(), cmd.holdId(), cmd.accountId(), 0, 0,
                cmd.amountMinor(), SettlementRecord.Status.RELEASED, r.getPriceVersionId(), cmd.traceId(), time.now()));
        String exposureId = null;
        if (cmd.externalRisk()) {
            ExposureRecord exp = exposures.get(cmd.requestId());
            if (exp == null) {
                exp = new ExposureRecord(null, cmd.requestId(), cmd.holdId(), cmd.accountId(), cmd.amountMinor(),
                        cmd.exposureReason() == null ? ExposureReason.SUPPLIER_UNKNOWN
                                : ExposureReason.valueOf(cmd.exposureReason()),
                        time.now(), cmd.graceDeadline());
                exposures.put(cmd.requestId(), exp);
            }
            exposureId = exp.getExposureId();
        }
        outboxEvents.add("request.released:" + cmd.requestId());
        return new ReleaseResult(cmd.requestId(), cmd.amountMinor(), exposureId, false);
    }

    @Override
    public synchronized AdjustResult adjust(AdjustCmd cmd) {
        if (cmd.idempotencyKey() != null) {
            Optional<LedgerEntry> hit = entries.stream()
                    .filter(e -> cmd.idempotencyKey().equals(e.getIdempotencyKey())).findFirst();
            if (hit.isPresent()) {
                return new AdjustResult(hit.get().getEntryId(), hit.get().getAmountMinor(), true);
            }
        }
        LedgerEntry e = append(cmd.accountId(), cmd.requestId(), LedgerEntryType.ADJUST, cmd.amountMinor(),
                io.quotapilot.ledger.domain.AmountKind.ADJUSTMENT, null, cmd.reason(), cmd.evidenceRef(),
                cmd.idempotencyKey(), cmd.traceId());
        outboxEvents.add("ledger.adjusted:" + e.getEntryId());
        return new AdjustResult(e.getEntryId(), cmd.amountMinor(), false);
    }

    @Override
    public synchronized AdjustResult closeExposure(String exposureId, String traceId) {
        ExposureRecord exp = exposures.values().stream()
                .filter(e -> e.getExposureId().equals(exposureId)).findFirst()
                .orElseThrow(() -> new DomainExceptions.NotFound("敞口不存在: " + exposureId));
        if (exp.getState() != ExposureState.PENDING) {
            throw new DomainExceptions.DuplicateRequest(exp.getRequestId(), "closeExposure");
        }
        String idemKey = "exposure-close:" + exposureId;
        LedgerEntry e = append(exp.getAccountId(), exp.getRequestId(), LedgerEntryType.ADJUST,
                exp.getEstimatedAmountMinor(), io.quotapilot.ledger.domain.AmountKind.ADJUSTMENT, null,
                "EXPOSURE_CLOSED", "exposure:" + exposureId, idemKey, traceId);
        exp.markClosedWithAdjustment(exp.getEstimatedAmountMinor(), time.now(), "exposure:" + exposureId);
        outboxEvents.add("exposure.closed:" + exposureId);
        return new AdjustResult(e.getEntryId(), exp.getEstimatedAmountMinor(), false);
    }

    // ---- LedgerQueryPort ----

    @Override
    public synchronized Map<LedgerEntryType, Long> sumsByType(String accountId) {
        Map<LedgerEntryType, Long> sums = new LinkedHashMap<>();
        for (LedgerEntryType t : LedgerEntryType.values()) {
            sums.put(t, 0L);
        }
        for (LedgerEntry e : entries) {
            if (e.getAccountId().equals(accountId)) {
                sums.merge(e.getType(), e.getAmountMinor(), Long::sum);
            }
        }
        return sums;
    }

    @Override
    public synchronized List<LedgerEntry> allEntries(String accountId) {
        return entries.stream().filter(e -> e.getAccountId().equals(accountId)).toList();
    }

    @Override
    public synchronized long countEntries(String accountId) {
        return allEntries(accountId).size();
    }

    @Override
    public synchronized List<String> recentActiveAccountIds(Instant since) {
        return entries.stream().filter(e -> e.getCreatedAt().isAfter(since)).map(LedgerEntry::getAccountId).distinct().toList();
    }

    @Override
    public synchronized Optional<SettlementRecord> settlementRecord(String requestId) {
        return Optional.ofNullable(settlements.get(requestId));
    }

    @Override
    public synchronized long netChargedByRequest(String requestId) {
        return entries.stream().filter(e -> requestId.equals(e.getRequestId()))
                .filter(e -> e.getType() == LedgerEntryType.SETTLE || e.getType() == LedgerEntryType.ADJUST)
                .mapToLong(LedgerEntry::getAmountMinor).sum();
    }

    @Override
    public synchronized long settledSince(String accountId, Instant since) {
        return entries.stream().filter(e -> e.getAccountId().equals(accountId))
                .filter(e -> e.getType() == LedgerEntryType.SETTLE && e.getCreatedAt().isAfter(since))
                .mapToLong(LedgerEntry::getAmountMinor).sum();
    }

    // ---- ReservationRepositoryPort ----

    @Override
    public void save(Reservation reservation) {
        if (reservations.containsKey(reservation.getRequestId())) {
            throw new DomainExceptions.DuplicateRequest(reservation.getRequestId(), "reserve");
        }
        reservations.put(reservation.getRequestId(), reservation);
    }

    @Override
    public void update(Reservation reservation) {
        reservations.put(reservation.getRequestId(), reservation);
    }

    @Override
    public synchronized Optional<Reservation> findByRequestId(String requestId) {
        return Optional.ofNullable(reservations.get(requestId));
    }

    @Override
    public synchronized List<Reservation> findExpiredBefore(Instant at) {
        return reservations.values().stream().filter(r -> r.isExpired(at)).toList();
    }

    @Override
    public synchronized List<Reservation> findByAccount(String accountId, int limit) {
        return reservations.values().stream().filter(r -> r.getAccountId().equals(accountId)).limit(limit).toList();
    }

    @Override
    public synchronized long sumActiveHolds(String accountId) {
        return reservations.values().stream()
                .filter(r -> r.getAccountId().equals(accountId) && r.getStatus() == ReservationStatus.RESERVED)
                .mapToLong(Reservation::getReservedAmountMinor).sum();
    }

    @Override
    public synchronized List<String> findSettledRequestIds(int limit) {
        return reservations.values().stream().filter(r -> r.getStatus() == ReservationStatus.SETTLED)
                .limit(limit).map(Reservation::getRequestId).toList();
    }

    // ---- ExposureRepositoryPort（独立视图类，避免与 Reservation 端口同名方法冲突） ----

    public ExposureRepositoryPort exposurePort() {
        return new ExposurePortView();
    }

    private class ExposurePortView implements ExposureRepositoryPort {

        @Override
        public void save(ExposureRecord exposure) {
            exposures.put(exposure.getRequestId(), exposure);
        }

        @Override
        public void update(ExposureRecord exposure) {
            exposures.put(exposure.getRequestId(), exposure);
        }

        @Override
        public Optional<ExposureRecord> findByRequestId(String requestId) {
            return Optional.ofNullable(exposures.get(requestId));
        }

        @Override
        public Optional<ExposureRecord> findById(String exposureId) {
            return exposures.values().stream().filter(e -> e.getExposureId().equals(exposureId)).findFirst();
        }

        @Override
        public List<ExposureRecord> findPendingPastGrace(Instant now) {
            return exposures.values().stream().filter(e -> e.isPastGrace(now)).toList();
        }

        @Override
        public long countOpen(String accountId) {
            return exposures.values().stream()
                    .filter(e -> e.getAccountId().equals(accountId) && e.getState() == ExposureState.PENDING).count();
        }

        @Override
        public List<ExposureRecord> findOpenByAccount(String accountId) {
            return exposures.values().stream()
                    .filter(e -> e.getAccountId().equals(accountId) && e.getState() == ExposureState.PENDING).toList();
        }
    }

    private Reservation mustFind(String requestId) {
        Reservation r = reservations.get(requestId);
        if (r == null) {
            throw new DomainExceptions.NotFound("预留不存在: " + requestId);
        }
        return r;
    }

    private LedgerEntry append(String accountId, String requestId, LedgerEntryType type, long amountMinor,
                               io.quotapilot.ledger.domain.AmountKind kind, String priceVersionId, String reason,
                               String evidenceRef, String idempotencyKey, String traceId) {
        LedgerEntry e = new LedgerEntry("e-" + entrySeq.incrementAndGet(), accountId, requestId, type, amountMinor,
                kind, priceVersionId, reason, evidenceRef, idempotencyKey, traceId, time.now());
        entries.add(e);
        return e;
    }
}
