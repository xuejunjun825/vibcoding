package com.example.deliver.task;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<TaskEntity, Long> {

    /** 内部建任务时无 {@code X-Tenant-Id} 的兜底：按需求行反查租户（不暴露 JDBC 模板）。 */
    @Query(value = "SELECT tenant_id FROM requirement WHERE id = :id", nativeQuery = true)
    Optional<Long> findRequirementTenantId(@Param("id") long id);

    List<TaskEntity> findByTenantIdAndRequirementIdOrderBySortNoAscIdAsc(long tenantId, long requirementId);

    List<TaskEntity> findByTenantIdAndSprintIdOrderBySortNoAscIdAsc(long tenantId, Long sprintId);

    long countByTenantIdAndSprintIdAndStatusNot(long tenantId, Long sprintId, String status);

    List<TaskEntity> findByTenantIdAndSprintIdAndStatusNotOrderBySortNoAscIdAsc(
            long tenantId, Long sprintId, String status);
}
