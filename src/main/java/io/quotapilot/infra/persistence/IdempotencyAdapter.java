package io.quotapilot.infra.persistence;

import java.time.Instant;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import io.quotapilot.common.DomainExceptions;
import io.quotapilot.common.TimeService;
import io.quotapilot.infra.idempotency.domain.IdempotencyPort;

/**
 * [M11/P6] 幂等存储适配器：DB 唯一约束为权威。
 * 事务语义：加入调用方事务（与业务同事务持久化）；禁止 REQUIRES_NEW（嵌套挂起连接在并发下会耗尽连接池）。
 * 冲突路径必须抛异常使外层事务回滚，不允许吞掉约束冲突后正常提交（rollback-only 陷阱）。
 */
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
    public void put(String key, String result) {
        Optional<IdempotencyEntity> existing = repo.findById(key);
        if (existing.isPresent()) {
            if (!existing.get().result.equals(result)) {
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
            // 外层事务已被标记 rollback-only：必须以异常结束本操作，由调用方按重复请求处理
            throw new DomainExceptions.DuplicateRequest(key, "idempotency-put");
        }
    }
}
