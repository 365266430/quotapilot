package io.quotapilot.ledger.domain;

/**
 * [M0/§5.1] 敞口状态：PENDING →（宽限期内回调到达）SETTLED / PENDING →（宽限期过）CLOSED_WITH_ADJUSTMENT。
 */
public enum ExposureState {
    PENDING, SETTLED, CLOSED_WITH_ADJUSTMENT
}
