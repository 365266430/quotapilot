package io.quotapilot.pricing.domain;

/**
 * [M2] SKU = 模型 + 用量类型。
 */
public record Sku(String model, UsageType usageType) {

    public String key() {
        return model + "|" + usageType.name();
    }

    public static Sku parse(String key) {
        int i = key.lastIndexOf('|');
        return new Sku(key.substring(0, i), UsageType.valueOf(key.substring(i + 1)));
    }
}
