package com.example.req.rbac;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.example.platform.contract.ActionRegistry;
import com.example.platform.contract.MenuRegistry;
import com.example.platform.contract.PermissionMatrix;
import com.example.platform.contract.PermissionMatrix.Access;
import com.example.req.seed.RbacSeeder;
import org.junit.jupiter.api.Test;

/**
 * AC-3 as executable evidence: the 1903 seeded rows per tenant are not hand-written, they are
 * derived — {@code FUNCTION = the matrix cell}, {@code MENU = max(function codes the page binds)},
 * {@code ACTION = max(function codes the action needs) unless the role carries an explicit
 * override}. If the derivation ever drifts from the matrix, upgrading quietly changes who can do
 * what, so the equality is asserted per cell and summarised as one canonical hash the database-side
 * check (s2) recomputes from real rows.
 */
class RbacSeedEquivalenceTest {

    @Test
    void derivesFromTheMatrixPerRoleAndPage() {
        for (String role : PermissionMatrix.roles()) {
            for (MenuRegistry.MenuPage page : MenuRegistry.pages()) {
                Access expected = max(role, page.functionCodes());
                Access actual = PermissionMatrix.accessForAny(role, page.functionCodes());
                assertEquals(expected, actual, "page " + page.code() + " for role " + role);
            }
        }
    }

    @Test
    void seedMatchesActionAccessFor() {
        for (String role : PermissionMatrix.roles()) {
            Set<String> overrides = RbacSeeder.actionDenyOverrides(role);
            for (ActionRegistry.ActionDef action : ActionRegistry.actions()) {
                Access expected = overrides.contains(action.code())
                        ? Access.DENY : max(role, action.functionCodes());
                Access actual = RbacSeeder.actionAccessFor(role, action);
                assertEquals(expected, actual, "action " + action.code() + " for role " + role);
            }
        }
    }

    /**
     * r24: the 11 overrides must be real overrides, not no-ops. Each one derives ALLOW from its
     * page-admission function code, so an override that silently stopped applying (a typo in the
     * code, a role mismatch) would hand VENDOR_PM a write capability and this test is the one place
     * that notices before the seeded rows do.
     */
    @Test
    void vendorPmCarriesThirteenRealActionOverrides() {
        // r24 的 11 条是「派生会放行、所以必须显式拒绝」；r40 新增的 2 条变更动作当前派生就是
        // DENY（VENDOR_PM 没有 req:split/req:deliver），显式列出是防功能码表以后扩码时静默放行。
        // 两类都断言最终 DENY；只有第一类额外断言「派生原本是 ALLOW」，那才是 no-op 的判据。
        Set<String> derivedAllow = Set.of(
                "release.action.create", "release.action.update",
                "release.action.approve", "release.action.close",
                "task.action.create", "task.action.assign",
                "task.action.update", "task.action.close",
                "sprint.action.create", "sprint.action.close",
                "test.action.case_edit");
        Set<String> expected = new java.util.LinkedHashSet<>(derivedAllow);
        expected.add("req.action.change_request");
        expected.add("req.action.change_apply");
        assertEquals(Set.copyOf(expected), RbacSeeder.actionDenyOverrides("VENDOR_PM"));
        for (String code : expected) {
            ActionRegistry.ActionDef action = ActionRegistry.actions().stream()
                    .filter(candidate -> candidate.code().equals(code)).findFirst().orElseThrow();
            if (derivedAllow.contains(code)) {
                assertEquals(Access.ALLOW, max("VENDOR_PM", action.functionCodes()),
                        code + " must derive ALLOW from its function codes, or the override is a no-op");
            }
            assertEquals(Access.DENY, RbacSeeder.actionAccessFor("VENDOR_PM", action),
                    code + " must be DENY for VENDOR_PM");
        }
        // Nobody else carries overrides: the 10 pre-existing rows keep the pure max derivation.
        for (String role : PermissionMatrix.roles()) {
            if ("VENDOR_PM".equals(role)) {
                continue;
            }
            for (ActionRegistry.ActionDef action : ActionRegistry.actions()) {
                assertEquals(max(role, action.functionCodes()),
                        RbacSeeder.actionAccessFor(role, action),
                        "role " + role + " must stay on the max derivation");
            }
        }
        long allowed = ActionRegistry.actions().stream()
                .filter(action -> RbacSeeder.actionAccessFor("VENDOR_PM", action) == Access.ALLOW)
                .count();
        assertEquals(8L, allowed,
                "VENDOR_PM keeps 8 actions (81 − 13 overrides − 60 derived DENY)");
    }

