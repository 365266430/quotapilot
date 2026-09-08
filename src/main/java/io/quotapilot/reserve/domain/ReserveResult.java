package io.quotapilot.reserve.domain;

import java.time.Instant;

/**
 * [M3] 预留结果。
 */
public record ReserveResult(String requestId, String holdId, String accountId, long estimateMinor,
                            long estimatedUnits, String priceVersionId, Instant expiresAt, boolean duplicate) {}
