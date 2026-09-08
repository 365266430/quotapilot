package io.quotapilot.gateway.domain;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quotapilot.common.TimeService;
import io.quotapilot.metering.domain.UsageEvent;
import io.quotapilot.metering.domain.UsageEventPort;
import io.quotapilot.metering.domain.UsageSource;
import io.quotapilot.reserve.domain.ReservationEngine;
import io.quotapilot.settlement.domain.ReleaseReason;
import io.quotapilot.settlement.domain.SettlementService;
import io.quotapilot.supplier.domain.StreamedResponse;
import io.quotapilot.supplier.domain.SupplierCallException;
import io.quotapilot.supplier.domain.SupplierRegistry;
import io.quotapilot.supplier.domain.SupplierSpi;

/**
 * [M6/V1.1] 流式代理：SSE 逐行透传 + 客户端断连检测。
 * 断连检测语义（规范 M6/Q6，必须遵守）：
 * 1. 客户端断开（写回失败）→ 尽力取消上游（close 上游流），但**不得假定上游停止计费**；
 * 2. 立即按「已发生未知用量」释放预留并转 ExposureRecord(PENDING)，等待供应商回调对账；
 * 3. 上游流中断（读到 IOException）→ 结果未知 → 同样释放 + 敞口。
 * 已观测到的部分用量（SSE usage 块）先入明细作为尽力计价依据。
 */
public class StreamingProxyService {

    /** 客户端断连信号（内部用，向上传播以终止 Servlet 写回）。 */
    public static final class ClientAbortException extends RuntimeException {
        public ClientAbortException(Throwable cause) {
            super("client aborted stream", cause);
        }
    }

    private final ReservationEngine engine;
    private final io.quotapilot.ratelimit.domain.RateLimiter rateLimiter;
    private final SupplierRegistry registry;
    private final SettlementService settlementService;
    private final UsageEventPort usageEvents;
    private final TimeService time;
    private final ObjectMapper json = new ObjectMapper();

    public StreamingProxyService(ReservationEngine engine, io.quotapilot.ratelimit.domain.RateLimiter rateLimiter,
                                 SupplierRegistry registry, SettlementService settlementService,
                                 UsageEventPort usageEvents, TimeService time) {
        this.engine = engine;
        this.rateLimiter = rateLimiter;
        this.registry = registry;
        this.settlementService = settlementService;
        this.usageEvents = usageEvents;
        this.time = time;
    }

    /** 预留 + 限流 + 打开上游流（任何失败以领域异常抛出，由 HTTP 层映射）。 */
    public StreamExecution open(GatewayRequest req) {
        String traceId = req.traceId() == null ? UUID.randomUUID().toString() : req.traceId();
        String requestId = req.requestId() == null ? UUID.randomUUID().toString() : req.requestId();
        SupplierSpi supplier = registry.get(req.supplier());
        ReservationEngine.Prepared prepared = engine.prepare(new io.quotapilot.reserve.domain.ReserveCommand(
                requestId, new io.quotapilot.reserve.domain.ReserveCommand.ScopeValues(req.userId(), req.teamId(),
                req.taskId()), req.model(), "TOKEN", req.declaredEstimatedUnits(), req.ttlSeconds(), traceId));
        rateLimiter.check(prepared.account().accountId(), prepared.units(), prepared.estimateMinor());
        var reserve = engine.reserve(prepared);
        try {
            StreamedResponse upstream = supplier.call(
                    new SupplierSpi.SupplierCallRequest(requestId, req.model(), reserve.estimatedUnits(),
                            req.payloadJson()),
                    new SupplierSpi.HoldContext(requestId, reserve.holdId(), reserve.accountId(),
                            reserve.priceVersionId(), traceId));
            return new StreamExecution(requestId, traceId, supplier, prepared, reserve, upstream);
        } catch (SupplierCallException e) {
            handleFailure(requestId, prepared, e);
            throw e;
        }
    }

    public record StreamExecution(String requestId, String traceId, SupplierSpi supplier,
                                  ReservationEngine.Prepared prepared, io.quotapilot.reserve.domain.ReserveResult reserve,
                                  StreamedResponse upstream) {}

