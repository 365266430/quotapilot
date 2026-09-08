package io.quotapilot.infra.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.quotapilot.pricing.domain.PriceVersion;
import io.quotapilot.pricing.domain.PriceVersionPort;

/** [M2] 价格版本适配器（新旧版本并存，不物理删除）。 */
@Component
public class PriceVersionAdapter implements PriceVersionPort {

    private final PriceVersionJpaRepo repo;

    public PriceVersionAdapter(PriceVersionJpaRepo repo) {
        this.repo = repo;
    }

    @Override
    @Transactional
    public void save(PriceVersion version) {
        PriceVersionEntity e = new PriceVersionEntity();
        e.priceVersionId = version.getPriceVersionId();
        e.skuKey = version.getSkuKey();
        e.unit = version.getUnit();
        e.pricePerUnitMinor = version.getPricePerUnitMinor();
        e.currency = version.getCurrency();
        e.version = version.getVersion();
        e.effectiveFrom = version.getEffectiveFrom();
        e.effectiveTo = version.getEffectiveTo();
        e.status = version.getStatus().name();
        repo.save(e);
    }

    @Override
    public Optional<PriceVersion> findById(String priceVersionId) {
        return repo.findById(priceVersionId).map(PriceVersionAdapter::toDomain);
    }

    @Override
    public List<PriceVersion> findBySku(String skuKey) {
        return repo.findBySkuKey(skuKey).stream().map(PriceVersionAdapter::toDomain).toList();
    }

    static PriceVersion toDomain(PriceVersionEntity e) {
        return new PriceVersion(e.priceVersionId, e.skuKey, e.unit, e.pricePerUnitMinor, e.currency, e.version,
                e.effectiveFrom, e.effectiveTo);
    }
}
