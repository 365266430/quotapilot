package io.quotapilot.infra.persistence;

import java.time.Instant;

import io.quotapilot.ledger.domain.SettlementRecord;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** [M0] 结算记录表：requestId 主键 = 结算幂等根（P6）。 */
@Entity
@Table(name = "settlement_records")
public class SettlementRecordEntity {
    @Id
    public String requestId;
    public String holdId;
    public String accountId;
    public long actualAmountMinor;
    public long chargedAmountMinor;
    public long refundAmountMinor;
    @Enumerated(EnumType.STRING)
    public SettlementRecord.Status status;
    public String priceVersionId;
    public String traceId;
    public Instant createdAt;
}
