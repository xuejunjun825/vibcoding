package com.example.req.release;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReleaseRequirementRepository extends JpaRepository<ReleaseRequirementEntity, ReleaseRequirementEntity.Key> {

    List<ReleaseRequirementEntity> findByTenantIdAndReleaseIdOrderByAddedAtAsc(Long tenantId, Long releaseId);

    boolean existsByTenantIdAndReleaseIdAndRequirementId(Long tenantId, Long releaseId, Long requirementId);

    void deleteByTenantIdAndReleaseIdAndRequirementId(Long tenantId, Long releaseId, Long requirementId);

    long countByTenantIdAndReleaseId(Long tenantId, Long releaseId);
}
