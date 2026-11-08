package com.example.deliver.defect;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DefectRepository extends JpaRepository<DefectEntity, Long> {

    List<DefectEntity> findByTenantIdAndRequirementId(long tenantId, long requirementId);

    boolean existsByTenantIdAndCode(long tenantId, String code);
}
