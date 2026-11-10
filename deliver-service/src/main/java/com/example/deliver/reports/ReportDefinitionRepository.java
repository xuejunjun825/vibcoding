package com.example.deliver.reports;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for report definitions.
 *
 * <p>Every query is a derived method with no hand-written {@code WHERE}: the row-level scope is the
 * job of {@code ScopedJdbc} on the data path, and the sharing tiers are the job of
 * {@link com.example.deliver.support.ShareVisibility} on this listing path. A second visibility
 * predicate in SQL here would be exactly the drift AC-8/AC-13 forbid.</p>
 */
public interface ReportDefinitionRepository extends JpaRepository<ReportDefinitionEntity, Long> {

    Optional<ReportDefinitionEntity> findByIdAndTenantIdAndStatus(long id, long tenantId, String status);

    Page<ReportDefinitionEntity> findByTenantIdAndOwnerIdAndStatusAndNameContainingOrderByUpdatedAtDesc(
            long tenantId, long ownerId, String status, String keyword, Pageable page);

    Page<ReportDefinitionEntity> findByTenantIdAndOwnerIdAndStatusOrderByUpdatedAtDesc(
            long tenantId, long ownerId, String status, Pageable page);

    boolean existsByTenantIdAndOwnerIdAndNameAndStatus(long tenantId, long ownerId, String name, String status);

    List<ReportDefinitionEntity> findByTenantIdAndStatusOrderByUpdatedAtDesc(long tenantId, String status);

    List<ReportDefinitionEntity> findByTenantIdAndOwnerIdAndStatusOrderByUpdatedAtDesc(
            long tenantId, long ownerId, String status);
}
