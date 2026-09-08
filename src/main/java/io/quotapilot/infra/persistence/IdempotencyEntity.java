package io.quotapilot.infra.persistence;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** [M11/P6] 幂等存储表：key=opType:businessKey。 */
@Entity
@Table(name = "idempotency")
public class IdempotencyEntity {
    @Id
    public String idemKey;
    public String result;
    public Instant createdAt;
}
