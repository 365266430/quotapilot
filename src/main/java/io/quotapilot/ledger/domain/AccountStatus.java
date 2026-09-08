package io.quotapilot.ledger.domain;

/**
 * [M0/M1] 账户状态。BLOCKED 为管理员硬止损开关：立即拒绝新请求（在途请求不受影响）。
 */
public enum AccountStatus {
    ACTIVE, BLOCKED
}
