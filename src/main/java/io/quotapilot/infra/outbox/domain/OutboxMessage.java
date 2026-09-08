package io.quotapilot.infra.outbox.domain;

/**
 * [M11] Outbox 事件：所有需要异步投递的账本/业务事件先写 DB（与业务同事务），
 * 由投递器送达事件通道，保证「DB 成功 ⇒ 事件必达」。
 */
public record OutboxMessage(String id, String aggregateType, String aggregateId, String eventType, String payloadJson) {}
