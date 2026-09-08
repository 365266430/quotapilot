package io.quotapilot.supplier.domain;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * [M10/M6] 流式响应：供应商适配器返回的流式 HTTP 响应。
 * close() 语义 = 尽力取消上游（关闭连接/取消订阅）——客户端断连或提前结束时必须调用（Q6）。
 * 明示规则：close/取消 ≠ 上游停止计费；调用方必须按「可能已产生外部费用」走敞口对账（P2）。
 */
public class StreamedResponse implements AutoCloseable {

    private final InputStream body;
    private final Map<String, java.util.List<String>> headers;
    private final Runnable onCancel;

    public StreamedResponse(InputStream body, Map<String, java.util.List<String>> headers, Runnable onCancel) {
        this.body = body;
        this.headers = headers;
        this.onCancel = onCancel == null ? () -> { } : onCancel;
    }

    public InputStream body() {
        return body;
    }

    public Map<String, java.util.List<String>> headers() {
        return headers;
    }

    /** 读取 body 的便捷方法（非流式消费场景），IOException 透传给调用方处理。 */
    public byte[] readAll() throws IOException {
        return body.readAllBytes();
    }

    @Override
    public void close() {
        try {
            body.close();
        } catch (IOException ignored) {
            // 上游连接关闭异常不阻断释放流程
        }
        try {
            onCancel.run();
        } catch (RuntimeException ignored) {
            // 取消上游失败不阻断释放流程（上游可能继续计费 → 由对账兜底）
        }
    }
}
