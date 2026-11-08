package com.example.deliver.testplan;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TestPlanCaseRepository extends JpaRepository<TestPlanCaseEntity, Long> {

    List<TestPlanCaseEntity> findByPlanIdOrderByCaseIdAsc(long planId);

    Optional<TestPlanCaseEntity> findByPlanIdAndCaseId(long planId, long caseId);

    long countByPlanId(long planId);
}
