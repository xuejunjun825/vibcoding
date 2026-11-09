package com.example.req.integration.admission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Sonar 快照只读仓储：取某需求最新一条 {@code source='SONAR'} 的快照。
 *
 * <p>查询只按 id 倒序取一条——快照是追加写，最新的一条就是当前结论（TECHNICAL §7.2）。</p>
 */
public interface SonarSnapshotRepository extends JpaRepository<SonarSnapshotEntity, Long> {

    Optional<SonarSnapshotEntity> findFirstByTenantIdAndSourceAndRequirementIdOrderByIdDesc(
            Long tenantId, String source, Long requirementId);
}
