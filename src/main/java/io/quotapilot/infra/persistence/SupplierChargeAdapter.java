package io.quotapilot.infra.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import io.quotapilot.supplier.domain.SupplierChargeStorePort;

/** [M10/M5] 供应商侧账本适配器（MockSupplier 计费记录；调用方无外层事务，独立仓储事务）。 */
@Component
public class SupplierChargeAdapter implements SupplierChargeStorePort {

    private final SupplierChargeJpaRepo repo;

    public SupplierChargeAdapter(SupplierChargeJpaRepo repo) {
        this.repo = repo;
    }

    @Override
    public void save(SupplierCharge charge) {
        SupplierChargeEntity e = new SupplierChargeEntity();
        e.supplierRequestId = charge.supplierRequestId() == null ? UUID.randomUUID().toString()
                : charge.supplierRequestId();
        e.requestId = charge.requestId();
        e.accountId = charge.accountId();
        e.model = charge.model();
        e.units = charge.units();
        e.amountMinor = charge.amountMinor();
        e.seq = charge.seq();
        e.billedAt = charge.billedAt();
        try {
            repo.saveAndFlush(e);
        } catch (DataIntegrityViolationException dup) {
            // 同请求同 seq 的供应商计费事件视为重复投递，安全忽略（P6）
        }
    }

    @Override
    public Optional<SupplierCharge> findByRequestId(String requestId) {
        return repo.findByRequestId(requestId).map(SupplierChargeAdapter::toDomain);
    }

    @Override
    public List<SupplierCharge> listAll() {
        return repo.findAll().stream().map(SupplierChargeAdapter::toDomain).toList();
    }

    @Override
    public List<SupplierCharge> findByAccount(String accountId) {
        return repo.findByAccountId(accountId).stream().map(SupplierChargeAdapter::toDomain).toList();
    }

    @Override
    public long nextSeq(String requestId) {
        return repo.maxSeqByRequest(requestId) + 1;
    }

    static SupplierCharge toDomain(SupplierChargeEntity e) {
        return new SupplierCharge(e.supplierRequestId, e.requestId, e.accountId, e.model, e.units, e.amountMinor,
                e.seq, e.billedAt);
    }
}
