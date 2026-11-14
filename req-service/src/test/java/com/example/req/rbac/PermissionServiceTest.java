package com.example.req.rbac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.platform.common.security.PermissionGrantsRepository;
import com.example.platform.common.security.PermissionService;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.PermissionMatrix.Access;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The single decision point: union across roles, the three-sequence order, and the epoch that makes
 * a revocation visible on the very next request.
 */
class PermissionServiceTest {

    private static final long TENANT = 42L;

    private FakeRepository repository;
    private PermissionService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(DataScope.system(TENANT));
        TenantContext.setPrincipal(new TenantContext.Principal(7L, "pm", "产品经理", "acme", "Acme",
                List.of("PRODUCT", "QA"), Set.of(), null));
        repository = new FakeRepository();
        service = new PermissionService(repository, 45);
    }

    @AfterEach
    void tearDown() {
        service.clearRequestMemo();
        TenantContext.clear();
    }

    @Test
    void anyDenyWins() {
        repository.put("PRODUCT", PermissionService.Layer.FUNCTION, "req:review", Access.ALLOW);
        repository.put("QA", PermissionService.Layer.FUNCTION, "req:review", Access.DENY);
        assertFalse(service.canFunction("req:review"),
                "one denying role must beat a granting role — adding a role never widens access");
    }

    @Test
    void threeSequenceOrder() {
        repository.put("PRODUCT", PermissionService.Layer.MENU, "req.pool", Access.ALLOW);
        repository.put("PRODUCT", PermissionService.Layer.ACTION, "req.action.approve", Access.ALLOW);
        assertEquals(Access.ALLOW, service.decide("req.pool", "req.action.approve"));

        // Each put below stands for a grant change committed in the database, so the snapshot and
        // the per-request memo are dropped the way the admin save does it.
        repository.put("PRODUCT", PermissionService.Layer.MENU, "req.pool", Access.DENY);
        service.invalidate(TENANT);
        assertEquals(Access.DENY, service.decide("req.pool", "req.action.approve"),
                "a denied page refuses every action on it, however well the action is granted");

        repository.put("PRODUCT", PermissionService.Layer.MENU, "req.pool", Access.ALLOW);
        repository.put("PRODUCT", PermissionService.Layer.ACTION, "req.action.approve", Access.DENY);
        service.invalidate(TENANT);
        assertEquals(Access.DENY, service.decide("req.pool", "req.action.approve"));

        repository.put("PRODUCT", PermissionService.Layer.ACTION, "req.action.approve",
                Access.RESTRICTED);
        service.invalidate(TENANT);
        assertEquals(Access.RESTRICTED, service.decide("req.pool", "req.action.approve"),
                "RESTRICTED passes the gate; the data scope narrows the rows afterwards");
    }

    @Test
    void invalidateBumpsEpoch() {
        assertEquals(0L, service.epoch());
        service.invalidate(TENANT);
        assertEquals(1L, service.epoch(), "a revocation must be visible to the next request");
        service.invalidate(TENANT);
        assertEquals(2L, service.epoch());
    }

    @Test
    void snapshotIsCachedForTheRequestAndClearedAfterwards() {
        repository.put("PRODUCT", PermissionService.Layer.MENU, "req.pool", Access.ALLOW);
        assertTrue(service.canMenu("req.pool"));
        assertTrue(service.canMenu("req.pool"));
        assertEquals(1, repository.loads.get(), "a second check within one request must not hit SQL");

        repository.clear();
        service.invalidate(TENANT);
        assertFalse(service.canMenu("req.pool"),
                "after the epoch moved the next read must reload, and the row is gone");
        assertTrue(repository.loads.get() >= 2, "the reload must actually happen");
    }

    @Test
    void grantedSkipsDeniedCodes() {
        repository.put("PRODUCT", PermissionService.Layer.MENU, "req.pool", Access.ALLOW);
        repository.put("QA", PermissionService.Layer.MENU, "defect.list", Access.RESTRICTED);
        repository.put("QA", PermissionService.Layer.MENU, "test.cases", Access.DENY);
        assertEquals(List.of("req.pool", "defect.list"), service.granted(PermissionService.Layer.MENU),
                "RESTRICTED is still granted — the page is visible, its data is narrowed");
        assertEquals(List.of("defect.list"), service.restricted(PermissionService.Layer.MENU));
    }

    /** A repository double: the real one needs a live {@code sys_role_permission} table. */
    private static final class FakeRepository extends PermissionGrantsRepository {

        private final Map<String, Map<PermissionService.Layer, Map<String, Access>>> rows =
                new LinkedHashMap<>();
        private final AtomicInteger loads = new AtomicInteger();

        FakeRepository() {
            super(new JdbcTemplate());
        }

        void put(String role, PermissionService.Layer layer, String code, Access access) {
            rows.computeIfAbsent(role, key -> new LinkedHashMap<>())
                    .computeIfAbsent(layer, key -> new LinkedHashMap<>())
                    .put(code, access);
        }

        void clear() {
            rows.clear();
        }

        @Override
        public PermissionService.Snapshot load(long tenantId, long epoch) {
            loads.incrementAndGet();
            Map<PermissionService.Layer, Map<String, Map<String, Access>>> byRole =
                    new LinkedHashMap<>();
            for (PermissionService.Layer layer : PermissionService.Layer.values()) {
                byRole.put(layer, new LinkedHashMap<>());
            }
            rows.forEach((role, layers) -> layers.forEach((layer, codes) ->
                    byRole.get(layer).put(role, new LinkedHashMap<>(codes))));
            return new PermissionService.Snapshot(tenantId, epoch, System.currentTimeMillis(), byRole);
        }
    }
}
