package io.quotapilot.infra.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierChargeJpaRepo extends JpaRepository<SupplierChargeEntity, String> {

    Optional<SupplierChargeEntity> findByRequestId(String requestId);

    List<SupplierChargeEntity> findByAccountId(String accountId);

    @Query("select coalesce(max(c.seq),0) from SupplierChargeEntity c where c.requestId = :r")
    long maxSeqByRequest(@Param("r") String requestId);
}
