package io.quotapilot.reserve.domain;

import java.time.Instant;

/**
 * [M3] 预留命令。
 */
public record ReserveCommand(String requestId, ScopeValues scope, String skuModel, String usageType,
                             Long declaredEstimatedUnits, long ttlSeconds, String traceId) {

    public record ScopeValues(String userId, String teamId, String taskId) {}
}
