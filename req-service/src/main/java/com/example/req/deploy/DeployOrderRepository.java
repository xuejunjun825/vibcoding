package com.example.req.deploy;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 发布单仓储。列表/统计都带"发布单所属版本的域在数据范围内"的条件，避免越权看到别域单据。 */
public interface DeployOrderRepository extends JpaRepository<DeployOrderEntity, Long> {

    Optional<DeployOrderEntity> findByIdAndTenantId(Long id, Long tenantId);

    @Query("select o from DeployOrderEntity o where o.tenantId = :tenantId"
            + " and (:releaseId = 0 or o.releaseId = :releaseId)"
            + " and (:env = '' or o.env = :env)"
            + " and (:status = '' or o.status = :status)"
            + " and (:domainFilter = false or o.releaseId in"
            + "      (select r.id from ReleaseEntity r where r.tenantId = :tenantId"
            + "       and r.domainId in :domainIds))"
            + " order by o.id desc")
    Page<DeployOrderEntity> search(@Param("tenantId") Long tenantId,
                                   @Param("releaseId") long releaseId,
                                   @Param("env") String env,
                                   @Param("status") String status,
                                   @Param("domainFilter") boolean domainFilter,
                                   @Param("domainIds") List<Long> domainIds,
                                   Pageable pageable);

    @Query("select count(o) from DeployOrderEntity o where o.tenantId = :tenantId"
            + " and (:releaseId = 0 or o.releaseId = :releaseId)"
            + " and (:env = '' or o.env = :env)"
            + " and (:status = '' or o.status = :status)"
            + " and (:domainFilter = false or o.releaseId in"
            + "      (select r.id from ReleaseEntity r where r.tenantId = :tenantId"
            + "       and r.domainId in :domainIds))")
    long countSearch(@Param("tenantId") Long tenantId,
                     @Param("releaseId") long releaseId,
                     @Param("env") String env,
                     @Param("status") String status,
                     @Param("domainFilter") boolean domainFilter,
                     @Param("domainIds") List<Long> domainIds);

    /** 发布统计用：同一批筛选条件，不分页（统计口径与列表一致，数字可逐单复算）。 */
    @Query("select o from DeployOrderEntity o where o.tenantId = :tenantId"
            + " and (:releaseId = 0 or o.releaseId = :releaseId)"
            + " and (:env = '' or o.env = :env)"
            + " and o.createdAt >= :from and o.createdAt < :to"
            + " and (:domainFilter = false or o.releaseId in"
            + "      (select r.id from ReleaseEntity r where r.tenantId = :tenantId"
            + "       and r.domainId in :domainIds))"
            + " order by o.id desc")
    List<DeployOrderEntity> searchForStats(@Param("tenantId") Long tenantId,
                                           @Param("releaseId") long releaseId,
                                           @Param("env") String env,
                                           @Param("from") LocalDateTime from,
                                           @Param("to") LocalDateTime to,
                                           @Param("domainFilter") boolean domainFilter,
                                           @Param("domainIds") List<Long> domainIds);
}
