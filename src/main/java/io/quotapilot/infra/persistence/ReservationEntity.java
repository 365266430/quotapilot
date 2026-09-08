package io.quotapilot.infra.persistence;

import java.time.Instant;

import io.quotapilot.ledger.domain.ReservationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** [M0] 预留表。requestId 唯一约束 = 预留幂等根；@Version 保护状态机并发转移。 */
@Entity
@Table(name = "reservations")
public class ReservationEntity {
    @Id
    public String holdId;
    @Column(unique = true)
    public String requestId;
    public String accountId;
    public long reservedAmountMinor;
    public String priceVersionId;
    public String traceId;
    public Instant createdAt;
    public Instant expiresAt;
    @Enumerated(EnumType.STRING)
    public ReservationStatus status;
    public Instant finishedAt;
    public String finishReason;
    @Version
    public Long version;
}
