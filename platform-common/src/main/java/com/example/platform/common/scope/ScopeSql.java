package com.example.platform.common.scope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.example.platform.contract.DataScope;
import com.example.platform.contract.ScopeTier;

/**
 * The single source of tenant/domain/vendor/organization predicates.
 *
 * <p>Every read path goes through {@link ScopedJdbc}, which pastes the predicate produced here in
 * place of a {@code /*scope:alias*}{@code /} marker. Because the predicate is generated (never
 * hand-written), a new query cannot "forget" a dimension: it either carries a marker (and gets all
 * applicable predicates) or it is rejected outright.</p>
 *
 * <p>Parameter names are prefixed with {@code __scope} so a caller-supplied parameter can never
 * shadow the scope, and so two markers on the same statement can share one value.</p>
 */
public final class ScopeSql {

    public static final String TENANT_PARAM = "__scopeTenant";
    public static final String DOMAINS_PARAM = "__scopeDomains";
    public static final String VENDOR_PARAM = "__scopeVendor";
    public static final String ORGS_PARAM = "__scopeOrgs";
    public static final String USER_PARAM = "__scopeUser";

    /**
     * Which columns a table actually has. {@code null} means "this table has no such dimension"
     * (e.g. {@code deploy_order} is tenant-only, {@code release} has no vendor column) — in that
     * case the predicate simply does not restrict that dimension rather than guessing a column.
     */
    private record TableSpec(String domainColumn,
                             String vendorColumn,
                             String vendorColumnAlt,
                             String orgColumn,
                             String ownerColumn) {

        static final TableSpec NONE = new TableSpec(null, null, null, null, null);
    }

    private static final Map<String, TableSpec> TABLES = tables();

    private ScopeSql() {
    }

    private static Map<String, TableSpec> tables() {
        Map<String, TableSpec> map = new LinkedHashMap<>();
        // requirement is the root object: vendor ownership is two columns (owner + assignee).
        map.put("requirement", new TableSpec("domain_id", "owner_vendor_id", "assignee_vendor_id",
                "origin_org_id", "owner_user_id"));
        // release / test_plan are planned per domain; release carries no vendor column.
        map.put("release", new TableSpec("domain_id", null, null, null, null));
        map.put("release_requirement", TableSpec.NONE);
        map.put("defect", new TableSpec("domain_id", "vendor_id", null, null, "reporter_id"));
        map.put("defect_history", TableSpec.NONE);
        map.put("task", new TableSpec("domain_id", "vendor_id", null, null, "assignee_id"));
        map.put("sprint", new TableSpec(null, null, null, null, null));
        map.put("test_case", new TableSpec("domain_id", "vendor_id", null, null, "created_by"));
        map.put("test_plan", new TableSpec("domain_id", "vendor_id", null, null, "created_by"));
        map.put("test_execution", TableSpec.NONE);
        map.put("attachment", new TableSpec("domain_id", "vendor_id", null, null, "uploader_id"));
        map.put("deploy_order", TableSpec.NONE);
        map.put("milestone", TableSpec.NONE);
        // M6 quality domain: strategy/env are planned per domain and (optionally) per vendor;
        // rounds/assignments/changes/report versions hang off a plan or report and carry no
        // isolation column of their own (they are reached through their parent).
        map.put("test_strategy", new TableSpec("domain_id", "vendor_id", null, null, "owner_id"));
        map.put("test_env", new TableSpec("domain_id", "vendor_id", null, null, "owner_id"));
        map.put("test_plan_round", TableSpec.NONE);
        map.put("plan_assignment", TableSpec.NONE);
        map.put("plan_change_log", TableSpec.NONE);
        map.put("test_report", new TableSpec("domain_id", "vendor_id", null, null, "created_by"));
        map.put("test_report_version", TableSpec.NONE);

        // ---- M8 CTeam collaboration: wiki + stage configuration ----
        // audit_log is read by the flow metrics (it is the source of truth for dwell time) and by
        // every audit surface; it carries no domain/vendor column, so a caller only ever gets the
        // tenant predicate here. Registering it is what lets the flow queries keep their marker.
        map.put("audit_log", TableSpec.NONE);
        // A space owns the visibility (INHERIT/DOMAIN/VENDOR); its domain/vendor columns are the
        // ones WikiVisibility projects onto pages. Pages and their children are reached *through*
        // the space, so they must be registered (a marker on an unknown table throws) while
        // carrying no dimension of their own - the space alias keeps the actual restriction.
        map.put("wiki_space", new TableSpec("domain_id", "vendor_id", null, null, "owner_id"));
        map.put("wiki_page", TableSpec.NONE);
        map.put("wiki_page_version", TableSpec.NONE);
        map.put("wiki_comment", TableSpec.NONE);
        map.put("wiki_page_link", TableSpec.NONE);
        // Stage definitions are one dictionary per tenant (they define the flow, not the data).
        map.put("stage_definition", TableSpec.NONE);

        // ---- M8 CCode: branch policy registry ----
        // A policy is registered per domain; its checks hang off it and carry no column.
        map.put("branch_policy", new TableSpec("domain_id", null, null, null, "created_by"));
        map.put("branch_policy_check", TableSpec.NONE);
        map.put("mr_comment", TableSpec.NONE);

        // ---- M8 CTest: reviews + executor ledger ----
        map.put("test_case_review", TableSpec.NONE);
        // Executors are tenant-wide test infrastructure: domain_id is an ownership hint (NULL means
        // "platform level"), not an isolation column - restricting on it would hide the platform
        // executors from every domain caller. Writes are gated by TEST_EXECUTOR_MANAGE instead.
        map.put("test_executor", TableSpec.NONE);
        return Map.copyOf(map);
    }

