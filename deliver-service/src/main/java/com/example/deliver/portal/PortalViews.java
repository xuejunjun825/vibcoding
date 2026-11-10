package com.example.deliver.portal;

import java.util.List;

import com.example.deliver.analytics.AnalyticsViews;

/**
 * The workbench wire model, field-for-field with {@code web/src/api/analytics.ts}.
 *
 * <p>{@code state} and {@code kind} are strings rather than enums so a new value is a
 * frontend-only change and Jackson cannot rename one behind the client's back. Empty optional
 * fields serialise as {@code null}, which the TS types already declare as optional.</p>
 */
public final class PortalViews {

    /** Section states. A degraded section still renders — with an empty card list and an error. */
    public static final String OK = "OK";
    public static final String DEGRADED = "DEGRADED";

    /** Card kinds: role-template card vs one pinned report. */
    public static final String TEMPLATE = "TEMPLATE";
    public static final String PERSONAL = "PERSONAL";

    /** Todo modes: normal, management read-only, and the unknown-role fallback. */
    public static final String TODO_FULL = "FULL";
    public static final String TODO_READONLY = "READONLY";
    public static final String TODO_FALLBACK = "FALLBACK";

    private PortalViews() {
    }

    public record Portal(String template, String templateLabel, String todoMode,
                         List<Section> sections, List<TodoSource> todoSources,
                         String dataAsOf, String cache) {
    }

    public record Section(String key, String title, String state, String error, List<Card> cards) {
    }

    public record Card(String key, String title, String kind, List<AnalyticsViews.Metric> metrics,
                       String link, String hint, Long reportId) {
    }

    public record TodoSource(String key, String label, Long count, String sub, String link,
                             String state, String error) {
    }

    public record CardRef(String key, String title, Long reportId) {
    }

    public record Config(String roleCode, List<String> cardKeys, int maxCards, List<CardRef> cards) {
    }

    public record ConfigCmd(String roleCode, List<String> cardKeys) {
    }

    public record PinResult(boolean pinned, String roleCode, List<CardRef> cards) {
    }
}