    /**
     * 逐行透传上游 SSE 到客户端，结束时按 usage 结算并写 quotapilot 元事件。
     * 客户端断开/上游中断均收敛到正确的结算/敞口分支（见类注释）。
     */
    public GatewayResult pump(StreamExecution exec, OutputStream client) {
        long usageSeen = 0;
        String supplierRequestId = null;
        boolean sawUsage = false;
        boolean sawDone = false;
        boolean finished = false;
        try (StreamedResponse upstream = exec.upstream()) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(upstream.body(), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if ("[DONE]".equals(line.trim())) {
                        sawDone = true;
                    }
                    Parsed parsed = parseLine(line);
                    if (parsed.supplierRequestId() != null && supplierRequestId == null) {
                        supplierRequestId = parsed.supplierRequestId();
                    }
                    if (parsed.usageTokens() != null) {
                        usageSeen = parsed.usageTokens();
                        sawUsage = true;
                    }
                    try {
                        client.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                        // SSE 注释行探测写：客户端 RST 后第一次 send 可能被 TCP 静默吞掉，
                        // 追加一次立即 flush 的探测写使断连在下一拍必然显形（EventSource 客户端忽略注释行）
                        client.write(": ka\n\n".getBytes(StandardCharsets.UTF_8));
                        client.flush();
                    } catch (IOException e) {
                        // Q6：客户端断开 —— 尽力取消上游（try-with-resources close）+ 转敞口，不假定上游停止计费
                        if (usageSeen > 0) {
                            recordUsage(exec.supplier().name(), exec.requestId(), supplierRequestId,
                                    exec.reserve().accountId(), exec.prepared(), usageSeen, exec.traceId());
                        }
                        settlementService.release(exec.requestId(), ReleaseReason.DISCONNECTED, true);
                        rateLimiter.onReleased(exec.reserve().accountId(), exec.prepared().units(),
                                exec.prepared().estimateMinor());
                        finished = true;
                        throw new ClientAbortException(e);
                    }
                }
            } catch (ClientAbortException rethrown) {
                throw rethrown;
            } catch (IOException upstreamDown) {
                // 上游流异常中断：结果未知（已发出），转敞口等待对账
                if (usageSeen > 0) {
                    recordUsage(exec.supplier().name(), exec.requestId(), supplierRequestId,
                            exec.reserve().accountId(), exec.prepared(), usageSeen, exec.traceId());
                }
                settlementService.release(exec.requestId(), ReleaseReason.TIMEOUT, true);
                rateLimiter.onReleased(exec.reserve().accountId(), exec.prepared().units(),
                        exec.prepared().estimateMinor());
                finished = true;
                return releasedResult(exec, ReleaseReason.TIMEOUT.name(), supplierRequestId, usageSeen);
            }
            // EOF：按协议语义判定流是否正常结束——OpenAI 兼容流正常收尾必有 usage 块或 [DONE]；
            // 两者皆无 = 上游流提前中断（结果未知，P2/Q6 诚实口径）→ 释放 + 敞口，不得按 0 结算假装无事
            if (!sawUsage && !sawDone) {
                settlementService.release(exec.requestId(), ReleaseReason.TIMEOUT, true);
                rateLimiter.onReleased(exec.reserve().accountId(), exec.prepared().units(),
                        exec.prepared().estimateMinor());
                finished = true;
                return releasedResult(exec, ReleaseReason.TIMEOUT.name(), supplierRequestId, usageSeen);
            }
            // 上游正常结束：按 usage 结算（usage=0 亦如实结算）
            recordUsage(exec.supplier().name(), exec.requestId(), supplierRequestId, exec.reserve().accountId(),
                    exec.prepared(), usageSeen, exec.traceId());
            var settled = settlementService.settle(exec.requestId(), usageSeen);
            rateLimiter.onSettled(exec.reserve().accountId(), usageSeen, settled.chargedMinor(),
                    exec.prepared().units(), exec.prepared().estimateMinor());
            finished = true;
            try {
                client.write(("data: {\"quotapilot\":{\"requestId\":\"" + exec.requestId()
                        + "\",\"status\":\"" + settled.status() + "\",\"chargedMinor\":" + settled.chargedMinor()
                        + "}}\n\n").getBytes(StandardCharsets.UTF_8));
                client.flush();
            } catch (IOException ignored) {
                // 元事件写回失败不影响已完成的正确结算
            }
            return new GatewayResult(exec.requestId(), exec.reserve().holdId(), exec.reserve().accountId(),
                    GatewayResult.SUCCEEDED, null, supplierRequestId, usageSeen, settled.chargedMinor(),
                    settled.refundMinor(), null, exec.reserve().duplicate(), exec.traceId());
        } finally {
            if (!finished) {
                // 异常路径兜底释放（如不可预期 RuntimeException）
                try {
                    settlementService.release(exec.requestId(), ReleaseReason.FAILED, true);
                } catch (RuntimeException ignored) {
                    // 幂等：已释放/已结算时静默
                }
            }
        }
    }

    private GatewayResult releasedResult(StreamExecution exec, String reason, String supplierRequestId,
                                         long partialUnits) {
        return new GatewayResult(exec.requestId(), exec.reserve().holdId(), exec.reserve().accountId(),
                GatewayResult.RELEASED, reason, supplierRequestId, partialUnits, 0, 0, null, false, exec.traceId());
    }

    private void handleFailure(String requestId, ReservationEngine.Prepared prepared, SupplierCallException e) {
        boolean risk = e.isDispatched() || e.getPartialUnits() > 0;
        ReleaseReason reason = e.isDispatched() ? ReleaseReason.TIMEOUT : ReleaseReason.PRE_DISPATCH_ERROR;
        try {
            settlementService.release(requestId, reason, risk);
            if (risk) {
                rateLimiter.onReleased(prepared.account().accountId(), prepared.units(), prepared.estimateMinor());
            }
        } catch (RuntimeException ignored) {
            // 释放幂等冲突（如回调已先结算）由对账兜底
        }
    }

    private void recordUsage(String supplierName, String requestId, String supplierRequestId, String accountId,
                             ReservationEngine.Prepared prepared, long units, String traceId) {
        UsageSource source = "openai".equals(supplierName) ? UsageSource.OPENAI : UsageSource.MOCK;
        usageEvents.record(new UsageEvent(null, requestId, supplierRequestId, accountId,
                prepared.snapshot().skuKey(), "TOKEN", units, time.now(), source, 0, traceId));
    }

    private Parsed parseLine(String line) {
        if (!line.startsWith("data:")) {
            return new Parsed(null, null);
        }
        String payload = line.substring(5).trim();
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return new Parsed(null, null);
        }
        try {
            JsonNode node = json.readTree(payload);
            Long usage = node.has("usage") && node.get("usage").has("total_tokens")
                    ? node.get("usage").get("total_tokens").asLong() : null;
            String id = node.hasNonNull("id") ? node.get("id").asText() : null;
            return new Parsed(id, usage);
        } catch (IOException e) {
            return new Parsed(null, null);
        }
    }

    private record Parsed(String supplierRequestId, Long usageTokens) {}
}
