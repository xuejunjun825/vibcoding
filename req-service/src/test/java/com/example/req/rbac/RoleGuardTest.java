package com.example.req.rbac;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.platform.common.error.ApiException;
import com.example.platform.contract.PermissionMatrix.Access;
import com.example.req.sys.RoleGuard;
import com.example.req.sys.SysViews;
import org.junit.jupiter.api.Test;

/**
 * The two rules that keep the permission editor from bricking a tenant, tested without a database:
 * the lockout refusal and the high-risk second confirmation.
 */
class RoleGuardTest {

    @Test
    void adminRoleMustKeepBothAdminCodes() {
        Map<String, Access> draft = new LinkedHashMap<>();
        draft.put("org:manage", Access.DENY);
        draft.put("tenant:manage", Access.ALLOW);
        ApiException ex = assertThrows(ApiException.class,
                () -> RoleGuard.assertNoLockout(draft, 3));
        assertEquals(409, ex.status());
        assertEquals("ADMIN_LOCKOUT_RISK", ex.code());
    }

    @Test
    void tenantWithNoActiveAdminIsALockoutToo() {
        Map<String, Access> draft = Map.of("org:manage", Access.ALLOW,
                "tenant:manage", Access.ALLOW);
        assertThrows(ApiException.class, () -> RoleGuard.assertNoLockout(draft, 0));
    }

    @Test
    void unrelatedSavesDoNotTriggerTheLockoutRule() {
        assertDoesNotThrow(() -> RoleGuard.assertNoLockout(Map.of(), 0),
                "an empty draft means the save does not touch the admin role");
        assertDoesNotThrow(() -> RoleGuard.assertNoLockout(
                Map.of("org:manage", Access.ALLOW, "tenant:manage", Access.RESTRICTED), 1));
    }

    @Test
    void highRiskConfirmationOnlyForNewGrants() {
        List<SysViews.GrantInput> grants = List.of(
                new SysViews.GrantInput("ACTION", "deploy.action.force_release", "ALLOW"),
                new SysViews.GrantInput("ACTION", "req.action.approve", "ALLOW"),
                new SysViews.GrantInput("MENU", "deploy.orders", "ALLOW"));
        assertEquals(List.of("deploy.action.force_release"),
                RoleGuard.highRiskGrants("OPS", grants),
                "only the newly granted high-risk action needs a confirmation");

        Map<String, String> before = Map.of("deploy.action.force_release", "ALLOW");
        assertTrue(RoleGuard.highRiskGrants("OPS", grants, before).isEmpty(),
                "re-saving an unchanged grant must not nag a second time");

        Map<String, String> wasDenied = Map.of("deploy.action.force_release", "DENY");
        assertEquals(List.of("deploy.action.force_release"),
                RoleGuard.highRiskGrants("OPS", grants, wasDenied));
    }

    @Test
    void revokingAHighRiskActionIsNotAConfirmation() {
        List<SysViews.GrantInput> grants = List.of(
                new SysViews.GrantInput("ACTION", "role.action.grant", "DENY"));
        assertTrue(RoleGuard.highRiskGrants("PMO", grants).isEmpty());
    }

    @Test
    void unknownSubjectsAreRejectedBeforeAnyWrite() {
        ApiException unknownPage = assertThrows(ApiException.class,
                () -> RoleGuard.validateSubject("MENU", "admin.nope"));
        assertEquals("UNKNOWN_SUBJECT", unknownPage.code());
        assertEquals(400, unknownPage.status());

        ApiException unknownType = assertThrows(ApiException.class,
                () -> RoleGuard.validateSubject("BUTTON", "x"));
        assertEquals("UNKNOWN_SUBJECT", unknownType.code());

        assertDoesNotThrow(() -> RoleGuard.validateSubject("ACTION", "role.action.grant"));
        assertDoesNotThrow(() -> RoleGuard.validateSubject("FUNCTION", "org:manage"));
        assertDoesNotThrow(() -> RoleGuard.validateSubject("MENU", "admin.roles"));
    }

    @Test
    void accessParsingFallsBackToDeny() {
        assertEquals(Access.DENY, RoleGuard.parse(null));
        assertEquals(Access.DENY, RoleGuard.parse("nonsense"));
        assertEquals(Access.RESTRICTED, RoleGuard.parse("restricted"));
    }
}
