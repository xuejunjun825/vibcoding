package com.example.req.approval;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** {@code wf_instance} 访问；并发推进用 {@link #lockById} 的悲观写锁串行化。 */
public interface ApprovalInstanceRepository extends JpaRepository<ApprovalInstance, Long> {

    Optional<ApprovalInstance> findFirstByTenantIdAndBizTypeAndBizIdAndActiveFlag(
            Long tenantId, String bizType, Long bizId, Integer activeFlag);

    Optional<ApprovalInstance> findFirstByTenantIdAndBizTypeAndBizIdOrderByIdDesc(
            Long tenantId, String bizType, Long bizId);

    Optional<ApprovalInstance> findByIdAndTenantId(Long id, Long tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ApprovalInstance i where i.id = :id")
    Optional<ApprovalInstance> lockById(@Param("id") Long id);

    Page<ApprovalInstance> findByTenantIdAndInitiatorIdOrderByIdDesc(Long tenantId, Long initiatorId, Pageable pageable);

    long countByTenantIdAndStatus(Long tenantId, String status);

    long countByTenantIdAndDefinitionId(Long tenantId, Long definitionId);
}
