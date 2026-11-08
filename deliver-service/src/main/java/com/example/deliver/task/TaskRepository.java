package com.example.deliver.task;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<TaskEntity, Long> {

    List<TaskEntity> findByTenantIdAndRequirementIdOrderBySortNoAscIdAsc(long tenantId, long requirementId);

    List<TaskEntity> findByTenantIdAndSprintIdOrderBySortNoAscIdAsc(long tenantId, Long sprintId);

    long countByTenantIdAndSprintIdAndStatusNot(long tenantId, Long sprintId, String status);

    List<TaskEntity> findByTenantIdAndSprintIdAndStatusNotOrderBySortNoAscIdAsc(
            long tenantId, Long sprintId, String status);
}
