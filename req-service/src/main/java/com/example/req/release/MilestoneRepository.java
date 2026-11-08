package com.example.req.release;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MilestoneRepository extends JpaRepository<MilestoneEntity, Long> {

    List<MilestoneEntity> findByTenantIdAndReleaseIdOrderByPlannedDateAsc(Long tenantId, Long releaseId);
}
