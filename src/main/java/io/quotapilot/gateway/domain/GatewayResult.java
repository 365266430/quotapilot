package io.quotapilot.gateway.domain;

/**
 * [M6] 网关执行结果。
 */
public record GatewayResult(String requestId, String holdId, String accountId, String status, String releaseReason,
                            String supplierRequestId, long usageUnits, long chargedMinor, long refundMinor,
                            String exposureId, boolean duplicate, String traceId) {

    public static final String RESERVED = "RESERVED";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String RELEASED = "RELEASED";
}
