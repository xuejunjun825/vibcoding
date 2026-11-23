package com.example.req.automation.engine;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.example.req.automation.spec.AutomationSpecs.ConditionSpec;
import com.example.req.automation.spec.AutomationSpecs.EstimateDays;

/**
 * Pure condition evaluation over an already-taken snapshot.
 *
 * <p>No container, no JDBC, no clock: everything the decision needs is in the map, so the five
 * comparison operators, the AND/OR combinations and "a missing field does not match" can all be
 * unit-tested without a database, and a state advance after the event was written can never change
 * this run's verdict.</p>
 *
 * <p>Keys are closed: only the fields declared in {@link ConditionSpec} are consulted, and an
 * absent value is a miss (fail-closed) rather than a wildcard.</p>
 */
public final class AutomationConditionEvaluator {

    /** {@code matched=false} always carries a reason; it is the run's explanation, not an error. */
    public record Verdict(boolean matched, String reason) {

        public static Verdict hit() {
            return new Verdict(true, null);
        }

        public static Verdict miss(String reason) {
            return new Verdict(false, reason);
        }
    }

    private AutomationConditionEvaluator() {
    }

    public static Verdict evaluate(ConditionSpec spec, Map<String, Object> snapshot) {
        Map<String, Object> values = snapshot == null ? Map.of() : snapshot;
        if (spec == null) {
            return Verdict.hit();
        }
        String type = string(values, AutomationSnapshot.K_TYPE);
        Verdict verdict = in(spec.types(), type, "types");
        if (!verdict.matched()) {
            return verdict;
        }
        verdict = in(spec.priorityIn(), string(values, AutomationSnapshot.K_PRIORITY), "priorityIn");
        if (!verdict.matched()) {
            return verdict;
        }
        verdict = in(spec.statusIn(), string(values, AutomationSnapshot.K_STATUS), "statusIn");
        if (!verdict.matched()) {
            return verdict;
        }
        verdict = in(spec.approvalResults(), string(values, AutomationSnapshot.K_APPROVAL_RESULT),
                "approvalResults");
        if (!verdict.matched()) {
            return verdict;
        }
        verdict = estimate(spec.estimateDays(), values);
        if (!verdict.matched()) {
            return verdict;
        }
        verdict = ids(spec.domainIds(), AutomationSnapshot.K_DOMAIN_ID, values, "domainIds");
        if (!verdict.matched()) {
            return verdict;
        }
        verdict = vendorIds(spec.vendorIds(), values);
        if (!verdict.matched()) {
            return verdict;
        }
        return in(spec.fields(), string(values, AutomationSnapshot.K_FIELD), "fields");
    }

    private static Verdict in(List<String> expected, String actual, String reason) {
        if (expected == null || expected.isEmpty()) {
            return Verdict.hit();
        }
        if (actual == null) {
            return Verdict.miss(reason + "_missing");
        }
        return expected.contains(actual) ? Verdict.hit() : Verdict.miss(reason);
    }

    private static Verdict estimate(EstimateDays estimate, Map<String, Object> values) {
        if (estimate == null) {
            return Verdict.hit();
        }
        BigDecimal actual = number(values.get(AutomationSnapshot.K_ESTIMATE_DAYS));
        if (actual == null) {
            return Verdict.miss("estimateDays_missing");
        }
        int cmp = actual.compareTo(estimate.value());
        boolean ok = switch (estimate.op()) {
            case "lt" -> cmp < 0;
            case "lte" -> cmp <= 0;
            case "gt" -> cmp > 0;
            case "gte" -> cmp >= 0;
            case "eq" -> cmp == 0;
            default -> false;
        };
        return ok ? Verdict.hit() : Verdict.miss("estimateDays");
    }

    private static Verdict ids(List<Long> expected, String key, Map<String, Object> values,
                               String reason) {
        if (expected == null || expected.isEmpty()) {
            return Verdict.hit();
        }
        Long actual = Rows2.longValue(values.get(key));
        if (actual == null) {
            return Verdict.miss(reason + "_missing");
        }
        return expected.contains(actual) ? Verdict.hit() : Verdict.miss(reason);
    }

    private static Verdict vendorIds(List<Long> expected, Map<String, Object> values) {
        if (expected == null || expected.isEmpty()) {
            return Verdict.hit();
        }
        Long owner = Rows2.longValue(values.get(AutomationSnapshot.K_OWNER_VENDOR_ID));
        Long assignee = Rows2.longValue(values.get(AutomationSnapshot.K_ASSIGNEE_VENDOR_ID));
        boolean ok = (owner != null && expected.contains(owner))
                || (assignee != null && expected.contains(assignee));
        if (owner == null && assignee == null) {
            return Verdict.miss("vendorIds_missing");
        }
        return ok ? Verdict.hit() : Verdict.miss("vendorIds");
    }

    private static String string(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static BigDecimal number(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Small helper so the evaluator stays JDBC-free (it must not import a row mapper). */
    private static final class Rows2 {

        private Rows2() {
        }

        static Long longValue(Object value) {
            if (value == null) {
                return null;
            }
            if (value instanceof Number number) {
                return number.longValue();
            }
            try {
                return Long.valueOf(String.valueOf(value).trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
    }
}
