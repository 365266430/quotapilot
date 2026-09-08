package io.quotapilot.alert.domain;

/**
 * [M9] 告警类型（内置规则，阈值可配）。
 */
public enum AlertType {
    AVAILABLE_LOW,        // 可用额度低于 X%
    OVER_RESERVE,         // 单请求结算超预留（actual > estimate）
    RECONCILE_GAP,        // 对账缺口/敞口封顶关闭
    EXPOSURE_BACKLOG,     // 敞口数量积压
    HOLD_LEAK             // 预留泄漏（hold 到期未收敛）
}
