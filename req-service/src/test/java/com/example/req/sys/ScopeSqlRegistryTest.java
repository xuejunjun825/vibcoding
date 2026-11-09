package com.example.req.sys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import com.example.platform.common.scope.ScopeSql;
import com.example.platform.common.scope.SqlPredicate;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.ScopeTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M8: every table the new services query through {@code ScopedJdbc} must be registered, with the
 * isolation columns that actually exist on it.
 *
 * <p>This is a registry test, not a query test: {@code ScopedJdbc} throws
 * {@code unknown scoped table} for anything missing here, so a forgotten registration is a 500 on
 * the first request of a new page. The assertions also pin the two deliberate <b>absences</b> —
 * {@code audit_log} and {@code test_executor} carry no domain/vendor predicate — because adding one
 * later would silently hide every row from every caller outside a domain.</p>
 */
class ScopeSqlRegistryTest {

    private static final List<String> M8_TABLES = List.of("audit_log", "wiki_space", "wiki_page",
            "wiki_page_version", "wiki_comment", "wiki_page_link", "stage_definition",
            "branch_policy", "branch_policy_check", "mr_comment", "test_case_review",
            "test_executor");

    @Test
    @DisplayName("all M8 tables are registered (an unregistered one is an unknown scoped table)")
    void everyCollaborationTableIsRegistered() {
        for (String table : M8_TABLES) {
            assertTrue(ScopeSql.knows(table), "missing scope registration: " + table);
        }
        // The M6/M7 tables this feature reads through a marker must stay registered too.
        for (String table : List.of("requirement", "release", "test_plan", "test_case",
                "attachment")) {
            assertTrue(ScopeSql.knows(table), "regression: " + table + " lost its registration");
        }
    }

    @Test
    @DisplayName("an unregistered table still fails closed with the documented message")
    void unknownTableFailsClosed() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ScopeSql.forTable("wiki_page_attachment", "x", DataScope.system(1L)));
        assertTrue(ex.getMessage().contains("unknown scoped table"), ex.getMessage());
    }

    @Test
    @DisplayName("audit_log is tenant-only: the flow metrics read every domain's transitions")
    void auditLogHasNoDimension() {
        DataScope vendor = DataScope.of(1L, ScopeTier.VENDOR, Set.of(9L), 5L, 7L, Set.of());
        SqlPredicate predicate = ScopeSql.forTable("audit_log", "al", vendor);
        assertEquals("al.tenant_id = :__scopeTenant", predicate.sql());
        assertEquals(1L, predicate.params().get("__scopeTenant"));
        assertFalse(predicate.sql().contains("domain_id"), "audit_log has no domain column");
        assertFalse(predicate.sql().contains("vendor_id"), "audit_log has no vendor column");
    }

    @Test
    @DisplayName("wiki_space carries domain × vendor; wiki_page inherits it through the space alias")
    void wikiIsolationFollowsTheSpace() {
        DataScope domain = DataScope.of(1L, ScopeTier.DOMAIN, Set.of(9L), null, 7L, Set.of());
        String space = ScopeSql.forTable("wiki_space", "s", domain).sql();
        assertTrue(space.contains("s.tenant_id = :"), space);
        assertTrue(space.contains("s.domain_id IN (:"), space);

        DataScope vendor = DataScope.of(1L, ScopeTier.VENDOR, Set.of(9L), 5L, 7L, Set.of());
        String vendorSpace = ScopeSql.forTable("wiki_space", "s", vendor).sql();
        assertTrue(vendorSpace.contains("s.vendor_id = :"), vendorSpace);

        // A page has no dimension of its own: the marker on the space alias is what narrows it, so
        // the page predicate must stay dimension-free (otherwise a DOMAIN caller would get 1 = 0).
        String page = ScopeSql.forTable("wiki_page", "p", domain).sql();
        assertEquals("p.tenant_id = :__scopeTenant", page);
    }

    @Test
    @DisplayName("a page queried under a SELF scope sees nothing (no owner column, fail-closed)")
    void selfScopeOnAPageIsEmpty() {
        DataScope self = DataScope.of(1L, ScopeTier.SELF, Set.of(), null, 7L, Set.of());
        assertTrue(ScopeSql.forTable("wiki_page", "p", self).sql().contains("1 = 0"));
    }

    @Test
    @DisplayName("branch_policy is per-domain; its checks are reached through the policy")
    void branchPolicyIsPerDomain() {
        DataScope domain = DataScope.of(1L, ScopeTier.DOMAIN, Set.of(9L), null, 7L, Set.of());
        String policy = ScopeSql.forTable("branch_policy", "bp", domain).sql();
        assertTrue(policy.contains("bp.domain_id IN (:"), policy);
        assertEquals("bc.tenant_id = :__scopeTenant",
                ScopeSql.forTable("branch_policy_check", "bc", domain).sql());
    }

    @Test
    @DisplayName("test_executor is tenant-wide: domain_id is an ownership hint, not a filter")
    void executorIsTenantWide() {
        DataScope domain = DataScope.of(1L, ScopeTier.DOMAIN, Set.of(9L), null, 7L, Set.of());
        String sql = ScopeSql.forTable("test_executor", "e", domain).sql();
        assertEquals("e.tenant_id = :__scopeTenant", sql,
                "filtering executors by domain would hide the platform-level ones from everyone");
    }

    @Test
    @DisplayName("stage_definition is one dictionary per tenant, not per domain")
    void stageDefinitionIsTenantWide() {
        DataScope domain = DataScope.of(1L, ScopeTier.DOMAIN, Set.of(9L), null, 7L, Set.of());
        assertEquals("sd.tenant_id = :__scopeTenant",
                ScopeSql.forTable("stage_definition", "sd", domain).sql());
    }
}
