package com.example.req.sys;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The scope marker is a mechanical guard (AC-4): a query that forgot it throws before reaching the
 * database instead of silently returning every tenant's rows.
 *
 * <p>Assertions run without a database: the guard fires during statement rewriting, which is where
 * a missing marker has to be caught.</p>
 */
class ScopedJdbcGuardTest {

    private final ScopedJdbc scoped = new ScopedJdbc(null);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void missingMarkerThrows() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> scoped.list("SELECT * FROM requirement r", Map.of()));
        assertTrue(ex.getMessage().contains(ScopedJdbc.MISSING_MARKER),
                "expected the documented fail-closed message, got: " + ex.getMessage());
        assertThrows(IllegalStateException.class,
                () -> scoped.count("SELECT COUNT(*) FROM defect d", Map.of()));
        assertThrows(IllegalStateException.class,
                () -> scoped.page("SELECT * FROM test_plan p", Map.of(), 1, 10));
    }

    @Test
    void markerOnAnUnknownAliasThrows() {
        TenantContext.set(DataScope.system(1L));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> scoped.list("SELECT * FROM requirement r WHERE /*scope:x*/", Map.of()));
        assertTrue(ex.getMessage().contains("not bound"), ex.getMessage());
    }

    @Test
    void writesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> scoped.list("DELETE FROM requirement WHERE /*scope:r*/", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> scoped.list("UPDATE requirement SET title = 'x' WHERE /*scope:r*/", Map.of()));
    }

    @Test
    void missingTenantContextThrowsInsteadOfUnfilteredRead() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> scoped.list("SELECT * FROM requirement r WHERE /*scope:r*/", Map.of()));
        assertTrue(ex.getMessage().contains("TenantContext"), ex.getMessage());
    }
}
