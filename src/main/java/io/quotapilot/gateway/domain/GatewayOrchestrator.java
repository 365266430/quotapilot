package io.quotapilot.gateway.domain;

import java.util.UUID;

import io.quotapilot.common.TimeService;
import io.quotapilot.ledger.domain.ExposureReason;
import io.quotapilot.metering.domain.UsageEvent;
import io.quotapilot.metering.domain.UsageEventPort;
import io.quotapilot.metering.domain.UsageSource;
import io.quotapilot.reserve.domain.ReserveCommand;
import io.quotapilot.reserve.domain.ReserveResult;
import io.quotapilot.reserve.domain.ReservationEngine;
import io.quotapilot.settlement.domain.ReleaseReason;
import io.quotapilot.settlement.domain.SettlementResult;
import io.quotapilot.settlement.domain.SettlementService;
import io.quotapilot.supplier.domain.SupplierCallException;
import io.quotapilot.supplier.domain.SupplierSpi;

/**
 * [M6] 请求网关编排（V1：同步调用 MockSupplier；V1.1 扩展流式代理 + 客户端断连检测）。
 *
 * 时序：预留（M3）→ 构造 holdContext 调用供应商（M10）→ 解析实际用量 → 结算（M4）。
 * 失败语义（P2/Q6 诚实计量）：
 * - 确定未发出（dispatched=false）→ 安全释放，不写敞口；
 * - 已发出但结果未知 / 流中断 → 释放 + ExposureRecord(PENDING) 等待对账；
 * - 结算环节自身异常不释放（结算幂等可重试），交由人工/回调/对账收敛。
 */
public class GatewayOrchestrator {

    private final ReservationEngine engine;
    private final SettlementService settlementService;
    private final SupplierSpi supplier;
    private final UsageEventPort usageEvents;
    private final io.quotapilot.ledger.domain.ExposureRepositoryPort exposureRepo;
    private final TimeService time;

    public GatewayOrchestrator(ReservationEngine engine, SettlementService settlementService, SupplierSpi supplier,
                               UsageEventPort usageEvents, io.quotapilot.ledger.domain.ExposureRepositoryPort exposureRepo,
                               TimeService time) {
        this.engine = engine;
        this.settlementService = settlementService;
        this.supplier = supplier;
        this.usageEvents = usageEvents;
        this.exposureRepo = exposureRepo;
        this.time = time;
    }

    public GatewayResult execute(GatewayRequest req) {
        String traceId = req.traceId() == null ? UUID.randomUUID().toString() : req.traceId();
        String requestId = req.requestId() == null ? UUID.randomUUID().toString() : req.requestId();

        ReserveResult reserve = engine.reserve(new ReserveCommand(requestId,
                new ReserveCommand.ScopeValues(req.userId(), req.teamId(), req.taskId()),
                req.model(), "TOKEN", req.declaredEstimatedUnits(), req.ttlSeconds(), traceId));
        if (req.reserveOnly()) {
            return new GatewayResult(requestId, reserve.holdId(), reserve.accountId(), GatewayResult.RESERVED,
                    null, null, 0, 0, 0, null, reserve.duplicate(), traceId);
        }

        SupplierSpi.SupplierResponse response;
        try {
            response = supplier.call(
                    new SupplierSpi.SupplierCallRequest(requestId, req.model(), reserve.estimatedUnits(), req.payloadJson()),
                    new SupplierSpi.HoldContext(requestId, reserve.holdId(), reserve.accountId(),
                            reserve.priceVersionId(), traceId));
        } catch (SupplierCallException e) {
            return handleSupplierFailure(req, requestId, reserve, traceId, e);
        }

        SupplierSpi.ActualUsage actual = supplier.parseUsage(response);
        recordUsage(requestId, response.supplierRequestId(), reserve.accountId(), req.model(),
                actual.usageUnits(), traceId);
        SettlementResult settled = settlementService.settle(requestId, actual.usageUnits());
        return new GatewayResult(requestId, reserve.holdId(), reserve.accountId(), GatewayResult.SUCCEEDED, null,
                response.supplierRequestId(), actual.usageUnits(), settled.chargedMinor(), settled.refundMinor(),
                null, reserve.duplicate(), traceId);
    }

    private GatewayResult handleSupplierFailure(GatewayRequest req, String requestId, ReserveResult reserve,
                                                String traceId, SupplierCallException e) {
        boolean dispatched = e.isDispatched() || e.getPartialUnits() > 0;
        if (e.getPartialUnits() > 0) {
            // 流中断：已观测的部分用量先入明细（尽力计价依据），敞口等待对账（Q6）
            recordUsage(requestId, null, reserve.accountId(), req.model(), e.getPartialUnits(), traceId);
        }
        ReleaseReason reason;
        if (!e.isDispatched() && e.getPartialUnits() == 0) {
            reason = ReleaseReason.PRE_DISPATCH_ERROR;
        } else if (e.getPartialUnits() > 0) {
            reason = ReleaseReason.DISCONNECTED;
        } else {
            reason = ReleaseReason.TIMEOUT; // 已发出、结果未知
        }
        SettlementResult released = settlementService.release(requestId, reason, dispatched);
        if ("SETTLED".equals(released.status())) {
            // 结算竞态获胜（回调先于超时处理到达）：请求实际已成功结算
            return new GatewayResult(requestId, reserve.holdId(), reserve.accountId(), GatewayResult.SUCCEEDED,
                    null, null, e.getPartialUnits(), released.chargedMinor(), 0, null, reserve.duplicate(), traceId);
        }
        String exposureId = dispatched
                ? exposureRepo.findByRequestId(requestId).map(exp -> exp.getExposureId()).orElse(null)
                : null;
        return new GatewayResult(requestId, reserve.holdId(), reserve.accountId(), GatewayResult.RELEASED,
                reason.name(), null, e.getPartialUnits(), 0, released.refundMinor(), exposureId,
                reserve.duplicate(), traceId);
    }

    private void recordUsage(String requestId, String supplierRequestId, String accountId, String model,
                             long units, String traceId) {
        usageEvents.record(new UsageEvent(null, requestId, supplierRequestId, accountId, model + "|TOKEN", "TOKEN",
                units, time.now(), UsageSource.MOCK, 0, traceId));
    }
}
