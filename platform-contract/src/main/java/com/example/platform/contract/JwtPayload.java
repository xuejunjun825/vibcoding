package com.example.platform.contract;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Claims carried by an access token.
 *
 * <p>Deliberately flat and JSON-friendly: the gateway only needs {@code tenantId} to reject a
 * mismatched {@code X-Tenant-Id}; the services turn {@code roles} + {@code domainIds} +
 * {@code vendorId} + {@code orgSubtreeIds} into a {@link DataScope}. Authorization is resolved
 * per request from the database, so a permission change takes effect within seconds without
 * waiting for a token to expire — the token is identity, not a permission snapshot.</p>
 */
public record JwtPayload(long userId,
                         long tenantId,
                         String username,
                         List<String> roles,
                         Set<Long> domainIds,
                         Long vendorId,
                         Set<Long> orgSubtreeIds,
                         long issuedAt,
                         long expiresAt) {

    public JwtPayload {
        roles = roles == null ? List.of() : List.copyOf(roles);
        domainIds = domainIds == null ? Set.of() : Set.copyOf(domainIds);
        orgSubtreeIds = orgSubtreeIds == null ? Set.of() : new LinkedHashSet<>(orgSubtreeIds);
    }

    /** Identity plus an empty scope, to be completed by the auth filter from the database. */
    public static JwtPayload of(long userId, long tenantId, String username, List<String> roles) {
        return new JwtPayload(userId, tenantId, username, roles, Set.of(), null, Set.of(), 0L, 0L);
    }

    public boolean isExpired(long nowEpochSeconds) {
        return expiresAt > 0 && nowEpochSeconds >= expiresAt;
    }

    public Set<String> roleSet() {
        return new LinkedHashSet<>(roles);
    }
}
