package com.example.deliver.portal;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Rows of {@code portal_card_config} — role-level template config and personal pins.
 *
 * <p>Derived queries only: the two row kinds are told apart by {@code userId}
 * ({@link PortalCardConfigEntity#ROLE_LEVEL} vs a real user id), so no caller needs a hand-written
 * {@code WHERE} that could drift from the unique key.</p>
 */
public interface PortalCardConfigRepository extends JpaRepository<PortalCardConfigEntity, Long> {

    Optional<PortalCardConfigEntity> findByTenantIdAndRoleCodeAndUserId(long tenantId, String roleCode, long userId);

    List<PortalCardConfigEntity> findByTenantIdAndUserIdOrderByIdAsc(long tenantId, long userId);
}
