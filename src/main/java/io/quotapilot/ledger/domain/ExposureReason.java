package io.quotapilot.ledger.domain;

/**
 * [M0] 敞口产生原因：TIMEOUT=超时结果未知，DISCONNECT=客户端断连，SUPPLIER_UNKNOWN=已发出但结果未知。
 */
public enum ExposureReason {
    TIMEOUT, DISCONNECT, SUPPLIER_UNKNOWN
}
