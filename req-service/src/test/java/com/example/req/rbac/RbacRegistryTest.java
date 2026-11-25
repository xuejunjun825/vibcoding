package com.example.req.rbac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.platform.contract.ActionRegistry;
import com.example.platform.contract.MenuRegistry;
import com.example.platform.contract.PermissionMatrix;
import org.junit.jupiter.api.Test;

/**
 * The two registries are the contract every other layer derives from — the seed, the route guard,
 * the admin tree and the frontend nav. A typo here is a permission that silently never fires, so
 * each invariant the design leans on is asserted once, here.
 */
class RbacRegistryTest {

    @Test
    void pageRegistryHasThirtyEightUniquePagesInSixModules() {
        assertEquals(38, MenuRegistry.pages().size(), "r41 appends the pdr.review page to r39's 37");
        assertEquals(38, new HashSet<>(MenuRegistry.codes()).size(), "page codes must be unique");
        assertEquals(6, MenuRegistry.modules().size(), "38 pages grouped in 6 modules");
        assertEquals(37, MenuRegistry.pages().stream()
                .map(MenuRegistry.MenuPage::routeName).distinct().count(),
                "route names are unique except /requirements, which two pages share");
        assertTrue(MenuRegistry.isRegistered("admin.roles"));
        assertFalse(MenuRegistry.isRegistered("admin.notifications"),
                "/notifications is deliberately not an authorisable page");
    }

    @Test
    void actionRegistryHasEightyOneUniqueActions() {
        assertEquals(81, ActionRegistry.actions().size(),
                "r41 appends 6 PDR-review/vendor actions to r40's 75");
        assertEquals(81, new HashSet<>(ActionRegistry.codes()).size(), "action codes must be unique");
        assertEquals(ActionRegistry.codes(),
                ActionRegistry.actions().stream().map(ActionRegistry.ActionDef::code).toList(),
                "codes() must follow the registry order the admin table renders");
    }

    @Test
    void everyActionBelongsToARegisteredPage() {
        for (ActionRegistry.ActionDef action : ActionRegistry.actions()) {
            assertTrue(MenuRegistry.isRegistered(action.menuCode()),
                    action.code() + " points at unregistered page " + action.menuCode());
            assertFalse(action.functionCodes().isEmpty(),
                    action.code() + " must name the function codes that grant it");
            assertTrue(action.code().contains(".action."),
                    action.code() + " must be namespaced <module>.action.<verb>");
        }
    }

    @Test
    void everyReferencedFunctionCodeExistsInTheMatrix() {
        Set<String> known = new HashSet<>(PermissionMatrix.functions());
        for (MenuRegistry.MenuPage page : MenuRegistry.pages()) {
            for (String function : page.functionCodes()) {
                assertTrue(known.contains(function),
                        page.code() + " references unknown function code " + function);
            }
        }
        for (ActionRegistry.ActionDef action : ActionRegistry.actions()) {
            for (String function : action.functionCodes()) {
                assertTrue(known.contains(function),
                        action.code() + " references unknown function code " + function);
            }
        }
    }

    @Test
    void exactlyThreeActionsAreHighRisk() {
        assertEquals(3, ActionRegistry.HIGH_RISK.size(), "force-release, user-disable, role-grant");
        assertEquals(Set.of("deploy.action.force_release", "user.action.disable", "role.action.grant"),
                ActionRegistry.HIGH_RISK);
        for (String code : ActionRegistry.HIGH_RISK) {
            assertTrue(ActionRegistry.require(code).highRisk(), code + " must be flagged high risk");
        }
        assertEquals(3, ActionRegistry.actions().stream()
                .filter(ActionRegistry.ActionDef::highRisk).count());
    }

    @Test
    void actionsAreGroupedUnderTheirPageInSortOrder() {
        for (MenuRegistry.MenuPage page : MenuRegistry.pages()) {
            List<ActionRegistry.ActionDef> actions = ActionRegistry.of(page.code());
            List<Integer> sorts = actions.stream().map(ActionRegistry.ActionDef::sort).toList();
            assertEquals(sorts.stream().sorted().toList(), sorts,
                    page.code() + " actions must come back in sort order");
        }
    }

    @Test
    void unboundFunctionCodesAreTheComplementOfPageBindings() {
        List<String> all = PermissionMatrix.functions();
        Set<String> bound = new HashSet<>();
        for (MenuRegistry.MenuPage page : MenuRegistry.pages()) {
            bound.addAll(page.functionCodes());
        }
        List<String> unbound = MenuRegistry.unboundFunctionCodes();
        assertEquals(61, all.size(), "the matrix authorises 61 function codes (r39 +1)");
        assertEquals(31, bound.size(), "31 are bound to a page");
        assertEquals(30, unbound.size(), "the other 30 are the group the admin tree now shows");
        assertEquals(all.size(), bound.size() + unbound.size(), "bound + unbound == 61");
        assertTrue(new HashSet<>(all).containsAll(unbound), "unbound must come from the matrix");
        Set<String> overlap = new HashSet<>(bound);
        overlap.retainAll(unbound);
        assertTrue(overlap.isEmpty(), "a code cannot be both bound and unbound: " + overlap);
        assertEquals(all.stream().filter(unbound::contains).toList(), unbound,
                "unbound keeps PermissionMatrix.functions() order");
    }
}
