package io.quotapilot.ledger.domain;

/**
 * [M0] 账本流水类型（不可变流水的方向语义）。
 * HOLD=预留在途，SETTLE=实际结算，RELEASE=释放退回，ADJUST=对账修正（可正可负）。
 */
public enum LedgerEntryType {
    HOLD, SETTLE, RELEASE, ADJUST
}
