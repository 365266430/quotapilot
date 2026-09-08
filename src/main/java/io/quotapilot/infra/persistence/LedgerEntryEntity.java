package io.quotapilot.infra.persistence;

import java.time.Instant;

import io.quotapilot.ledger.domain.AmountKind;
import io.quotapilot.ledger.domain.LedgerEntryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** [M0/P3] 账本流水表（不可变，只增不改）。ADJUST 幂等键唯一防重复入账。 */
@Entity
@Table(name = "ledger_entries", indexes = {
        @Index(name = "ix_ledger_account", columnList = "account_id,created_at"),
        @Index(name = "ix_ledger_request", columnList = "request_id")})
public class LedgerEntryEntity {
    @Id
    public String entryId;
    public String accountId;
    public String requestId;
    @Enumerated(EnumType.STRING)
    public LedgerEntryType type;
    public long amountMinor;
    @Enumerated(EnumType.STRING)
    public AmountKind kind;
    public String priceVersionId;
    public String reason;
    public String evidenceRef;
    @Column(name = "idempotency_key", unique = true)
    public String idempotencyKey;
    public String traceId;
    public Instant createdAt;
}
