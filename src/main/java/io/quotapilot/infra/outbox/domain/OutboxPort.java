package io.quotapilot.infra.outbox.domain;

/**
 * [M11] Outbox 写入端口：调用方在业务事务内 enqueue，实现方保证同事务持久化。
 */
public interface OutboxPort {

    void enqueue(String aggregateType, String aggregateId, String eventType, String payloadJson);
}
