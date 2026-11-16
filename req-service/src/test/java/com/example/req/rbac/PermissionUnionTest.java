package com.example.req.rbac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.platform.common.security.PermissionService;
import com.example.platform.contract.PermissionMatrix.Access;
import org.junit.jupiter.api.Test;

/**
 * The union rule is the whole of r18's multi-role semantics, so it is pinned exhaustively here:
 * a missing row denies, one explicit DENY beats any number of grants, and otherwise the more
 * permissive level wins.
 */
class PermissionUnionTest {

    @Test
    void missingRowsDenyEverything() {
        assertTrue(PermissionService.merge(List.of()).isEmpty(),
                "no rows means nothing is granted — the service treats absent as DENY");
    }

    @Test
    void oneDenyBeatsEveryGrant() {
        Map<String, Access> a = Map.of("req:review", Access.ALLOW);
        Map<String, Access> b = Map.of("req:review", Access.DENY);
        Map<String, Access> merged = PermissionService.merge(List.of(a, b));
        assertEquals(Access.DENY, merged.get("req:review"),
                "giving a user one more role must never widen a code another role denies");
    }

    @Test
    void otherwiseTheMorePermissiveLevelWins() {
        assertEquals(Access.ALLOW, PermissionService.union(Access.RESTRICTED, Access.ALLOW));
        assertEquals(Access.DENY, PermissionService.union(Access.RESTRICTED, Access.DENY),
                "DENY is the only level that wins over a grant");
        assertEquals(Access.RESTRICTED, PermissionService.union(Access.RESTRICTED, Access.RESTRICTED));
    }

    @Test
    void unionKeepsEveryCodeAcrossRoles() {
        Map<String, Access> a = new LinkedHashMap<>();
        a.put("org:manage", Access.ALLOW);
        Map<String, Access> b = new LinkedHashMap<>();
        b.put("deploy:force", Access.RESTRICTED);
        Map<String, Access> merged = PermissionService.merge(List.of(a, b));
        assertEquals(Access.ALLOW, merged.get("org:manage"));
        assertEquals(Access.RESTRICTED, merged.get("deploy:force"));
        assertEquals(2, merged.size());
    }
}
