package com.example.req.sys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.ScopeTier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Fail-closed behaviour: business code that never got a scope must throw, not see everything. */
class TenantContextTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void requireWithoutScopeThrows() {
        assertFalse(TenantContext.isSet());
        IllegalStateException ex = assertThrows(IllegalStateException.class, TenantContext::require);
        assertTrue(ex.getMessage().contains("AuthFilter"), "the message must name the missing filter");
        assertNull(TenantContext.scopeOrNull());
        assertNull(TenantContext.principal());
    }

    @Test
    void scopeAndPrincipalRoundTrip() {
        DataScope scope = DataScope.of(7L, ScopeTier.VENDOR, Set.of(1L, 2L), 42L, 9L, Set.of());
        TenantContext.set(scope);
        TenantContext.setPrincipal(new TenantContext.Principal(9L, "dev1", "开发一号", "t1", "大供应链",
                List.of("DEV"), Set.of(1L, 2L), 42L));

        assertEquals(7L, TenantContext.require().tenantId());
        assertEquals(9L, TenantContext.require().userId());
        assertEquals("dev1", TenantContext.username());
        assertEquals("t1", TenantContext.tenantCode());
        assertNotNull(TenantContext.principal());
        assertTrue(TenantContext.can("req:deliver"));
        assertFalse(TenantContext.can("tenant:manage"));

        TenantContext.clear();
        assertThrows(IllegalStateException.class, TenantContext::require);
        assertNull(TenantContext.principal());
    }
}
