package io.quotapilot.supplier.domain;

import java.util.List;
import java.util.Optional;

/**
 * [M10] 供应商侧账本存储端口：MockSupplier 的对外计费记录，用于 M5 对账测试（「供应商账单比本地多」场景）。
 */
public interface SupplierChargeStorePort {

    void save(SupplierCharge charge);

    Optional<SupplierCharge> findByRequestId(String requestId);

    List<SupplierCharge> listAll();

    List<SupplierCharge> findByAccount(String accountId);

    /** 同一请求的自增序号（供应商侧事件 seq）。 */
    long nextSeq(String requestId);

    record SupplierCharge(String supplierRequestId, String requestId, String accountId, String model,
                          long units, long amountMinor, long seq, java.time.Instant billedAt) {}
}
