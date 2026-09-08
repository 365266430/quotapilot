package io.quotapilot.supplier.mock;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.quotapilot.common.TimeService;
import io.quotapilot.supplier.domain.CallbackSenderPort;
import io.quotapilot.supplier.domain.SupplierChargeStorePort;
import io.quotapilot.supplier.domain.SupplierCallException;
import io.quotapilot.supplier.domain.SupplierSpi;

/**
 * [M10] MockSupplier（V1 必做）：固定单价 + 请求前可返回估计用量 + 可注入故障。
 * 故障类型（可按请求注入，用于确定性测试 M3/M4/M5）：
 * DELAY / FAIL_BEFORE_DISPATCH / FAIL_AFTER_DISPATCH / TIMEOUT / STREAM_INTERRUPT / USAGE_DRIFT /
 * LATE_CALLBACK / DOUBLE_CALLBACK。
 * 提供「供应商侧账本」视图（SupplierChargeStorePort），用于对账测试。
 * 定价口径：固定 0.01 元 / 1K token = 1 minorUnit（1e-5 元）/ token（与 M2 默认价格版本一致）。
 */
public class MockSupplier implements SupplierSpi {

    public static final long UNIT_PRICE_MINOR_PER_TOKEN = 1L;

    public enum Fault { NONE, DELAY, FAIL_BEFORE_DISPATCH, FAIL_AFTER_DISPATCH, TIMEOUT, STREAM_INTERRUPT,
        USAGE_DRIFT, LATE_CALLBACK, DOUBLE_CALLBACK }

    private final SupplierChargeStorePort chargeStore;
    private final CallbackSenderPort callbackSender;
    private final TimeService time;
    private final long defaultDelayMillis;
    private final long defaultDriftPercent; // 正数=用量上浮，负数=下浮（对账差额来源）

    private final Map<String, Fault> faultByRequest = new HashMap<>();
    private final Map<String, Long> driftPercentByRequest = new HashMap<>();
    private final Map<String, Long> delayMillisByRequest = new HashMap<>();
    private final Map<String, Long> lateCallbackDelayByRequest = new HashMap<>();

    public MockSupplier(SupplierChargeStorePort chargeStore, CallbackSenderPort callbackSender, TimeService time,
                        long defaultDelayMillis, long defaultDriftPercent) {
        this.chargeStore = chargeStore;
        this.callbackSender = callbackSender;
        this.time = time;
        this.defaultDelayMillis = defaultDelayMillis;
        this.defaultDriftPercent = defaultDriftPercent;
    }

    /** 测试注入口令：按 requestId 定向注入故障。 */
    public void injectFault(String requestId, Fault fault) {
        faultByRequest.put(requestId, fault == null ? Fault.NONE : fault);
    }

    public void injectDrift(String requestId, long driftPercent) {
        driftPercentByRequest.put(requestId, driftPercent);
    }

    public void injectDelay(String requestId, long delayMillis) {
        delayMillisByRequest.put(requestId, delayMillis);
    }

    public void injectLateCallback(String requestId, long delayMillis) {
        lateCallbackDelayByRequest.put(requestId, delayMillis);
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public EstimatedUsage estimateUsage(String model) {
        // 无声明上限时的兜底估计：保守固定值（历史均值×安全系数的 V1 简化）
        return new EstimatedUsage(1000L, false);
    }

    @Override
    public SupplierResponse call(SupplierCallRequest request, HoldContext holdContext) throws SupplierCallException {
        Fault fault = faultByRequest.getOrDefault(request.requestId(), Fault.NONE);
        long delay = delayMillisByRequest.getOrDefault(request.requestId(), defaultDelayMillis);
        if (fault == Fault.DELAY || fault == Fault.TIMEOUT) {
            sleep(delay);
        }
        if (fault == Fault.FAIL_BEFORE_DISPATCH) {
            // 确定未发出：连接建立即失败
            throw new SupplierCallException("mock: dispatch failed before send", false, 0, null);
        }
        // —— 请求已发出（以下任何结果都必须按「可能已计费」处理，P2/Q6）——
        long units = resolveUnits(request, fault);
        String supplierRequestId = "sup-" + UUID.randomUUID();
        boolean completed = true;
        if (fault == Fault.STREAM_INTERRUPT) {
            // 流中断：只产生部分用量，结果未知
            completed = false;
        }
        if (fault == Fault.FAIL_AFTER_DISPATCH) {
            // 已发出后供应商内部失败：计费与否未知 → 不记录用量、抛 dispatched 异常
            throw new SupplierCallException("mock: supplier error after dispatch", true, 0, null);
        }
        if (fault == Fault.TIMEOUT) {
            // 供应商实际会完成计算并计费（Q2 核心场景），但调用方已超时
            bill(request, holdContext, supplierRequestId, units);
            scheduleCallback(request.requestId(), supplierRequestId, units, false);
            throw new SupplierCallException("mock: client timeout while supplier still computing", true, 0, null);
        }
        // 供应商侧账本：完成即计费
        bill(request, holdContext, supplierRequestId, units);
        if (fault == Fault.STREAM_INTERRUPT) {
            throw new SupplierCallException("mock: stream interrupted", true, units, null);
        }
        if (fault == Fault.LATE_CALLBACK || fault == Fault.DOUBLE_CALLBACK) {
            scheduleCallback(request.requestId(), supplierRequestId, units, fault == Fault.DOUBLE_CALLBACK);
        }
        return new SupplierResponse(supplierRequestId, request.model(), units, completed,
                "{\"model\":\"" + request.model() + "\",\"usage\":{\"total_tokens\":" + units + "}}");
    }

    @Override
    public ActualUsage parseUsage(SupplierResponse response) {
        return new ActualUsage(response.supplierRequestId(), response.usageUnits(), response.completed());
    }

    @Override
    public List<UsageRecord> subscribeUsage(String requestId) {
        return chargeStore.findByRequestId(requestId)
                .map(c -> List.of(new UsageRecord(c.requestId(), c.supplierRequestId(), c.accountId(), c.units(),
                        c.seq(), c.billedAt())))
                .orElse(List.of());
    }

    private long resolveUnits(SupplierCallRequest request, Fault fault) {
        long base = request.maxUnits() > 0 ? request.maxUnits() : 1000L;
        long drift = driftPercentByRequest.getOrDefault(request.requestId(),
                fault == Fault.USAGE_DRIFT ? 30L : defaultDriftPercent);
        return Math.max(1, base + base * drift / 100);
    }

    private void bill(SupplierCallRequest request, HoldContext holdContext, String supplierRequestId, long units) {
        long seq = chargeStore.nextSeq(request.requestId());
        chargeStore.save(new SupplierChargeStorePort.SupplierCharge(supplierRequestId, request.requestId(),
                holdContext == null ? null : holdContext.accountId(), request.model(), units,
                units * UNIT_PRICE_MINOR_PER_TOKEN, seq, time.now()));
    }

    private void scheduleCallback(String requestId, String supplierRequestId, long units, boolean twice) {
        long delay = lateCallbackDelayByRequest.getOrDefault(requestId, 0L);
        CallbackSenderPort.UsageCallbackPayload payload = new CallbackSenderPort.UsageCallbackPayload(
                requestId, supplierRequestId, units, chargeStore.nextSeq(requestId), time.now().toString());
        callbackSender.sendUsageCallback(name(), payload, delay);
        if (twice) {
            callbackSender.sendUsageCallback(name(), payload, delay);
        }
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SupplierCallException("mock: interrupted", true, 0, e);
        }
    }
}
