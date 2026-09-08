package io.quotapilot.gateway.domain;

/**
 * [M6] 网关请求（V1.1：supplier 字段选择适配器，null=mock）。
 */
public record GatewayRequest(String requestId, String userId, String teamId, String taskId, String model,
                             Long declaredEstimatedUnits, String payloadJson, boolean reserveOnly,
                             long ttlSeconds, String traceId, String supplier) {

    /** 兼容 V1 调用方签名（supplier 默认 mock）。 */
    public GatewayRequest(String requestId, String userId, String teamId, String taskId, String model,
                          Long declaredEstimatedUnits, String payloadJson, boolean reserveOnly,
                          long ttlSeconds, String traceId) {
        this(requestId, userId, teamId, taskId, model, declaredEstimatedUnits, payloadJson, reserveOnly,
                ttlSeconds, traceId, null);
    }
}
