package com.example.deliver.quality.plan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.example.platform.common.error.ApiException;
import com.example.platform.contract.StateTables;

/**
 * Plan lifecycle vocabulary: {@code allowedNext}, normalisation and the 409 shape.
 *
 * <p>It is a thin adapter over {@link StateTables#PLAN} on purpose — the transition table itself
 * stays in the contract module so the controller, the detail payload and the frontend stepper all
 * read the same rows.</p>
 */
public final class PlanLifecycle {

    public static final String TYPE = StateTables.PLAN;

    private PlanLifecycle() {
    }

    /** Actions legal in {@code status}, in declaration order. */
    public static List<String> allowedNext(String status) {
        return List.copyOf(StateTables.actions(TYPE, status));
    }

    /** Action code as it arrives on the wire ({@code submit_review → submit-review}). */
    public static String normaliseAction(String action) {
        if (action == null || action.isBlank()) {
            throw new ApiException(400, "VALIDATION_FAILED", "动作不能为空", Map.of("field", "action"));
        }
        return action.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Target status, or a 409 carrying from/action/allowedNext. */
    public static String requireTarget(String status, String action) {
        String target = StateTables.target(TYPE, status, action);
        if (target == null) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("from", status);
            detail.put("action", action);
            detail.put("allowedNext", allowedNext(status));
            throw new ApiException(409, "ILLEGAL_PLAN_TRANSITION",
                    "计划当前状态不允许该动作", detail);
        }
        return target;
    }

    /** Whether a plan in this status still accepts writes (execution, cases, changes, rounds). */
    public static boolean writable(String status) {
        return !"CLOSED".equals(status) && !"ARCHIVED".equals(status);
    }

    public static void requireWritable(String status, String what) {
        if (writable(status)) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", status);
        detail.put("status", status);
        detail.put("allowedNext", allowedNext(status));
        throw new ApiException(409, "ILLEGAL_PLAN_TRANSITION",
                "计划已" + ("CLOSED".equals(status) ? "关闭" : "归档") + "，" + what + "不再允许",
                detail);
    }

    /** Statuses that count as "approved enough" for reporting/gates. */
    public static Set<String> reportableStatuses() {
        return Set.of("APPROVED", "IN_PROGRESS", "COMPLETED", "CLOSED");
    }

    /**
     * Statuses that are <em>not</em> approved yet, so a deploy order must not be allowed to
     * reference requirements they cover (R1, AC-1..AC-4).
     *
     * <p>This is the single vocabulary for that decision: the coverage query passes it as a bind
     * parameter instead of writing {@code 'DRAFT'} literals, so widening the state table later
     * cannot leave the SQL behind.</p>
     */
    public static Set<String> pendingStatuses() {
        return Set.of("DRAFT", "PENDING_REVIEW");
    }

    /** Stable rank for "conclusion may only go down" (CANCELLED is independent). */
    public static int conclusionRank(String conclusion) {
        if (conclusion == null) {
            return -1;
        }
        return switch (conclusion.toUpperCase(Locale.ROOT)) {
            case "PASS" -> 3;
            case "CONDITIONAL" -> 2;
            case "FAIL" -> 1;
            case "CANCELLED" -> 0;
            default -> -1;
        };
    }

    public static String normaliseConclusion(String conclusion) {
        if (conclusion == null || conclusion.isBlank()) {
            throw new ApiException(400, "VALIDATION_FAILED", "轮次结论不能为空",
                    Map.of("field", "conclusion"));
        }
        String normalised = conclusion.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("PASS", "CONDITIONAL", "FAIL", "CANCELLED").contains(normalised)) {
            throw new ApiException(400, "VALIDATION_FAILED", "轮次结论不合法",
                    Map.of("field", "conclusion", "allowed",
                            List.of("PASS", "CONDITIONAL", "FAIL", "CANCELLED")));
        }
        return normalised;
    }

    /** Lower rank wins? No — {@code requested} must be ≤ {@code suggested}. */
    public static void requireNotUpgraded(String requested, String suggested) {
        int requestedRank = conclusionRank(requested);
        int suggestedRank = conclusionRank(suggested);
        if ("CANCELLED".equals(requested) || requestedRank <= suggestedRank) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("from", requested);
        detail.put("to", suggested);
        throw new ApiException(409, "CONCLUSION_UPGRADE_FORBIDDEN",
                "轮次结论只能下调，不能优于数据建议值", detail);
    }
}
