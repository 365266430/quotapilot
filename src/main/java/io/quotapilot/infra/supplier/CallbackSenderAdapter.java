package io.quotapilot.infra.supplier;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.quotapilot.metering.domain.CallbackService;
import io.quotapilot.supplier.domain.CallbackSenderPort;

/**
 * [M10] 回调投递适配器：模拟供应商向 QuotaPilot 的回调通道发送用量事件。
 * delayMillis>0 用调度线程模拟「迟到回调」；投递失败仅记录日志（供应商重试语义由对账兜底）。
 */
@Component
public class CallbackSenderAdapter implements CallbackSenderPort {

    private static final Logger log = LoggerFactory.getLogger(CallbackSenderAdapter.class);

    private final CallbackService callbackService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "supplier-callback");
        t.setDaemon(true);
        return t;
    });

    public CallbackSenderAdapter(CallbackService callbackService) {
        this.callbackService = callbackService;
    }

    @Override
    public void sendUsageCallback(String supplierName, UsageCallbackPayload payload, long delayMillis) {
        if (delayMillis <= 0) {
            deliver(supplierName, payload);
            return;
        }
        scheduler.schedule(() -> deliver(supplierName, payload), delayMillis, TimeUnit.MILLISECONDS);
    }

    private void deliver(String supplierName, UsageCallbackPayload payload) {
        try {
            callbackService.ingest(supplierName,
                    new CallbackService.CallbackPayload(payload.requestId(), payload.supplierRequestId(),
                            payload.units(), payload.seq(), null));
        } catch (RuntimeException e) {
            log.warn("供应商回调投递失败 supplier={} requestId={} err={}", supplierName, payload.requestId(),
                    e.toString());
        }
    }
}
