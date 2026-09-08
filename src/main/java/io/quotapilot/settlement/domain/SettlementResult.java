package io.quotapilot.settlement.domain;

/**
 * [M4] 结算结果。
 */
public record SettlementResult(String requestId, String status, long actualCostMinor, long chargedMinor,
                               long refundMinor, boolean overReserve, boolean duplicate, boolean adjusted) {

    public static SettlementResult settled(String requestId, long cost, long refund, boolean overReserve) {
        return new SettlementResult(requestId, "SETTLED", cost, cost, refund, overReserve, false, false);
    }

    public static SettlementResult duplicate(String requestId, String status, long charged, long refund) {
        return new SettlementResult(requestId, status, charged, charged, refund, false, true, false);
    }

    public static SettlementResult adjusted(String requestId, long cost) {
        return new SettlementResult(requestId, "SETTLED", cost, cost, 0, false, false, true);
    }
}
