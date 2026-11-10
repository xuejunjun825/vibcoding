package com.example.deliver.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.deliver.analytics.AnalyticsRequest;
import com.example.deliver.analytics.AnalyticsViews;
import com.example.deliver.reports.ReportLookup;
import com.example.platform.common.audit.AuditService;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;

/**
 * Gate for QA D-6 / AC-13: one section throwing must degrade that section only, with a
 * user-readable reason — never a Java class name — and the other four sections stay OK.
 */
class PortalServiceTest {

    private PortalCardConfigRepository configs;
    private ReportLookup reports;
    private AuditService audit;
    private PortalService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(DataScope.system(1L));
        TenantContext.setPrincipal(new TenantContext.Principal(
                7L, "po1", "业务负责人", "t1", "租户一",
                List.of("SUB_BUSINESS_LEADER"), Set.of(), null));
        configs = mock(PortalCardConfigRepository.class);
        when(configs.findByTenantIdAndRoleCodeAndUserId(anyLong(), anyString(), anyLong()))
                .thenReturn(Optional.empty());
        when(configs.findByTenantIdAndUserIdOrderByIdAsc(anyLong(), anyLong()))
                .thenReturn(List.of());
        reports = mock(ReportLookup.class);
        audit = mock(AuditService.class);

        List<PortalSectionProvider> providers = new ArrayList<>();
        providers.add(new OkProvider("todo", 1));
        providers.add(new OkProvider("actions", 2));
        providers.add(new FailingProvider("metrics", 3));
        providers.add(new OkProvider("list", 4));
        providers.add(new OkProvider("messages", 5));
        service = new PortalService(providers, configs, reports, audit);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("a failing section is DEGRADED with a readable reason; the other four stay OK")
    void oneSectionDegradesWithoutBlankPage() {
        String[] cache = new String[1];
        PortalViews.Portal portal = service.portal((AnalyticsRequest) null, cache);

        assertEquals(5, portal.sections().size(), "five sections are always present");
        assertEquals(List.of("todo", "actions", "metrics", "list", "messages"),
                portal.sections().stream().map(PortalViews.Section::key).toList(),
                "providers are assembled in order()");

        PortalViews.Section degraded = portal.sections().get(2);
        assertEquals(PortalViews.DEGRADED, degraded.state());
        assertTrue(degraded.cards().isEmpty(), "a degraded section renders no cards");
        assertEquals("该区块数据暂时不可用，请稍后重试", degraded.error());
        assertFalse(degraded.error().contains("Exception"), "never leak a Java class name");

        for (int i : new int[] {0, 1, 3, 4}) {
            assertEquals(PortalViews.OK, portal.sections().get(i).state(),
                    "section " + portal.sections().get(i).key() + " must stay OK");
        }
    }

    private record OkProvider(String key, int order) implements PortalSectionProvider {
        @Override
        public PortalViews.Section build(PortalContext ctx) {
            return new PortalViews.Section(key, key, PortalViews.OK, null, List.of());
        }
    }

    private record FailingProvider(String key, int order) implements PortalSectionProvider {
        @Override
        public PortalViews.Section build(PortalContext ctx) {
            throw new IllegalStateException("metrics backend down");
        }
    }
}
