package com.example.req.requirement;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RequirementRepository extends JpaRepository<RequirementEntity, Long> {

    Optional<RequirementEntity> findByIdAndTenantId(Long id, Long tenantId);

    /**
     * apply 变更单前必须先锁需求行：把「比对 before → 写字段 → 写 items」收进同一事务，
     * 防两个变更单并发双写或写到一半。仅变更单 apply 使用，普通 PUT 不锁。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RequirementEntity r where r.id = :id and r.tenantId = :tenantId")
    Optional<RequirementEntity> findWithLockByIdAndTenantId(@Param("id") Long id,
                                                            @Param("tenantId") Long tenantId);

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
