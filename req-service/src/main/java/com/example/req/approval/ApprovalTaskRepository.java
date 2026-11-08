package com.example.req.approval;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** {@code wf_task} 访问。任务行的加锁保证「同一待办只能被处理一次」（AC-9 重复处理 409）。 */
public interface ApprovalTaskRepository extends JpaRepository<ApprovalTask, Long> {

    List<ApprovalTask> findByInstanceIdOrderByNodeKeyAscSortNoAscIdAsc(Long instanceId);

    List<ApprovalTask> findByInstanceIdOrderBySortNoAscIdAsc(Long instanceId);

    List<ApprovalTask> findByInstanceIdAndStatusOrderBySortNoAscIdAsc(Long instanceId, String status);

    List<ApprovalTask> findByInstanceIdAndNodeKeyAndStatusOrderBySortNoAscIdAsc(
            Long instanceId, String nodeKey, String status);

    List<ApprovalTask> findByInstanceIdAndNodeKeyAndApproverIdOrderBySignNoDesc(
            Long instanceId, String nodeKey, Long approverId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ApprovalTask t where t.id = :id")
    Optional<ApprovalTask> lockById(@Param("id") Long id);

    Page<ApprovalTask> findByTenantIdAndApproverIdAndStatusOrderByIdDesc(
            Long tenantId, Long approverId, String status, Pageable pageable);

    Page<ApprovalTask> findByTenantIdAndApproverIdAndStatusInOrderByIdDesc(
            Long tenantId, Long approverId, java.util.Collection<String> statuses, Pageable pageable);

    long countByTenantIdAndApproverIdAndStatus(Long tenantId, Long approverId, String status);

    long countByInstanceIdAndStatus(Long instanceId, String status);

    Optional<ApprovalTask> findFirstByInstanceIdAndNodeKeyOrderBySortNoDesc(Long instanceId, String nodeKey);

    Optional<ApprovalTask> findFirstByInstanceIdAndNodeKeyOrderBySortNoAsc(Long instanceId, String nodeKey);
}