    /** A page that binds no function code is not narrowed by the function layer. */
    @Test
    void pagesWithoutFunctionCodesAreOpenAtTheMenuLayer() {
        for (MenuRegistry.MenuPage page : MenuRegistry.pages()) {
            if (page.functionCodes().isEmpty()) {
                for (String role : PermissionMatrix.roles()) {
                    assertEquals(Access.ALLOW, PermissionMatrix.accessForAny(role, List.of()),
                            page.code() + " binds no code, so it must derive ALLOW, not DENY");
                }
            }
        }
    }

    @Test
    void canonicalHashIsStable() {
        String first = canonicalHash();
        String second = canonicalHash();
        assertEquals(first, second, "the canonical form must not depend on map iteration order");
        assertEquals(64, first.length(), "SHA-256 hex");
        System.out.println("[rbac-seed] canonical sha256 = " + first);
        System.out.println("[rbac-seed] rows = " + rowCount());
    }

    @Test
    void totalRowsAreNineteenHundredEighty() {
        assertEquals(1980L, rowCount(),
                "11 roles × (61 functions + 38 pages + 81 actions) seeded rows per tenant");
    }

    private static long rowCount() {
        return (long) PermissionMatrix.roles().size()
                * (PermissionMatrix.functions().size() + MenuRegistry.pages().size()
                + ActionRegistry.actions().size());
    }

    /** Stable text form: {@code ROLE|LAYER|code|access}, one line per seeded row, sorted. */
    static String canonicalForm() {
        List<String> lines = new ArrayList<>();
        for (String role : PermissionMatrix.roles()) {
            for (String function : PermissionMatrix.functions()) {
                lines.add(role + "|FUNCTION|" + function + "|"
                        + PermissionMatrix.accessFor(role, function).name());
            }
            for (MenuRegistry.MenuPage page : MenuRegistry.pages()) {
                lines.add(role + "|MENU|" + page.code() + "|"
                        + PermissionMatrix.accessForAny(role, page.functionCodes()).name());
            }
            for (ActionRegistry.ActionDef action : ActionRegistry.actions()) {
                lines.add(role + "|ACTION|" + action.code() + "|"
                        + RbacSeeder.actionAccessFor(role, action).name());
            }
        }
        lines.sort(String::compareTo);
        return String.join("\n", lines);
    }

    static String canonicalHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalForm().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** {@code max} of the levels of the given codes, ALLOW when the list is empty. */
    static Access max(String role, List<String> functions) {
        if (functions == null || functions.isEmpty()) {
            return Access.ALLOW;
        }
        Access result = Access.DENY;
        for (String function : functions) {
            result = PermissionMatrix.Access.max(result, PermissionMatrix.accessFor(role, function));
        }
        return result;
    }

    /** Kept for the s2 checker: the derivation of one role as a map, handy for ad-hoc inspection. */
    static Map<String, String> roleRow(String role) {
        List<String> lines = canonicalForm().lines()
                .filter(line -> line.startsWith(role + "|")).toList();
        return lines.stream().collect(java.util.stream.Collectors.toMap(
                line -> line.split("\\|")[1] + ":" + line.split("\\|")[2],
                line -> line.split("\\|")[3], (left, right) -> left,
                java.util.LinkedHashMap::new));
    }
}
