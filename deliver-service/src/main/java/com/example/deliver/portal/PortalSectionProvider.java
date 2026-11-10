package com.example.deliver.portal;

import java.util.List;

import com.example.deliver.analytics.AnalyticsRequest;
import com.example.deliver.analytics.AnalyticsViews;
import com.example.platform.common.tenant.TenantContext;

/**
 * One section of the workbench. Five implementations, fixed order 1..5.
 *
 * <p>The interface exists so a section can fail alone: {@code PortalService} wraps each
 * {@link #build} call in its own {@code try}, turning an exception into a {@code DEGRADED} section
 * instead of a blank portal. A section that needs a new number reads it through the injected
 * query service — never a second formula.</p>
 */
public interface PortalSectionProvider {

    /** Stable section key ({@code todo}, {@code actions}, {@code metrics}, {@code list}, {@code messages}). */
    String key();

    /** Render order, 1-based and unique. */
    int order();

    PortalViews.Section build(PortalContext ctx);

    /**
     * Everything one request's sections share.
     *
     * <p>{@code stamp} collects the data timestamp and cache state of every query that succeeded —
     * the portal reports one {@code dataAsOf} for the whole page, so a section cannot report its own.
     * {@code todos} is filled by the todo section and lifted to the envelope.</p>
     */
    record PortalContext(TenantContext.Principal user, String template, String todoMode,
                         AnalyticsRequest request, String[] cacheHeader,
                         List<PortalViews.CardRef> personalCards, List<String> cardKeys,
                         Stamp stamp, List<PortalViews.TodoSource> todos) {
    }

    /** Mutable request-scoped accumulator; not thread-safe and never shared across requests. */
    final class Stamp {

        private String dataAsOf;
        private boolean miss;

        public void note(AnalyticsViews.Metrics metrics) {
            if (metrics == null) {
                return;
            }
            noteAsOf(metrics.dataAsOf());
            miss |= !com.example.deliver.analytics.AnalyticsCache.HIT.equals(metrics.cache());
        }

        public void note(AnalyticsViews.CrossTab tab) {
            if (tab == null) {
                return;
            }
            noteAsOf(tab.dataAsOf());
            miss |= !com.example.deliver.analytics.AnalyticsCache.HIT.equals(tab.cache());
        }

        /** A query that failed contributes nothing — the page timestamp is the newest success. */
        private void noteAsOf(String asOf) {
            if (asOf == null || asOf.isBlank()) {
                return;
            }
            if (dataAsOf == null || asOf.compareTo(dataAsOf) > 0) {
                dataAsOf = asOf;
            }
        }

        public String dataAsOf() {
            return dataAsOf;
        }

        public String cache() {
            return miss ? com.example.deliver.analytics.AnalyticsCache.MISS
                    : com.example.deliver.analytics.AnalyticsCache.HIT;
        }
    }
}
