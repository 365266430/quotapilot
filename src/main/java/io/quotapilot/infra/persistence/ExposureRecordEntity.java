package io.quotapilot.infra.persistence;

import java.time.Instant;

import io.quotapilot.ledger.domain.ExposureReason;
import io.quotapilot.ledger.domain.ExposureState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** [M0] 未决敞口表。requestId 唯一；@Version 防 settleLate 与 closeExposure 并发双收敛。 */
@Entity
@Table(name = "exposures", indexes = @Index(name = "ix_expo_state", columnList = "state,grace_deadline"))
public class ExposureRecordEntity {
    @Id
    public String exposureId;
    @Column(unique = true)
    public String requestId;
    public String holdId;
    public String accountId;
    public long estimatedAmountMinor;
    @Enumerated(EnumType.STRING)
    public ExposureReason reason;
    @Enumerated(EnumType.STRING)
    public ExposureState state;
    public Instant createdAt;
    public Instant graceDeadline;
    public Instant resolvedAt;
    public long resolvedAmountMinor;
    public String evidenceRef;
    @Version
    public Long version;
}
