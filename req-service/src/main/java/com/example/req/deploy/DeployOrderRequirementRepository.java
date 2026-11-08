package com.example.req.deploy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 发布单↔需求关联仓储。 */
public interface DeployOrderRequirementRepository extends JpaRepository<DeployOrderRequirementEntity, DeployOrderRequirementEntity.Key> {

    List<DeployOrderRequirementEntity> findByTenantIdAndDeployOrderIdOrderByRequirementIdAsc(Long tenantId, Long deployOrderId);

    long countByTenantIdAndDeployOrderId(Long tenantId, Long deployOrderId);

    void deleteByTenantIdAndDeployOrderId(Long tenantId, Long deployOrderId);
}
