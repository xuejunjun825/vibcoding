package com.example.deliver.reports;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.example.deliver.analytics.AnalyticsErrors;
import com.example.deliver.support.ShareVisibility;
import com.example.platform.common.error.ApiException;

/**
 * The five sharing tiers, normalised in one place.
 *
 * <p>Two contracts live here and nowhere else, so the service and the controller cannot drift
 * apart:</p>
 *
 * <ul>
 *   <li>{@code TENANT} is accepted as an alias of {@code PUBLIC} — the word tenants used before
 *       the tier list settled — and is <b>stored and echoed as {@code PUBLIC}</b>, so a reader
 *       never sees two spellings of the same sharing level.</li>
 *   <li>{@code ROLE}, {@code DOMAIN} and {@code VENDOR} carry a numeric subject, while
 *       {@code PRIVATE} and {@code PUBLIC} must not carry one. A body that sends the wrong shape
 *       fails with 400 {@code REPORT_UNKNOWN_FIELD} and {@code detail.field} naming
 *       {@code scope} or {@code scopeRef}, which is how the editor tells "pick a tier" apart
 *       from "pick a subject".</li>
 * </ul>
 *
 * <p>The tier set is read from {@link ShareVisibility} so the predicate and the validator can
 * never disagree about which tiers exist.</p>
 */
public final class ReportScopes {

    private static final Set<String> TIERS = Set.of(ShareVisibility.PRIVATE, ShareVisibility.ROLE,
            ShareVisibility.DOMAIN, ShareVisibility.VENDOR, ShareVisibility.PUBLIC);

    private ReportScopes() {
    }

    /** Canonical tier: blank → PRIVATE, {@code TENANT} → {@code PUBLIC}, otherwise upper-cased. */
    public static String normalize(String scope) {
        if (scope == null || scope.isBlank()) {
            return ShareVisibility.PRIVATE;
        }
        String tier = scope.trim().toUpperCase(Locale.ROOT);
        return "TENANT".equals(tier) ? ShareVisibility.PUBLIC : tier;
    }

    public static boolean known(String scope) {
        return scope != null && TIERS.contains(scope);
    }

    /** Whether the tier names a subject (role id / domain id / vendor id) in {@code scopeRef}. */
    public static boolean needsRef(String scope) {
        return ShareVisibility.ROLE.equals(scope) || ShareVisibility.DOMAIN.equals(scope)
                || ShareVisibility.VENDOR.equals(scope);
    }

    /**
     * Read a numeric subject from the wire, where JSON gives us an {@code Integer}, a {@code Long}
     * or a numeric string depending on the client.
     */
    public static Long parseRef(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            long ref = number.longValue();
            if (value != (double) ref || ref <= 0) {
                throw invalidRef(raw);
            }
            return ref;
        }
        if (raw instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                long ref = Long.parseLong(trimmed);
                if (ref <= 0) {
                    throw invalidRef(raw);
                }
                return ref;
            } catch (NumberFormatException e) {
                throw invalidRef(raw);
            }
        }
        throw invalidRef(raw);
    }

    /** 400 for an unknown tier; {@code detail.field} is what the editor highlights. */
    public static ApiException unknownScope(Object scope) {
        return badField("scope", scope, "共享范围必须是 PRIVATE/ROLE/DOMAIN/VENDOR/PUBLIC 之一");
    }

    /** 400 for a subject that is missing, non-numeric or supplied where the tier forbids one. */
    public static ApiException invalidRef(Object ref) {
        return badField("scopeRef", ref, "ROLE/DOMAIN/VENDOR 需要数值 scopeRef，PRIVATE/PUBLIC 不得携带");
    }

    private static ApiException badField(String field, Object value, String message) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("field", field);
        if (value != null) {
            detail.put("value", String.valueOf(value));
        }
        return new ApiException(400, AnalyticsErrors.UNKNOWN_FIELD,
                message + "（field=" + field + "）", detail);
    }
}
