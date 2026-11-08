package com.example.req.requirement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RequirementDomainRelRepository extends JpaRepository<RequirementDomainRelEntity, RequirementDomainRelEntity.Key> {

    List<RequirementDomainRelEntity> findByRequirementId(Long requirementId);

    void deleteByRequirementId(Long requirementId);
}
