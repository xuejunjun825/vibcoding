package com.example.deliver.analytics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.ScopeTier;

/**
 * One query's filter input, and the only place a preset turns into a date range.
 *
 * <p>The workbench, the dashboard and the report page share this record: same preset means same
 * window means same numbers. {@link #cacheKey(String, long)} carries the scope fingerprint as well
 * as the filters, because a cache that ignores who is asking hands one account another account's
 * numbers (the exact failure AC-12 forbids).</p>
 */
public record AnalyticsRequest(Long domainId, Long vendorId, LocalDate from, LocalDate to,
                               String preset, String reqType) {

    /** A single query may not span more than a year plus the leap day. */
    public static final int MAX_SPAN_DAYS = 366;

    public static final String PRESET_7D = "7d";
    public static final String PRESET_30D = "30d";
    public static final String PRESET_90D = "90d";
    public static final String PRESET_QUARTER = "quarter";
    public static final String PRESET_CUSTOM = "custom";

    public static final String DEFAULT_PRESET = PRESET_30D;

    public static AnalyticsRequest empty() {
        return new AnalyticsRequest(null, null, null, null, DEFAULT_PRESET, null);
    }

    /**
     * Fill the window from the preset (or keep an explicit {@code from}/{@code to}) and reject a
     * span the engine will not stream.
     *
     * @param today the caller's today, so a preset is resolved once and is testable
     */
    public AnalyticsRequest resolve(LocalDate today) {
        LocalDate anchor = today == null ? LocalDate.now() : today;
        String normalised = preset == null || preset.isBlank()
                ? (from == null ? DEFAULT_PRESET : PRESET_CUSTOM)
                : preset.trim().toLowerCase(Locale.ROOT);
        LocalDate end = to != null ? to : anchor;
        LocalDate start = from;
        if (start == null) {
            start = switch (normalised) {
                case PRESET_7D -> end.minusDays(6);
                case PRESET_90D -> end.minusDays(89);
                case PRESET_QUARTER -> end.withDayOfMonth(1)
                        .minusMonths((end.getMonthValue() - 1) % 3);
                default -> end.minusDays(29);
            };
        }
        if (start.isAfter(end)) {
            LocalDate swap = start;
            start = end;
            end = swap;
        }
        if (ChronoUnit.DAYS.between(start, end) > MAX_SPAN_DAYS) {
            throw AnalyticsErrors.spanLimit(start, end);
        }
        return new AnalyticsRequest(domainId, vendorId, start, end, normalised, reqType);
    }

    /** The equidistant window immediately before this one ("环比" comparison window). */
    public AnalyticsRequest previous() {
        if (from == null || to == null) {
            throw new IllegalStateException("resolve() the request before asking for the previous window");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        return new AnalyticsRequest(domainId, vendorId, from.minusDays(days), from.minusDays(1),
                PRESET_CUSTOM, reqType);
    }

    /**
     * A cache key that cannot leak across accounts.
     *
     * <p>Contains tenant, viewer, scope tier and a digest of the scope's domain/vendor/org sets:
     * two viewers with the same filters but different row-level scopes get different keys, so the
     * cheap in-process cache stays correct without a per-row re-check on a hit.</p>
     */
    public String cacheKey(String tenant, long userId) {
        DataScope scope = TenantContext.scopeOrNull();
        StringBuilder source = new StringBuilder(128);
        source.append(tenant).append('|').append(userId).append('|')
                .append(scope == null ? "-" : scope.tier().name()).append('|')
                .append(digest(scope == null ? null : scope.domainIds())).append('|')
                .append(scope == null ? "-" : String.valueOf(scope.vendorId())).append('|')
                .append(digest(scope == null ? null : scope.orgSubtreeIds())).append('|')
                .append(domainId).append('|').append(vendorId).append('|')
                .append(from).append('|').append(to).append('|')
                .append(preset).append('|').append(reqType);
        return sha256(source.toString());
    }

    private static String digest(Set<Long> values) {
        if (values == null || values.isEmpty()) {
            return "-";
        }
        Set<Long> sorted = new TreeSet<>(values);
        StringBuilder out = new StringBuilder();
        for (Long value : sorted) {
            out.append(value).append(',');
        }
        return out.toString();
    }

    private static String sha256(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** The tier values the cache key distinguishes; exposed for the isolation probe. */
    public static Set<String> scopeTiers() {
        Set<String> values = new LinkedHashSet<>();
        for (ScopeTier tier : ScopeTier.values()) {
            values.add(tier.name());
        }
        return values;
    }
}