    /** True when the table is known to the scope generator (unknown tables are rejected). */
    public static boolean knows(String table) {
        return table != null && TABLES.containsKey(normalize(table));
    }

    public static String normalize(String table) {
        return table == null ? "" : table.trim().replace("`", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Predicate for one table alias.
     *
     * @throws IllegalArgumentException for a table the generator has no column knowledge of —
     *                                  guessing would be the leak this class exists to prevent
     */
    public static SqlPredicate forTable(String table, String alias, DataScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scope is required");
        }
        String normalized = normalize(table);
        TableSpec spec = TABLES.get(normalized);
        if (spec == null) {
            throw new IllegalArgumentException("unknown scoped table: " + table
                    + " (add it to ScopeSql with its isolation columns)");
        }
        String prefix = alias == null || alias.isBlank() ? "" : alias + ".";
        List<String> clauses = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();

        clauses.add(prefix + "tenant_id = :" + TENANT_PARAM);
        params.put(TENANT_PARAM, scope.tenantId());

        if (scope.tier() == ScopeTier.TENANT) {
            return new SqlPredicate(String.join(" AND ", clauses), params);
        }

        // ---- self: only rows the caller submitted (二级企业业务「自己提报」) ----
        if (scope.tier() == ScopeTier.SELF) {
            if (spec.ownerColumn() == null) {
                // fail-closed: a table without an owner column cannot express "mine"
                clauses.add("1 = 0");
            } else {
                clauses.add(prefix + spec.ownerColumn() + " = :" + USER_PARAM);
                params.put(USER_PARAM, scope.userId());
            }
            return new SqlPredicate(String.join(" AND ", clauses), params);
        }

        // ---- organization subtree (requirement origin_org_id); BIZ is the business-unit form ----
        if ((scope.tier() == ScopeTier.ORG || scope.tier() == ScopeTier.BIZ)
                && spec.orgColumn() != null) {
            Set<Long> orgs = scope.orgSubtreeIds();
            if (orgs.isEmpty()) {
                // fail-closed: an org-restricted caller without a subtree sees nothing
                clauses.add("1 = 0");
                return new SqlPredicate(String.join(" AND ", clauses), params);
            }
            clauses.add(prefix + spec.orgColumn() + " IN (:" + ORGS_PARAM + ")");
            params.put(ORGS_PARAM, orgs);
            return new SqlPredicate(String.join(" AND ", clauses), params);
        }

        // ---- domain ----
        if (spec.domainColumn() != null) {
            Set<Long> domains = scope.domainIds();
            if (!domains.isEmpty()) {
                clauses.add(prefix + spec.domainColumn() + " IN (:" + DOMAINS_PARAM + ")");
                params.put(DOMAINS_PARAM, domains);
            } else if (scope.tier() == ScopeTier.DOMAIN) {
                clauses.add("1 = 0");
                return new SqlPredicate(String.join(" AND ", clauses), params);
            }
        }

        // ---- vendor ----
        if (scope.tier() == ScopeTier.VENDOR && scope.vendorId() != null) {
            if (spec.vendorColumn() != null && spec.vendorColumnAlt() != null) {
                clauses.add("(" + prefix + spec.vendorColumn() + " = :" + VENDOR_PARAM
                        + " OR " + prefix + spec.vendorColumnAlt() + " = :" + VENDOR_PARAM + ")");
                params.put(VENDOR_PARAM, scope.vendorId());
            } else if (spec.vendorColumn() != null) {
                clauses.add(prefix + spec.vendorColumn() + " = :" + VENDOR_PARAM);
                params.put(VENDOR_PARAM, scope.vendorId());
            }
            // no vendor column on this table: tenant (+ domain when present) already applies
        }

        return new SqlPredicate(String.join(" AND ", clauses), params);
    }
}
