package io.quotapilot.pricing.domain;

import java.util.List;
import java.util.Optional;

/**
 * [M2] 价格版本存储端口。
 */
public interface PriceVersionPort {

    void save(PriceVersion version);

    Optional<PriceVersion> findById(String priceVersionId);

    List<PriceVersion> findBySku(String skuKey);
}
