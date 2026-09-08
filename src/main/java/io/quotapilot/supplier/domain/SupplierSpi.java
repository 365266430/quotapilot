package io.quotapilot.supplier.domain;

import java.time.Instant;
import java.util.List;

/**
 * [M10] 供应商适配器 SPI —— 全部供应商必须实现的统一接口。
 * 新增供应商只需实现本接口，不改动 M3~M9（依赖倒置）。
 * call 返回 StreamedResponse（流式语义）；close() = 尽力取消上游（Q6）。
 */
public interface SupplierSpi {

    String name();

    /** 估计用量或声明上限（预留估算输入）。 */
    EstimatedUsage estimateUsage(String model);

    /** 实际调用（流式）。失败抛 SupplierCallException。返回的 StreamedResponse 必须被 close()。 */
    StreamedResponse call(SupplierCallRequest request, HoldContext holdContext) throws SupplierCallException;

    /** 从响应体解析实际用量：兼容非流式 JSON（含 usage 字段）与已累积的 SSE 文本。 */
    ActualUsage parseUsage(String responseBody);

    /** 拉/推实际用量记录（对账数据源；无拉取能力的供应商返回空并由账单导入对账）。 */
    List<UsageRecord> subscribeUsage(String requestId);

    record EstimatedUsage(long estimatedUnits, boolean declared) {}

    record HoldContext(String requestId, String holdId, String accountId, String priceVersionId, String traceId) {}

    record SupplierCallRequest(String requestId, String model, long maxUnits, String payloadJson) {}

    record ActualUsage(String supplierRequestId, long usageUnits, boolean completed) {}

    record UsageRecord(String requestId, String supplierRequestId, String accountId, long units, long seq,
                       Instant occurredAt) {}
}
