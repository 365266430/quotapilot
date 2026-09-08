package io.quotapilot.infra.persistence;

import java.time.Instant;

import io.quotapilot.pricing.domain.UsageType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** [M2/P5] 价格版本表：新旧版本并存可查（审计回溯），不物理删除。 */
@Entity
@Table(name = "price_versions", indexes = @Index(name = "ix_price_sku", columnList = "sku_key"))
public class PriceVersionEntity {
    @Id
    public String priceVersionId;
    public String skuKey;
    @Enumerated(EnumType.STRING)
    public UsageType unit;
    public long pricePerUnitMinor;
    public String currency;
    public long version;
    public Instant effectiveFrom;
    public Instant effectiveTo;
    public String status;
}
