package io.quotapilot.supplier.domain;

/**
 * [M10] 供应商调用异常。关键语义：
 * - dispatched=true：请求可能已到达供应商，费用情况未知 → 必须走敞口（Q6/Q8，不得假定未计费）；
 * - dispatched=false：确定未发出（如连接建立失败），可安全释放；
 * - partialUnits > 0：流中断前已观测到的部分用量（尽力计价依据）。
 */
public class SupplierCallException extends RuntimeException {

    private final boolean dispatched;
    private final long partialUnits;

    public SupplierCallException(String message, boolean dispatched, long partialUnits, Throwable cause) {
        super(message, cause);
        this.dispatched = dispatched;
        this.partialUnits = partialUnits;
    }

    public boolean isDispatched() {
        return dispatched;
    }

    public long getPartialUnits() {
        return partialUnits;
    }
}
