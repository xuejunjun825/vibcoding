package com.example.platform.common.scope;

/**
 * A persisted row that carries the isolation columns.
 *
 * <p>List queries are filtered in SQL; by-id reads (detail, attachment, approval summary) cannot
 * be, so the row exposes the four dimensions and {@link ScopeGuard} decides visibility. Returning
 * {@code null} from a column accessor means "dimension not present on this aggregate".</p>
 */
public interface ScopeAware {

    long tenantId();

    default Long domainId() {
        return null;
    }

    default Long vendorId() {
        return null;
    }

    default Long originOrgId() {
        return null;
    }

    /** Submitter id, for {@code ScopeTier.SELF} ("only what I submitted"). */
    default Long ownerUserId() {
        return null;
    }
}
