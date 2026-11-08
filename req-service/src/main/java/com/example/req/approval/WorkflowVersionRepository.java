package com.example.req.approval;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** {@code wf_definition} 访问。解析顺序与「发布即不可编辑」都由本仓储的查询语义承载。 */
public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersion, Long> {

    List<WorkflowVersion> findByTenantIdAndBizTypeOrderByVersionDesc(Long tenantId, String bizType);

    List<WorkflowVersion> findByTenantIdOrderByBizTypeAscVersionDesc(Long tenantId);

    /**
     * 解析一条已发布流程：{@code biz_type} 必须精确；{@code req_type} 与 {@code domain_id} 允许落到
     * ANY/0 通配，精确绑定优先；同绑定取最高版本（§5.6 解析顺序）。
     */
    @Query("""
            select d from WorkflowVersion d
             where d.tenantId = :tenantId
               and d.bizType = :bizType
               and d.status = 'PUBLISHED'
               and (d.reqType = :reqType or d.reqType = 'ANY')
               and (d.domainId = :domainId or d.domainId = 0)
             order by case when d.reqType = :reqType then 0 else 1 end,
                      case when d.domainId = :domainId then 0 else 1 end,
                      d.version desc,
                      d.id desc
            """)
    List<WorkflowVersion> resolveCandidates(@Param("tenantId") Long tenantId,
                                            @Param("bizType") String bizType,
                                            @Param("reqType") String reqType,
                                            @Param("domainId") Long domainId);

    /** 无任何绑定命中时退回默认流程（{@code is_default=1}），不要求 req_type/domain 匹配。 */
    @Query("""
            select d from WorkflowVersion d
             where d.tenantId = :tenantId
               and d.bizType = :bizType
               and d.status = 'PUBLISHED'
               and d.isDefault = 1
             order by d.version desc, d.id desc
            """)
    List<WorkflowVersion> defaultCandidates(@Param("tenantId") Long tenantId, @Param("bizType") String bizType);

    Optional<WorkflowVersion> findFirstByTenantIdAndBizTypeOrderByVersionDesc(Long tenantId, String bizType);

    Optional<WorkflowVersion> findFirstByTenantIdAndBizTypeAndReqTypeAndDomainIdOrderByVersionDesc(
            Long tenantId, String bizType, String reqType, Long domainId);

    List<WorkflowVersion> findByTenantIdAndBizTypeAndIsDefault(Long tenantId, String bizType, Integer isDefault);

    long countByTenantIdAndStatus(Long tenantId, String status);
}
