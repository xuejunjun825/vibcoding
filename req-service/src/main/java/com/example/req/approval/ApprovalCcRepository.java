package com.example.req.approval;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** {@code wf_cc} 访问。 */
public interface ApprovalCcRepository extends JpaRepository<ApprovalCc, Long> {

    List<ApprovalCc> findByInstanceIdOrderByIdAsc(Long instanceId);

    List<ApprovalCc> findByTenantIdAndReceiverIdOrderByIdDesc(Long tenantId, Long receiverId);
}
