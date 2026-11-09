package com.example.req.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 评审记录仓储：按租户隔离，版本内按 id 倒序（最新一条即最近结论）。 */
public interface ReleaseReviewRepository extends JpaRepository<ReleaseReviewEntity, Long> {

    List<ReleaseReviewEntity> findByTenantIdAndReleaseIdOrderByIdDesc(Long tenantId, Long releaseId);

    Optional<ReleaseReviewEntity> findByIdAndTenantId(Long id, Long tenantId);
}
