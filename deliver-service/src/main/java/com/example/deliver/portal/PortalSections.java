package com.example.deliver.portal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.example.deliver.analytics.AnalyticsMetrics;
import com.example.deliver.analytics.AnalyticsQueryService;
import com.example.deliver.analytics.AnalyticsViews;
import com.example.deliver.reports.ReportDefinitionCodec;
import com.example.deliver.reports.ReportLookup;

/**
 * The five section providers.
 *
 * <p>Each one builds its own slice and nothing else: a failing number becomes a degraded tile in
 * the section that asked for it, and the other four sections still render. The metrics section is
 * the only place where a personal card's total is read, and it reads
 * {@code CrossTab.total} — the same aggregate the report page shows — instead of re-deriving a
 * percentage from raw counts.</p>
 */
public final class PortalSections {

    static final String TODO = "todo";
    static final String ACTIONS = "actions";
    static final String METRICS = "metrics";
    static final String LIST = "list";
    static final String MESSAGES = "messages";

    private PortalSections() {
    }

    /** Section titles also used when a section has to be degraded before it can build itself. */
    public static final Map<String, String> TITLES = Map.of(
            TODO, "待办",
            ACTIONS, "快捷入口",
            METRICS, "关键指标",
            LIST, "最近报表",
            MESSAGES, "提示");

    @Component
    public static class PortalTodoSection implements PortalSectionProvider {

        private final AnalyticsQueryService analytics;

        public PortalTodoSection(AnalyticsQueryService analytics) {
            this.analytics = analytics;
        }

        @Override
        public String key() {
            return TODO;
        }

        @Override
        public int order() {
            return 1;
        }

        @Override
        public PortalViews.Section build(PortalContext ctx) {
            PortalTemplates.Role role = PortalTemplates.of(ctx.template());
            if (role.todos().isEmpty() || PortalViews.TODO_READONLY.equals(ctx.todoMode())) {
                return new PortalViews.Section(TODO, TITLES.get(TODO), PortalViews.OK, null, List.of());
            }
            List<String> keys = role.todos().stream().map(PortalTemplates.TodoDef::metric).distinct().toList();
            Map<String, AnalyticsViews.Metric> byKey = new LinkedHashMap<>();
            String error = null;
            try {
                AnalyticsViews.Metrics metrics = analytics.metrics(ctx.request(), keys, ctx.cacheHeader());
                ctx.stamp().note(metrics);
                for (AnalyticsViews.Metric metric : metrics.items()) {
                    byKey.put(metric.key(), metric);
                }
            } catch (RuntimeException e) {
                error = reason(e);
            }
            List<PortalViews.Card> cards = new ArrayList<>();
            List<PortalViews.TodoSource> todos = new ArrayList<>();
            for (PortalTemplates.TodoDef def : role.todos()) {
                AnalyticsViews.Metric metric = byKey.get(def.metric());
                Long count = metric == null || metric.value() == null ? null : Math.round(metric.value());
                String state = count == null ? PortalViews.DEGRADED : PortalViews.OK;
                todos.add(new PortalViews.TodoSource(def.key(), def.label(), count,
                        metric == null ? null : metric.unit(), def.link(), state,
                        count == null ? (error == null ? "指标不可用" : error) : null));
                if (metric != null) {
                    cards.add(new PortalViews.Card(def.key(), def.label(), PortalViews.TEMPLATE,
                            List.of(metric), def.link(), null, null));
                }
            }
            ctx.todos().addAll(todos);
            return new PortalViews.Section(TODO, TITLES.get(TODO), PortalViews.OK, null, cards);
        }

        /** Why the tile row could not be read: the API message when there is one. */
        private static String reason(RuntimeException e) {
            if (e instanceof com.example.platform.common.error.ApiException api
                    && api.getMessage() != null && !api.getMessage().isBlank()) {
                return api.getMessage();
            }
            return e.getClass().getSimpleName();
        }
    }

    @Component
    public static class PortalActionsSection implements PortalSectionProvider {

        @Override
        public String key() {
            return ACTIONS;
        }

        @Override
        public int order() {
            return 2;
        }

        @Override
        public PortalViews.Section build(PortalContext ctx) {
            PortalTemplates.Role role = PortalTemplates.of(ctx.template());
            List<PortalViews.Card> cards = new ArrayList<>();
            for (PortalTemplates.ActionDef action : role.actions()) {
                cards.add(new PortalViews.Card(action.key(), action.label(), PortalViews.TEMPLATE,
                        List.of(), action.link(), action.hint(), null));
            }
            return new PortalViews.Section(ACTIONS, TITLES.get(ACTIONS), PortalViews.OK, null, cards);
        }
    }

    /**
     * Personal cards first, role cards fill the rest, at most four — the same four-card ceiling the
     * config and pin endpoints enforce, so the portal can never show a card that config would
     * reject.
     */
    @Component
    public static class PortalMetricsSection implements PortalSectionProvider {

        private final AnalyticsQueryService analytics;
        private final ReportLookup reports;
        private final ReportDefinitionCodec codec;

        public PortalMetricsSection(AnalyticsQueryService analytics, ReportLookup reports,
                                    ReportDefinitionCodec codec) {
            this.analytics = analytics;
            this.reports = reports;
            this.codec = codec;
        }

