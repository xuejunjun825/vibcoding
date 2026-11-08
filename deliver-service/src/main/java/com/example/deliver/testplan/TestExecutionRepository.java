package com.example.deliver.testplan;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TestExecutionRepository extends JpaRepository<TestExecutionEntity, Long> {

    Optional<TestExecutionEntity> findByTenantIdAndClientIdAndRunIdAndCaseId(
            long tenantId, long clientId, String runId, long caseId);
}
