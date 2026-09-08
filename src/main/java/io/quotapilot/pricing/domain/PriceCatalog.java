package io.quotapilot.pricing.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * [M2] 价格目录（纯领域逻辑）：
 * - resolve(sku, at)：解析 at 时刻生效的价格版本快照；
 * - publish：发布新版本，自动关闭前一版本窗口（旧版本保留可查，用于审计回溯）。
 */
public class PriceCatalog {

    private final PriceVersionPort port;

    public PriceCatalog(PriceVersionPort port) {
        this.port = port;
    }

    public Optional<PriceSnapshot> resolve(Sku sku, Instant at) {
        return port.findBySku(sku.key()).stream()
                .filter(v -> v.covers(at))
                .max(Comparator.comparing(PriceVersion::getEffectiveFrom))
                .map(v -> new PriceSnapshot(v.getPriceVersionId(), v.getSkuKey(), v.getUnit(),
                        v.getPricePerUnitMinor(), v.getCurrency(), v.getVersion(), v.getEffectiveFrom()));
    }

    public Optional<PriceVersion> loadVersion(String priceVersionId) {
        return port.findById(priceVersionId);
    }

    /** 列出 SKU 的全部版本（审计/回溯，规范 M2：新旧版本都可查询）。 */
    public List<PriceVersion> loadVersions(Sku sku) {
        return port.findBySku(sku.key());
    }

    /**
     * 发布新版本：version 自增；若前一版本窗口覆盖新版本生效点则在该点关闭（新旧并存、窗口不重叠）。
     */
    public PriceSnapshot publish(Sku sku, long pricePerUnitMinor, String currency, Instant effectiveFrom) {
        if (pricePerUnitMinor <= 0) {
            throw new IllegalArgumentException("单价必须为正");
        }
        List<PriceVersion> existing = port.findBySku(sku.key());
        long nextVersion = existing.stream().mapToLong(PriceVersion::getVersion).max().orElse(0) + 1;
        // 关闭被新版本覆盖的旧版本窗口：closeAt 后必须回写持久化，保证审计/回溯口径正确
        existing.stream()
                .filter(v -> v.getEffectiveTo() == null || v.getEffectiveTo().isAfter(effectiveFrom))
                .filter(v -> !v.getEffectiveFrom().isAfter(effectiveFrom))
                .forEach(v -> {
                    v.closeAt(effectiveFrom);
                    port.save(v);
                });
        PriceVersion created = new PriceVersion(null, sku.key(), sku.usageType(), pricePerUnitMinor, currency,
                nextVersion, effectiveFrom, null);
        port.save(created);
        return new PriceSnapshot(created.getPriceVersionId(), sku.key(), sku.usageType(), pricePerUnitMinor,
                currency, nextVersion, effectiveFrom);
    }
}
