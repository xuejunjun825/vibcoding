package com.example.req.release;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReleaseRepository extends JpaRepository<ReleaseEntity, Long> {

    Optional<ReleaseEntity> findByIdAndTenantId(Long id, Long tenantId);

    Optional<ReleaseEntity> findFirstByTenantIdOrderByIdDesc(Long tenantId);
}
