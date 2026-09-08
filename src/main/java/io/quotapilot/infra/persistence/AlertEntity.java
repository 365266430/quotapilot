package io.quotapilot.infra.persistence;

import java.time.Instant;

import io.quotapilot.alert.domain.AlertType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** [M9] 告警记录表。 */
@Entity
@Table(name = "alerts", indexes = @Index(name = "ix_alerts_time", columnList = "created_at"))
public class AlertEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Enumerated(EnumType.STRING)
    public AlertType type;
    public String severity;
    public String accountId;
    public String requestId;
    public String message;
    public Instant createdAt;
}
