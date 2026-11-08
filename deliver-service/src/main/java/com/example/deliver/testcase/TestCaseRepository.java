package com.example.deliver.testcase;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TestCaseRepository extends JpaRepository<TestCaseEntity, Long> {

    Optional<TestCaseEntity> findByIdAndTenantId(long id, long tenantId);

    boolean existsByTenantIdAndModuleIdAndDeletedAtIsNull(long tenantId, long moduleId);
}
