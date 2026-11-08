package com.example.deliver.testcase;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TestCaseVersionRepository extends JpaRepository<TestCaseVersionEntity, Long> {

    Optional<TestCaseVersionEntity> findByCaseIdAndVersionNo(long caseId, int versionNo);

    List<TestCaseVersionEntity> findByCaseIdOrderByVersionNoDesc(long caseId);
}
