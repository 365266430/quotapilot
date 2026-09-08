package io.quotapilot.infra.persistence;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** [M10/M5] 供应商侧账本（MockSupplier 计费记录）：对账测试的数据源。 */
@Entity
@Table(name = "supplier_charges", uniqueConstraints = @UniqueConstraint(name = "uk_charge_seq", columnNames = {"request_id", "seq"}))
public class SupplierChargeEntity {
    @Id
    public String supplierRequestId;
    public String requestId;
    public String accountId;
    public String model;
    public long units;
    public long amountMinor;
    public long seq;
    public Instant billedAt;
}
