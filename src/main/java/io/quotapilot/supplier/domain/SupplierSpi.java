package io.quotapilot.supplier.domain;

import java.time.Instant;
import java.util.List;

/**
 * [M10] 供应商适配器 SPI —— 全部供应商必须实现的统一接口。
 * 新增供应商只需实现本接口，不改动 M3~M9（依赖倒置）。
 */
public interface SupplierSpi {

    String name();

    /** 估计用量或声明上限（预留估算输入）。 */
    EstimatedUsage estimateUsage(String model);

    /** 实际调用（流式语义；V1 MockSupplier 为同步模拟）。失败抛 SupplierCallException。 */
    SupplierResponse call(SupplierCallRequest request, HoldContext holdContext) throws SupplierCallException;

    /** 从响应/事件解析实际用量。 */
    ActualUsage parseUsage(SupplierResponse response);

    /** 拉/推实际用量记录（对账数据源）。 */
    List<UsageRecord> subscribeUsage(String requestId);

    record EstimatedUsage(long estimatedUnits, boolean declared) {}

    record HoldContext(String requestId, String holdId, String accountId, String priceVersionId, String traceId) {}

    record SupplierCallRequest(String requestId, String model, long maxUnits, String payloadJson) {}

    record SupplierResponse(String supplierRequestId, String model, long usageUnits, boolean completed,
                            String rawPayloadJson) {}

    record ActualUsage(String supplierRequestId, long usageUnits, boolean completed) {}

    record UsageRecord(String requestId, String supplierRequestId, String accountId, long units, long seq,
                       Instant occurredAt) {}
}
