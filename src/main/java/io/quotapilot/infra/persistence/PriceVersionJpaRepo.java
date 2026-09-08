package io.quotapilot.infra.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceVersionJpaRepo extends JpaRepository<PriceVersionEntity, String> {
    List<PriceVersionEntity> findBySkuKey(String skuKey);
}
