package io.quotapilot.infra.persistence;

import java.time.Instant;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.quotapilot.common.DomainExceptions;
import io.quotapilot.common.TimeService;
import io.quotapilot.infra.idempotency.domain.IdempotencyPort;

/** [M11/P6] 幂等存储适配器：DB 唯一约束为权威。 */
@Component
public class IdempotencyAdapter implements IdempotencyPort {

    private final IdempotencyJpaRepo repo;
    private final TimeService time;

    public IdempotencyAdapter(IdempotencyJpaRepo repo, TimeService time) {
        this.repo = repo;
        this.time = time;
    }

    @Override
    public Optional<String> get(String key) {
        return repo.findById(key).map(e -> e.result);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void put(String key, String result) {
        IdempotencyEntity e = repo.findById(key).orElse(null);
        if (e != null) {
            if (!e.result.equals(result)) {
                throw new DomainExceptions.IdempotencyConflict(key);
            }
            return;
        }
        IdempotencyEntity fresh = new IdempotencyEntity();
        fresh.idemKey = key;
        fresh.result = result;
        fresh.createdAt = time.now();
        try {
            repo.saveAndFlush(fresh);
        } catch (DataIntegrityViolationException race) {
            IdempotencyEntity winner = repo.findById(key).orElse(null);
            if (winner != null && !winner.result.equals(result)) {
                throw new DomainExceptions.IdempotencyConflict(key);
            }
        }
    }
}
