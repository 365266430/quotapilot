package io.quotapilot.pricing.domain;

import java.time.Instant;

/**
 * [M2] 价格快照：预留时固化到 Reservation 的计价依据（P5）。
 */
public record PriceSnapshot(String priceVersionId, String skuKey, UsageType unit, long pricePerUnitMinor,
                            String currency, long version, Instant effectiveFrom) {}
