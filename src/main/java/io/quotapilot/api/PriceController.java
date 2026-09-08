package io.quotapilot.api;

import java.time.Instant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.quotapilot.pricing.domain.PriceCatalog;
import io.quotapilot.pricing.domain.PriceSnapshot;
import io.quotapilot.pricing.domain.PriceVersion;
import io.quotapilot.pricing.domain.Sku;
import io.quotapilot.pricing.domain.UsageType;

/** [M2] 价格版本 API：POST /v1/prices/versions（发布新版本，旧版本并存可查）。 */
@RestController
public class PriceController {

    private final PriceCatalog priceCatalog;

    public PriceController(PriceCatalog priceCatalog) {
        this.priceCatalog = priceCatalog;
    }

    public record PublishReq(String model, String usageType, long pricePerUnitMinor, String currency,
                             Instant effectiveFrom) {}

    @PostMapping("/v1/prices/versions")
    public PriceSnapshot publish(@RequestBody PublishReq req) {
        Sku sku = new Sku(req.model(), UsageType.valueOf(req.usageType() == null ? "TOKEN" : req.usageType()));
        return priceCatalog.publish(sku, req.pricePerUnitMinor(),
                req.currency() == null ? "CNY" : req.currency(),
                req.effectiveFrom() == null ? Instant.now() : req.effectiveFrom());
    }

    @GetMapping("/v1/prices")
    public java.util.List<PriceVersion> list(@org.springframework.web.bind.annotation.RequestParam("model") String model,
                                             @org.springframework.web.bind.annotation.RequestParam(name = "usageType", defaultValue = "TOKEN") String usageType) {
        return priceCatalog.loadVersions(new Sku(model, UsageType.valueOf(usageType)));
    }
}
