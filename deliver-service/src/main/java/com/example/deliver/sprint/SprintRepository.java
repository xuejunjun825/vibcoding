package com.example.deliver.sprint;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SprintRepository extends JpaRepository<SprintEntity, Long> {

    List<SprintEntity> findByTenantIdOrderByStartDateDescIdDesc(long tenantId);

    List<SprintEntity> findByTenantIdAndStatusOrderByStartDateAscIdAsc(long tenantId, String status);
}
