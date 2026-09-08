package io.quotapilot.infra.idempotency.domain;

import java.util.Optional;

/**
 * [M11/P6] 幂等存储端口：key 形如 "opType:businessKey"。DB 唯一约束为权威，Redis 仅作快速判重（可选实现）。
 */
public interface IdempotencyPort {

    Optional<String> get(String key);

    /** 幂等写入：已存在同值时静默忽略；存在异值时抛 IdempotencyConflict。 */
    void put(String key, String result);
}
