package io.quotapilot.metering.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * [M8] 用量事件。幂等键 = requestId + source + seq（P6），重复投递不产生重复行。
 */
public record UsageEvent(String usageEventId, String requestId, String supplierRequestId, String accountId,
                         String sku, String usageType, long quantity, Instant occurredAt, UsageSource source,
                         long seq, String traceId) {

    public UsageEvent {
        if (usageEventId == null) {
            usageEventId = UUID.randomUUID().toString();
        }
    }
}
