package io.quotapilot.infra.persistence;

import java.time.Instant;

import io.quotapilot.metering.domain.UsageSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** [M8/P6/Q3] 用量事件表：幂等键 (request_id, source, seq) 唯一，重复回调不产生重复行。 */
@Entity
@Table(name = "usage_events", indexes = @Index(name = "ix_usage_account", columnList = "account_id,occurred_at"),
        uniqueConstraints = @UniqueConstraint(name = "uk_usage_idem", columnNames = {"request_id", "source", "seq"}))
public class UsageEventEntity {
    @Id
    public String usageEventId;
    public String requestId;
    public String supplierRequestId;
    public String accountId;
    public String sku;
    public String usageType;
    public long quantity;
    public Instant occurredAt;
    @Enumerated(EnumType.STRING)
    public UsageSource source;
    public long seq;
    public String traceId;
}
