package com.example.deliver.metric;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MetricDefinitionRepository extends JpaRepository<MetricDefinitionEntity, Long> {

    List<MetricDefinitionEntity> findByTenantIdOrderByCodeAscVersionAsc(long tenantId);

    Optional<MetricDefinitionEntity> findFirstByTenantIdAndCodeOrderByVersionDesc(long tenantId, String code);
}
