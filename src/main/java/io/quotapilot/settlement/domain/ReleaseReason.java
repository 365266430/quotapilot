package io.quotapilot.settlement.domain;

/**
 * [M4] 释放原因。所有转移必须记录 reason + evidence（§5.1）。
 */
public enum ReleaseReason {
    FAILED,            // 供应商调用失败（已发出，结果未知 → externalRisk=true）
    CANCELLED,         // 调用方取消（未发出 → externalRisk=false；已发出 → true）
    DISCONNECTED,      // 客户端断连（上游可能继续计费 → externalRisk=true，Q6）
    TIMEOUT,           // 客户端超时/预留到期（结果未知 → externalRisk=true）
    PRE_DISPATCH_ERROR // 发出前失败（确定未触达供应商 → externalRisk=false）
}
