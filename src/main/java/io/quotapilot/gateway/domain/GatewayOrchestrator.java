package io.quotapilot.gateway.domain;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import io.quotapilot.common.TimeService;
import io.quotapilot.ledger.domain.ExposureRepositoryPort;
import io.quotapilot.metering.domain.UsageEvent;
import io.quotapilot.metering.domain.UsageEventPort;
import io.quotapilot.metering.domain.UsageSource;
import io.quotapilot.reserve.domain.ReserveCommand;
import io.quotapilot.reserve.domain.ReserveResult;
import io.quotapilot.reserve.domain.ReservationEngine;
import io.quotapilot.settlement.domain.ReleaseReason;
import io.quotapilot.settlement.domain.SettlementResult;
import io.quotapilot.settlement.domain.SettlementService;
import io.quotapilot.supplier.domain.StreamedResponse;
import io.quotapilot.supplier.domain.SupplierCallException;
import io.quotapilot.supplier.domain.SupplierRegistry;
import io.quotapilot.supplier.domain.SupplierSpi;

/**
 * [M6] 请求网关编排（V1.1：多供应商注册表 + 流式读取语义）。
 *
 * 时序：预留（M3）→ 构造 holdContext 调用供应商（M10）→ 读取流式响应 → 解析实际用量 → 结算（M4）。
 * 失败语义（P2/Q6 诚实计量）：
 * - 确定未发出（dispatched=false）→ 安全释放，不写敞口；
 * - 已发出但结果未知（含响应流读取中断）→ 释放 + ExposureRecord(PENDING) 等待对账；
 * - 结算环节自身异常不释放（结算幂等可重试），交由人工/回调/对账收敛。
 */
public class GatewayOrchestrator {

    private final ReservationEngine engine;
    private final SettlementService settlementService;
    private final SupplierRegistry registry;
    private final UsageEventPort usageEvents;
    private final ExposureRepositoryPort exposureRepo;
    private final TimeService time;
    private final io.quotapilot.ratelimit.domain.RateLimiter rateLimiter;

    public GatewayOrchestrator(ReservationEngine engine, SettlementService settlementService,
                               SupplierRegistry registry, UsageEventPort usageEvents,
                               ExposureRepositoryPort exposureRepo, TimeService time,
                               io.quotapilot.ratelimit.domain.RateLimiter rateLimiter) {
        this.engine = engine;
        this.settlementService = settlementService;
        this.registry = registry;
        this.usageEvents = usageEvents;
        this.exposureRepo = exposureRepo;
        this.time = time;
        this.rateLimiter = rateLimiter;
    }

    public GatewayResult execute(GatewayRequest req) {
        String traceId = req.traceId() == null ? UUID.randomUUID().toString() : req.traceId();
        String requestId = req.requestId() == null ? UUID.randomUUID().toString() : req.requestId();
        SupplierSpi supplier = registry.get(req.supplier());

        // 阶段一：解析额度/价格/估算（M3 prepare）；阶段二前插入 M7 限流（额度管钱、限流管速率）
        ReservationEngine.Prepared prepared = engine.prepare(new ReserveCommand(requestId,
                new ReserveCommand.ScopeValues(req.userId(), req.teamId(), req.taskId()),
                req.model(), "TOKEN", req.declaredEstimatedUnits(), req.ttlSeconds(), traceId));
        rateLimiter.check(prepared.account().accountId(), prepared.units(), prepared.estimateMinor());
        ReserveResult reserve = engine.reserve(prepared);
        if (req.reserveOnly()) {
            return new GatewayResult(requestId, reserve.holdId(), reserve.accountId(), GatewayResult.RESERVED,
                    null, null, 0, 0, 0, null, reserve.duplicate(), traceId);
        }

        SupplierSpi.ActualUsage actual;
        try (StreamedResponse response = supplier.call(
                new SupplierSpi.SupplierCallRequest(requestId, req.model(), reserve.estimatedUnits(), req.payloadJson()),
                new SupplierSpi.HoldContext(requestId, reserve.holdId(), reserve.accountId(),
                        reserve.priceVersionId(), traceId))) {
            String body;
            try {
                body = new String(response.readAll(), StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                // 响应已发出但读取中断：结果未知，必须按「可能已计费」处理（Q6）
                throw new SupplierCallException("read response stream failed: " + e.getMessage(), true, 0, e);
            }
            actual = supplier.parseUsage(body);
        } catch (SupplierCallException e) {
            return handleSupplierFailure(req, requestId, reserve, prepared, traceId, e);
        }

        recordUsage(supplier.name(), requestId, actual.supplierRequestId(), reserve.accountId(), req.model(),
                actual.usageUnits(), traceId);
        SettlementResult settled = settlementService.settle(requestId, actual.usageUnits());
        // M7：用量型限流按实际用量返还/补扣（尽力而为）
        rateLimiter.onSettled(reserve.accountId(), actual.usageUnits(), settled.chargedMinor(),
                prepared.units(), prepared.estimateMinor());
        return new GatewayResult(requestId, reserve.holdId(), reserve.accountId(), GatewayResult.SUCCEEDED, null,
                actual.supplierRequestId(), actual.usageUnits(), settled.chargedMinor(), settled.refundMinor(),
                null, reserve.duplicate(), traceId);
    }

    private GatewayResult handleSupplierFailure(GatewayRequest req, String requestId, ReserveResult reserve,
                                                ReservationEngine.Prepared prepared, String traceId,
                                                SupplierCallException e) {
        boolean dispatched = e.isDispatched() || e.getPartialUnits() > 0;
        if (e.getPartialUnits() > 0) {
            // 流中断：已观测的部分用量先入明细（尽力计价依据），敞口等待对账（Q6）
            recordUsage(registry.get(req.supplier()).name(), requestId, null, reserve.accountId(), req.model(),
                    e.getPartialUnits(), traceId);
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
        // M7：释放路径全额返还预留扣减（实际用量由供应商回调/对账另行结算）
        rateLimiter.onReleased(reserve.accountId(), prepared.units(), prepared.estimateMinor());
        String exposureId = dispatched
                ? exposureRepo.findByRequestId(requestId).map(exp -> exp.getExposureId()).orElse(null)
                : null;
        return new GatewayResult(requestId, reserve.holdId(), reserve.accountId(), GatewayResult.RELEASED,
                reason.name(), null, e.getPartialUnits(), 0, released.refundMinor(), exposureId,
                reserve.duplicate(), traceId);
    }

    private void recordUsage(String supplierName, String requestId, String supplierRequestId, String accountId,
                             String model, long units, String traceId) {
        UsageSource source = "openai".equals(supplierName) ? UsageSource.OPENAI : UsageSource.MOCK;
        usageEvents.record(new UsageEvent(null, requestId, supplierRequestId, accountId, model + "|TOKEN", "TOKEN",
                units, time.now(), source, 0, traceId));
    }
}
