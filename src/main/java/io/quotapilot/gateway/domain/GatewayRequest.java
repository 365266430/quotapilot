package io.quotapilot.gateway.domain;

/**
 * [M6] 网关请求（V1：预留并调用 MockSupplier；V1.1 扩展流式代理）。
 */
public record GatewayRequest(String requestId, String userId, String teamId, String taskId, String model,
                             Long declaredEstimatedUnits, String payloadJson, boolean reserveOnly,
                             long ttlSeconds, String traceId) {}