        @Override
        public String key() {
            return METRICS;
        }

        @Override
        public int order() {
            return 3;
        }

        @Override
        public PortalViews.Section build(PortalContext ctx) {
            PortalTemplates.Role role = PortalTemplates.of(ctx.template());
            List<PortalViews.Card> cards = new ArrayList<>();
            for (PortalViews.CardRef ref : ctx.personalCards()) {
                if (ref.reportId() == null || cards.size() >= PortalTemplates.MAX_CARDS) {
                    continue;
                }
                cards.add(personalCard(ref, ctx));
            }
            List<PortalTemplates.CardDef> templateCards = new ArrayList<>();
            for (String key : ctx.cardKeys()) {
                if (cards.size() + templateCards.size() >= PortalTemplates.MAX_CARDS) {
                    break;
                }
                PortalTemplates.CardDef def = role.card(key);
                if (def != null) {
                    templateCards.add(def);
                }
            }
            if (!templateCards.isEmpty()) {
                Set<String> keys = new LinkedHashSet<>();
                for (PortalTemplates.CardDef def : templateCards) {
                    keys.addAll(def.metrics());
                }
                AnalyticsViews.Metrics metrics = analytics.metrics(ctx.request(),
                        new ArrayList<>(keys), ctx.cacheHeader());
                ctx.stamp().note(metrics);
                Map<String, AnalyticsViews.Metric> byKey = new LinkedHashMap<>();
                for (AnalyticsViews.Metric metric : metrics.items()) {
                    byKey.put(metric.key(), metric);
                }
                for (PortalTemplates.CardDef def : templateCards) {
                    List<AnalyticsViews.Metric> shown = new ArrayList<>();
                    for (String key : def.metrics()) {
                        AnalyticsViews.Metric metric = byKey.get(key);
                        if (metric != null) {
                            shown.add(metric);
                        }
                    }
                    cards.add(new PortalViews.Card(def.key(), def.title(), PortalViews.TEMPLATE,
                            shown, def.link(), def.hint(), null));
                }
            }
            return new PortalViews.Section(METRICS, TITLES.get(METRICS), PortalViews.OK, null, cards);
        }

        private PortalViews.Card personalCard(PortalViews.CardRef ref, PortalContext ctx) {
            ReportLookup.ReportCard card = reports.card(ref.reportId()).orElse(null);
            if (card == null) {
                return new PortalViews.Card(ref.key(), ref.title(), PortalViews.PERSONAL,
                        List.of(), "/reports/" + ref.reportId(), null, ref.reportId());
            }
            AnalyticsViews.ReportDefinition definition =
                    codec.engine(card.dataset(), codec.read(card.definitionJson()));
            AnalyticsViews.CrossTab tab = analytics.crossTab(card.dataset(), definition,
                    ctx.request(), AnalyticsQueryService.MAX_GROUPS, card.id(), ctx.cacheHeader());
            ctx.stamp().note(tab);
            List<AnalyticsViews.Metric> metrics = new ArrayList<>();
            List<String> measures = definition.measures();
            List<Double> values = tab.total() == null ? List.of() : tab.total().values();
            for (int i = 0; i < measures.size() && i < values.size(); i++) {
                AnalyticsViews.MetricSpec spec = AnalyticsMetrics.spec(measures.get(i));
                metrics.add(new AnalyticsViews.Metric(spec.key(), spec.label(), spec.unit(),
                        spec.digits(), values.get(i), null, null, spec.caliber(), tab.dataAsOf()));
            }
            return new PortalViews.Card(ref.key(), card.name(), PortalViews.PERSONAL, metrics,
                    "/reports/" + card.id(), "置顶报表", card.id());
        }
    }

    @Component
    public static class PortalListSection implements PortalSectionProvider {

        private final ReportLookup reports;

        public PortalListSection(ReportLookup reports) {
            this.reports = reports;
        }

        @Override
        public String key() {
            return LIST;
        }

        @Override
        public int order() {
            return 4;
        }

        @Override
        public PortalViews.Section build(PortalContext ctx) {
            PortalTemplates.Role role = PortalTemplates.of(ctx.template());
            List<PortalViews.Card> cards = new ArrayList<>();
            for (ReportLookup.ReportCard card : reports.recent(ctx.user().userId(), 5)) {
                cards.add(new PortalViews.Card("report:" + card.id(), card.name(), PortalViews.PERSONAL,
                        List.of(), "/reports/" + card.id(), role.listTitle(), card.id()));
            }
            return new PortalViews.Section(LIST, TITLES.get(LIST), PortalViews.OK, null, cards);
        }
    }

    @Component
    public static class PortalMessagesSection implements PortalSectionProvider {

        @Override
        public String key() {
            return MESSAGES;
        }

        @Override
        public int order() {
            return 5;
        }

        @Override
        public PortalViews.Section build(PortalContext ctx) {
            PortalTemplates.Role role = PortalTemplates.of(ctx.template());
            List<PortalViews.Card> cards = new ArrayList<>();
            int index = 0;
            for (String message : role.messages()) {
                cards.add(new PortalViews.Card("message." + index++, "提示", PortalViews.TEMPLATE,
                        List.of(), null, message, null));
            }
            return new PortalViews.Section(MESSAGES, TITLES.get(MESSAGES), PortalViews.OK, null, cards);
        }
    }
}
