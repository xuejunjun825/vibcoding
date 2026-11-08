package com.example.deliver.testplan;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TestPlanRepository extends JpaRepository<TestPlanEntity, Long> {

    Optional<TestPlanEntity> findByIdAndTenantId(long id, long tenantId);
}
