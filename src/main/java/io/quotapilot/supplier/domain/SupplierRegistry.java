package io.quotapilot.supplier.domain;

import java.util.Map;
import java.util.Optional;

/**
 * [M10] 供应商注册表：按名称选择适配器（新增供应商只需注册，不改动 M3~M9）。
 */
public class SupplierRegistry {

    private final Map<String, SupplierSpi> suppliers;

    public SupplierRegistry(Map<String, SupplierSpi> suppliers) {
        this.suppliers = Map.copyOf(suppliers);
    }

    public SupplierSpi get(String name) {
        return Optional.ofNullable(suppliers.get(name == null ? "mock" : name))
                .orElseThrow(() -> new IllegalArgumentException("UNKNOWN_SUPPLIER: " + name));
    }

    public java.util.Set<String> names() {
        return suppliers.keySet();
    }
}
