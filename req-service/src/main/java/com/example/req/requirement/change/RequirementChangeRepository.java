package com.example.req.requirement.change;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 变更单查询/分页与状态条件更新。
 *
 * <p>{@link #markStatus} / {@link #markApplied} 返回受影响行数：调用方（回调与 apply）
 * 以 {@code expected → next} 作为并发闸，返回 0 表示状态已被别人改过（或本单已应用），
 * 由服务层决定幂等忽略还是 409。不要把它们换成「先查后 save」——那会把并发窗口
 * 带回业务层。</p>
 */
public interface RequirementChangeRepository extends JpaRepository<RequirementChangeEntity, Long> {

    Optional<RequirementChangeEntity> findByIdAndTenantId(Long id, Long tenantId);

    /** status 为 null 时不过滤；分页固定按创建时间倒序（同刻再按 id 倒序）。 */
    @Query("""
            select c from RequirementChangeEntity c
            where c.tenantId = :tenantId
              and c.requirementId = :requirementId
              and (:status is null or c.status = :status)
            order by c.createdAt desc, c.id desc
            """)
    Page<RequirementChangeEntity> page(@Param("tenantId") Long tenantId,
                                       @Param("requirementId") Long requirementId,
                                       @Param("status") String status,
                                       Pageable pageable);

    long countByTenantIdAndRequirementIdAndStatus(Long tenantId, Long requirementId, String status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RequirementChangeEntity c
            set c.status = :next
            where c.id = :id and c.tenantId = :tenantId and c.status = :expected
            """)
    int markStatus(@Param("id") long id,
                   @Param("tenantId") long tenantId,
                   @Param("expected") String expected,
                   @Param("next") String next);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RequirementChangeEntity c
            set c.status = 'APPLIED', c.appliedAt = :appliedAt, c.appliedBy = :appliedBy
            where c.id = :id and c.tenantId = :tenantId and c.status = :expected
            """)
    int markApplied(@Param("id") long id,
                    @Param("tenantId") long tenantId,
                    @Param("expected") String expected,
                    @Param("appliedAt") LocalDateTime appliedAt,
                    @Param("appliedBy") Long appliedBy);
}
