package io.quotapilot.pricing.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * [M2] 价格版本。生效窗口 [effectiveFrom, effectiveTo)，effectiveTo=null 表示长期有效。
 * 版本变更不物理删除旧版本：新旧版本都可查询，用于审计与回溯重算（P5）。
 * pricePerUnitMinor：每 1 计费单位的价格，单位=千分之一分（1e-5 元）。
 * 例：0.01 元 / 1K token = 1 minorUnit / token。
 */
public class PriceVersion {

    public enum Status { ACTIVE }

    private final String priceVersionId;
    private final String skuKey;
    private final UsageType unit;
    private final long pricePerUnitMinor;
    private final String currency;
    private final long version;
    private final Instant effectiveFrom;
    private Instant effectiveTo;
    private final Status status;

    public PriceVersion(String priceVersionId, String skuKey, UsageType unit, long pricePerUnitMinor, String currency,
                        long version, Instant effectiveFrom, Instant effectiveTo) {
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveTo 必须晚于 effectiveFrom");
        }
        this.priceVersionId = priceVersionId == null ? UUID.randomUUID().toString() : priceVersionId;
        this.skuKey = skuKey;
        this.unit = unit;
        this.pricePerUnitMinor = pricePerUnitMinor;
        this.currency = currency;
        this.version = version;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.status = Status.ACTIVE;
    }

    public boolean covers(Instant at) {
        return !at.isBefore(effectiveFrom) && (effectiveTo == null || at.isBefore(effectiveTo));
    }

    public void closeAt(Instant at) {
        if (effectiveTo == null || effectiveTo.isAfter(at)) {
            this.effectiveTo = at;
        }
    }

    public String getPriceVersionId() { return priceVersionId; }
    public String getSkuKey() { return skuKey; }
    public UsageType getUnit() { return unit; }
    public long getPricePerUnitMinor() { return pricePerUnitMinor; }
    public String getCurrency() { return currency; }
    public long getVersion() { return version; }
    public Instant getEffectiveFrom() { return effectiveFrom; }
    public Instant getEffectiveTo() { return effectiveTo; }
    public Status getStatus() { return status; }
}
