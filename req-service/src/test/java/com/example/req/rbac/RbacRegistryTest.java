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
    void pageRegistryHasThirtySixUniquePagesInSixModules() {
        assertEquals(36, MenuRegistry.pages().size(), "r18 authorises exactly 36 pages");
        assertEquals(36, new HashSet<>(MenuRegistry.codes()).size(), "page codes must be unique");
        assertEquals(6, MenuRegistry.modules().size(), "36 pages grouped in 6 modules");
        assertEquals(35, MenuRegistry.pages().stream()
                .map(MenuRegistry.MenuPage::routeName).distinct().count(),
                "route names are unique except /requirements, which two pages share");
        assertTrue(MenuRegistry.isRegistered("admin.roles"));
        assertFalse(MenuRegistry.isRegistered("admin.notifications"),
                "/notifications is deliberately not an authorisable page");
    }

    @Test
    void actionRegistryHasSeventyTwoUniqueActions() {
        assertEquals(72, ActionRegistry.actions().size(), "60 verbs over 36 pages collapse to 72");
        assertEquals(72, new HashSet<>(ActionRegistry.codes()).size(), "action codes must be unique");
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
}
