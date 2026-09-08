package io.quotapilot.common;

/**
 * [M11] traceId 载体：非 Web 上下文（调度/回调线程）下由调用方显式传递或退化为随机。
 */
public final class TraceIdHolder {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TraceIdHolder() {}

    public static String get() {
        String v = HOLDER.get();
        if (v != null) {
            return v;
        }
        String mdc = org.slf4j.MDC.get(io.quotapilot.api.TraceIdFilter.MDC_KEY);
        return mdc == null ? java.util.UUID.randomUUID().toString() : mdc;
    }

    public static void set(String traceId) {
        HOLDER.set(traceId);
    }

    public static void clear() {
        HOLDER.remove();
    }
}
