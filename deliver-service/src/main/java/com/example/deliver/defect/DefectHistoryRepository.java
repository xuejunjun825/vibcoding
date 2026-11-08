package com.example.deliver.defect;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DefectHistoryRepository extends JpaRepository<DefectHistoryEntity, Long> {

    List<DefectHistoryEntity> findByTenantIdAndDefectIdOrderByIdAsc(long tenantId, long defectId);
}
