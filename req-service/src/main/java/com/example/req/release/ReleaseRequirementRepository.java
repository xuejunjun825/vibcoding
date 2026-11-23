package com.example.req.release;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReleaseRequirementRepository extends JpaRepository<ReleaseRequirementEntity, ReleaseRequirementEntity.Key> {

    List<ReleaseRequirementEntity> findByTenantIdAndReleaseIdOrderByAddedAtAsc(Long tenantId, Long releaseId);

    /** 需求所属的全部版本（影响评估要列全，RELEASE 变更要清理旧成员）。 */
    List<ReleaseRequirementEntity> findByTenantIdAndRequirementId(Long tenantId, Long requirementId);

    boolean existsByTenantIdAndReleaseIdAndRequirementId(Long tenantId, Long releaseId, Long requirementId);

    void deleteByTenantIdAndReleaseIdAndRequirementId(Long tenantId, Long releaseId, Long requirementId);

    long countByTenantIdAndReleaseId(Long tenantId, Long releaseId);
}
