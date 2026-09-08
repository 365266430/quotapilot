package io.quotapilot.infra.persistence;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import io.quotapilot.ledger.domain.LedgerEntry;
import io.quotapilot.ledger.domain.LedgerEntryType;
import io.quotapilot.ledger.domain.LedgerQueryPort;
import io.quotapilot.ledger.domain.SettlementRecord;

/** [M0] 账本只读查询适配器（回放审计的权威数据源）。 */
@Component
public class LedgerQueryAdapter implements LedgerQueryPort {

    private final LedgerEntryJpaRepo entries;
    private final SettlementJpaRepo settlements;

    public LedgerQueryAdapter(LedgerEntryJpaRepo entries, SettlementJpaRepo settlements) {
        this.entries = entries;
        this.settlements = settlements;
    }

    @Override
    public Map<LedgerEntryType, Long> sumsByType(String accountId) {
        Map<LedgerEntryType, Long> sums = new EnumMap<>(LedgerEntryType.class);
        for (LedgerEntryType t : LedgerEntryType.values()) {
            sums.put(t, 0L);
        }
        for (LedgerEntryJpaRepo.TypeSum ts : entries.sumByAccount(accountId)) {
            if (ts.getType() != null) {
                sums.put(ts.getType(), ts.getValue());
            }
        }
        return sums;
    }

    @Override
    public List<LedgerEntry> allEntries(String accountId) {
        return entries.findByAccountIdOrderByCreatedAtAsc(accountId).stream().map(LedgerQueryAdapter::toDomain).toList();
    }

    @Override
    public long countEntries(String accountId) {
        return entries.countByAccountId(accountId);
    }

    @Override
    public List<String> recentActiveAccountIds(Instant since) {
        return entries.recentActiveAccountIds(since);
    }

    @Override
    public Optional<SettlementRecord> settlementRecord(String requestId) {
        return settlements.findById(requestId).map(e -> new SettlementRecord(e.requestId, e.holdId, e.accountId,
                e.actualAmountMinor, e.chargedAmountMinor, e.refundAmountMinor, e.status, e.priceVersionId,
                e.traceId, e.createdAt));
    }

    @Override
    public long netChargedByRequest(String requestId) {
        return entries.netChargedByRequest(requestId);
    }

    @Override
    public long settledSince(String accountId, Instant since) {
        return entries.settledSince(accountId, since);
    }

    static LedgerEntry toDomain(LedgerEntryEntity e) {
        return new LedgerEntry(e.entryId, e.accountId, e.requestId, e.type, e.amountMinor, e.kind, e.priceVersionId,
                e.reason, e.evidenceRef, e.idempotencyKey, e.traceId, e.createdAt);
    }
}
