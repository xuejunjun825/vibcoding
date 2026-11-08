package com.example.deliver.testcase;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TestCaseStepRepository extends JpaRepository<TestCaseStepEntity, Long> {

    List<TestCaseStepEntity> findByCaseIdAndVersionNoOrderByStepNoAsc(long caseId, int versionNo);
}
