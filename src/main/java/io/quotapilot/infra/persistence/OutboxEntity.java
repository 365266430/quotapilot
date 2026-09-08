package io.quotapilot.infra.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** [M11] Outbox 表：与业务同事务写入，投递器异步送达（DB 成功 ⇒ 事件必达）。 */
@Entity
@Table(name = "outbox", indexes = @Index(name = "ix_outbox_status", columnList = "status,created_at"))
public class OutboxEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String aggregateType;
    public String aggregateId;
    public String eventType;
    @Column(length = 4096)
    public String payloadJson;
    public String status; // PENDING / SENT / FAILED
    public int attempts;
    public Instant createdAt;
    public Instant dispatchedAt;
}
