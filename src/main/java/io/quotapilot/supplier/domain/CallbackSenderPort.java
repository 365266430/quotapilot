package io.quotapilot.supplier.domain;

/**
 * [M10] 供应商用量回调发送端口：MockSupplier 模拟「供应商回调 POST /v1/callbacks/{supplier}」。
 * 实现方在进程内直接投递到 M8 回调服务（V1 无真实出站 HTTP）。
 */
public interface CallbackSenderPort {

    /**
     * @param delayMillis 模拟迟到回调的延迟；0 = 立即投递
     */
    void sendUsageCallback(String supplierName, UsageCallbackPayload payload, long delayMillis);

    record UsageCallbackPayload(String requestId, String supplierRequestId, long units, long seq,
                                String occurredAtIso) {}
}
