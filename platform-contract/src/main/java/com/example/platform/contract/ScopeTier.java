package com.example.platform.contract;

/**
 * The depth at which a request's data visibility is rooted.
 *
 * <p>Isolation is two-dimensional (domain × vendor) and sometimes organization-scoped.
 * The tier says <em>which</em> of those dimensions the request may see; the concrete ids
 * travel in {@link DataScope}. Business code never invents its own filtering rules —
 * it asks {@code DataScope} and lets {@code ScopeSql} render the predicate.</p>
 */
public enum ScopeTier {

    /** Platform-level: every row of the tenant (seed, admin, internal calls). */
    TENANT,
    /** Domain-scoped: only rows belonging to {@code DataScope.domainIds()}. */
    DOMAIN,
    /** Vendor-scoped: only rows whose vendor is visible, restricted to the user's domains. */
    VENDOR,
    /** Organization-subtree-scoped: reporter/handler inside the user's org subtree. */
    ORG,
    /** Business-unit-scoped (业务部门 / 二级企业负责人): rows whose origin org is in the subtree. */
    BIZ,
    /** Self-scoped (二级企业业务): only rows the caller submitted. */
    SELF
}
