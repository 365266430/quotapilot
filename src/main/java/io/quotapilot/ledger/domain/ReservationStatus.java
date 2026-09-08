package io.quotapilot.ledger.domain;

/**
 * [M0/§5.1] 预留状态机：RESERVED → SETTLED（正常结算）/ RELEASED（失败、取消、断连、超时到期）。
 * 所有转移必须记录 reason + evidence。
 */
public enum ReservationStatus {
    RESERVED, SETTLED, RELEASED
}
