package io.quotapilot.metering.domain;

import io.quotapilot.common.DomainExceptions;
import io.quotapilot.common.TimeService;
import io.quotapilot.settlement.domain.SettlementResult;
import io.quotapilot.settlement.domain.SettlementService;

/**
 * [M8] 供应商用量回调摄入：幂等入库 → 触发结算（迟到回调在宽限期内正常结算，Q2）。
 * 重复回调（requestId+source+seq 已存在）安全丢弃并返回 DUPLICATE（P6/Q3）。
 */
public class CallbackService {

    private final UsageEventPort usageEvents;
    private final SettlementService settlementService;
    private final TimeService time;

    public CallbackService(UsageEventPort usageEvents, SettlementService settlementService, TimeService time) {
        this.usageEvents = usageEvents;
        this.settlementService = settlementService;
        this.time = time;
    }

    public enum Status { ACCEPTED, DUPLICATE, UNMATCHED }

    public record CallbackResult(String requestId, Status status, String settlementStatus) {}

    public record CallbackPayload(String requestId, String supplierRequestId, long units, long seq,
                                  String traceId) {}

    public CallbackResult ingest(String supplierName, CallbackPayload payload) {
        boolean inserted = usageEvents.record(new UsageEvent(null, payload.requestId(), payload.supplierRequestId(),
                null, supplierName + "|TOKEN", "TOKEN", payload.units(), time.now(),
                UsageSource.SUPPLIER_CALLBACK, payload.seq(), payload.traceId()));
        if (!inserted) {
            return new CallbackResult(payload.requestId(), Status.DUPLICATE, null);
        }
        try {
            SettlementResult settled = settlementService.settle(payload.requestId(), payload.units());
            return new CallbackResult(payload.requestId(), Status.ACCEPTED, settled.status());
        } catch (DomainExceptions.NotFound e) {
            // 供应商计费但本地无预留：事件已留存，由 M5 对账产出修正流水
            return new CallbackResult(payload.requestId(), Status.UNMATCHED, null);
        }
    }
}
