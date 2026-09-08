package io.quotapilot.alert.domain;

/**
 * [M9] 告警出口端口：实现方负责 Web 面板 + Webhook 通道与抑制窗口。
 */
public interface AlertEmitterPort {

    void emit(Alert alert);
}
