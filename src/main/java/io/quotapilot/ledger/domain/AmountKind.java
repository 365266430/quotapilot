package io.quotapilot.ledger.domain;

/**
 * [M0/P1] 金额语义标注：estimate=预留估计值，actual=结算实际值，adjustment=对账修正值。
 * 三种语义的数据禁止混用同一字段，流水上必须带此标注。
 */
public enum AmountKind {
    ESTIMATE, ACTUAL, ADJUSTMENT
}
