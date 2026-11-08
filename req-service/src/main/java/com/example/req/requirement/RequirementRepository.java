package com.example.req.requirement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RequirementRepository extends JpaRepository<RequirementEntity, Long> {

    Optional<RequirementEntity> findByIdAndTenantId(Long id, Long tenantId);

    Optional<RequirementEntity> findByTenantIdAndCode(Long tenantId, String code);

    List<RequirementEntity> findByTenantIdAndParentIdOrderByCreatedAtAsc(Long tenantId, Long parentId);

    List<RequirementEntity> findByTenantIdAndParentIdIn(Long tenantId, Collection<Long> parentIds);

    List<RequirementEntity> findByTenantIdAndReleaseId(Long tenantId, Long releaseId);

    long countByTenantIdAndParentId(Long tenantId, Long parentId);

    boolean existsByTenantIdAndParentId(Long tenantId, Long parentId);

    boolean existsByTenantIdAndReleaseId(Long tenantId, Long releaseId);

    @Query("""
            select coalesce(sum(r.estimateDays), 0) from RequirementEntity r
            where r.tenantId = :tenantId and r.parentId = :parentId
            """)
    BigDecimal sumEstimateDaysByParent(@Param("tenantId") Long tenantId, @Param("parentId") Long parentId);

    @Query("""
            select coalesce(sum(r.actualDays), 0) from RequirementEntity r
            where r.tenantId = :tenantId and r.parentId = :parentId
            """)
    BigDecimal sumActualDaysByParent(@Param("tenantId") Long tenantId, @Param("parentId") Long parentId);
}
