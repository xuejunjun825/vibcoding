package com.example.deliver.testcase;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TestModuleRepository extends JpaRepository<TestModuleEntity, Long> {

    List<TestModuleEntity> findByTenantIdAndParentIdIsNullOrderBySortNoAscIdAsc(long tenantId);

    List<TestModuleEntity> findByTenantIdAndParentIdOrderBySortNoAscIdAsc(long tenantId, Long parentId);

    Optional<TestModuleEntity> findByIdAndTenantId(long id, long tenantId);

    boolean existsByTenantIdAndPath(long tenantId, String path);

    boolean existsByTenantIdAndParentId(long tenantId, Long parentId);

    List<TestModuleEntity> findByTenantIdAndPathStartingWith(long tenantId, String prefix);
}
