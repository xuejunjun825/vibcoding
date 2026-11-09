package com.example.platform.contract;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The state machines of the platform, as data.
 *
 * <p>Three requirement types, defects, and the six M6 quality machines (plan / round / report
 * version / quality gate / waiver / Go-No-Go review). The table is the <b>only</b> source of
 * "which action is legal now" and "where does it land": services assert against it, detail
 * responses expose it as {@code allowedActions}/{@code allowedNext}, and the frontend renders
 * what the server sends instead of copying the table. A second copy is how "button enabled,
 * API returns 409" drift starts.</p>
 *
 * <p>Action codes are lower-case on the wire ({@code start}, {@code verify_pass},
 * {@code submit-review}); callers normalise before asking. Unknown type/status/action returns
 * an empty set / {@code null}, and {@code *StateMachine} turns that into a 409 — never a
 * silent no-op.</p>
 *
 * <p>{@link #GATE} is not a workflow but a re-evaluable verdict: every terminal-valued state
 * keeps the three {@code pass}/{@code pass_with_waiver}/{@code fail} actions, because a gate can
 * be re-evaluated on the same release as data changes (AC-5/AC-7). Consumers never "advance" a
 * gate; they set the status produced by {@code GateEvaluator} and validate it here.</p>
 */
public final class StateTables {

    public static final String BUS = "BUS";
    public static final String PDR = "PDR";
    public static final String RD = "RD";
    public static final String DEFECT = "DEFECT";

    /** Quality machines added by M6 (TECHNICAL §6.3/§6.4/§6.6/§6.7). */
    public static final String PLAN = "PLAN";
    public static final String ROUND = "ROUND";
    public static final String REPORT = "REPORT";
    public static final String GATE = "GATE";
    public static final String WAIVER = "WAIVER";
    public static final String REVIEW = "REVIEW";

    private static final Map<String, Map<String, Map<String, String>>> TABLE = new LinkedHashMap<>();
    private static final Map<String, Set<String>> TERMINAL = Map.ofEntries(
            Map.entry(BUS, Set.of("CLOSED")),
            Map.entry(PDR, Set.of("ACCEPTED_CLOSED")),
            Map.entry(RD, Set.of("DONE")),
            Map.entry(DEFECT, Set.of("CLOSED")),
            Map.entry(PLAN, Set.of("ARCHIVED")),
            Map.entry(ROUND, Set.of("CLOSED")),
            // signed is not terminal: a newer version supersedes it.
            Map.entry(REPORT, Set.of("REJECTED", "SUPERSEDED")),
            Map.entry(GATE, Set.of()),
            Map.entry(WAIVER, Set.of("REJECTED", "EXPIRED", "REVOKED")),
            Map.entry(REVIEW, Set.of("CONFIRMED")));

    static {
        Map<String, Map<String, String>> bus = new LinkedHashMap<>();
        bus.put("SUBMITTED", transitions("submit", "APPROVING"));
        bus.put("APPROVING", transitions("approve", "PENDING_REVIEW",
                "reject", "SUBMITTED",
                "withdraw", "SUBMITTED"));
        // review_start 只负责发起 REQ_REVIEW 审批实例（状态不变）；
        // review_pass 只能由审批回调驱动，服务层另做「实例必须 APPROVED」的守卫。
        bus.put("PENDING_REVIEW", transitions("review_start", "PENDING_REVIEW",
                "review_pass", "POOLED",
                "review_reject", "PENDING_REVIEW",
                "close", "CLOSED"));
        bus.put("POOLED", transitions("close", "CLOSED"));
        TABLE.put(BUS, bus);

        Map<String, Map<String, String>> pdr = new LinkedHashMap<>();
        pdr.put("PENDING_ANALYSIS", transitions("schedule", "SCHEDULED"));
        pdr.put("SCHEDULED", transitions("start_dev", "IN_DEV"));
        pdr.put("IN_DEV", transitions("deliver", "DELIVERED"));
        pdr.put("DELIVERED", transitions("accept", "ACCEPTED_CLOSED"));
        TABLE.put(PDR, pdr);

        Map<String, Map<String, String>> rd = new LinkedHashMap<>();
        rd.put("TODO", transitions("start_dev", "IN_DEV"));
        rd.put("IN_DEV", transitions("submit_test", "PENDING_TEST"));
        rd.put("PENDING_TEST", transitions("test_pass", "TEST_PASSED"));
        rd.put("TEST_PASSED", transitions("merge", "MERGED"));
        rd.put("MERGED", transitions("finish", "DONE"));
        TABLE.put(RD, rd);

        // Defect loop: rework after verification and re-opening a closed defect both count
        // as reopen(+1); the cycle has no terminal node by design, so "closed is final" is
        // expressed as "CLOSED can only leave through reopen", not as a dead end.
        Map<String, Map<String, String>> defect = new LinkedHashMap<>();
        defect.put("NEW", transitions("start", "IN_PROGRESS",
                "close_duplicate", "CLOSED"));
        defect.put("IN_PROGRESS", transitions("submit_verify", "PENDING_VERIFY"));
        defect.put("PENDING_VERIFY", transitions("verify_pass", "CLOSED",
                "verify_reject", "IN_PROGRESS"));
        defect.put("CLOSED", transitions("reopen", "IN_PROGRESS"));
        TABLE.put(DEFECT, defect);

        // ---- M6 quality machines ----

        // Test plan: DRAFT → PENDING_REVIEW → APPROVED → IN_PROGRESS → COMPLETED → CLOSED →
        // ARCHIVED, with reject falling back to DRAFT. There is no "set status" action: the
        // frontend renders a stepper from allowedNext so it cannot offer an illegal jump.
        Map<String, Map<String, String>> plan = new LinkedHashMap<>();
        plan.put("DRAFT", transitions("submit-review", "PENDING_REVIEW"));
        plan.put("PENDING_REVIEW", transitions("approve", "APPROVED",
                "reject", "DRAFT"));
        plan.put("APPROVED", transitions("start", "IN_PROGRESS"));
        plan.put("IN_PROGRESS", transitions("complete", "COMPLETED"));
        plan.put("COMPLETED", transitions("close", "CLOSED"));
        plan.put("CLOSED", transitions("archive", "ARCHIVED"));
        plan.put("ARCHIVED", transitions());
        TABLE.put(PLAN, plan);

        // A round is open (PLANNING, scope frozen) or running; close freezes its exec_* snapshot
        // and frees the (plan_id, active) unique key so the next round can be opened.
        Map<String, Map<String, String>> round = new LinkedHashMap<>();
        round.put("PLANNING", transitions("start", "RUNNING",
                "close", "CLOSED"));
        round.put("RUNNING", transitions("close", "CLOSED"));
        round.put("CLOSED", transitions());
        TABLE.put(ROUND, round);

        // test_report_version status. Signing is strictly ordered by role in SignoffService;
        // this table only says a version that is already signed cannot be signed again.
        Map<String, Map<String, String>> report = new LinkedHashMap<>();
        report.put("SIGNING", transitions("sign", "SIGNED",
                "reject", "REJECTED"));
        report.put("SIGNED", transitions("supersede", "SUPERSEDED"));
        report.put("REJECTED", transitions());
        report.put("SUPERSEDED", transitions());
        TABLE.put(REPORT, report);

        // Gate verdict: re-evaluable, so each state keeps all three outcomes.
        Map<String, Map<String, String>> gate = new LinkedHashMap<>();
        for (String status : List.of("NOT_EVALUATED", "PASS", "PASS_WITH_WAIVER", "FAIL")) {
            gate.put(status, transitions("pass", "PASS",
                    "pass_with_waiver", "PASS_WITH_WAIVER",
                    "fail", "FAIL"));
        }
        TABLE.put(GATE, gate);

        // Waiver: PENDING → ACTIVE → (revoke) REVOKED, or (expire) EXPIRED from either open
        // state. EXPIRED is set lazily by the evaluator, never by a scheduled job.
        Map<String, Map<String, String>> waiver = new LinkedHashMap<>();
        waiver.put("PENDING", transitions("approve", "ACTIVE",
                "reject", "REJECTED",
                "expire", "EXPIRED"));
        waiver.put("ACTIVE", transitions("revoke", "REVOKED",
                "expire", "EXPIRED"));
        waiver.put("REJECTED", transitions());
        waiver.put("EXPIRED", transitions());
        waiver.put("REVOKED", transitions());
        TABLE.put(WAIVER, waiver);

        // Go/No-Go record: written once, PMO confirmation moves it to CONFIRMED.
        Map<String, Map<String, String>> review = new LinkedHashMap<>();
        review.put("RECORDED", transitions("confirm", "CONFIRMED"));
        review.put("CONFIRMED", transitions());
        TABLE.put(REVIEW, review);
    }

    private StateTables() {
    }

    /** Legal action codes in the given state, in declaration order. */
    public static Set<String> actions(String type, String status) {
        Map<String, String> actions = row(type, status);
        return actions == null ? Set.of() : new LinkedHashSet<>(actions.keySet());
    }

    /** Target status for an action, or {@code null} when the action is illegal here. */
    public static String target(String type, String status, String action) {
        Map<String, String> actions = row(type, status);
        return actions == null ? null : actions.get(action);
    }

    /** Whether the state is terminal for its type (defects: CLOSED, still re-openable). */
    public static boolean isTerminal(String type, String status) {
        Set<String> terminal = TERMINAL.get(type);
        return terminal != null && terminal.contains(status);
    }

    /** Whether the action increments a defect's reopen counter. */
    public static boolean isReopen(String type, String action) {
        return DEFECT.equals(type) && ("reopen".equals(action) || "verify_reject".equals(action));
    }

    /**
     * All statuses the type can be in, in stable order (lists, filters and the allowedNext
     * payload are generated from this list, so the wire order never depends on map iteration).
     */
    public static List<String> statuses(String type) {
        Map<String, Map<String, String>> typeTable = TABLE.get(type == null ? null : type.toUpperCase());
        return typeTable == null ? List.of() : List.copyOf(typeTable.keySet());
    }

    private static Map<String, String> row(String type, String status) {
        if (type == null || status == null) {
            return null;
        }
        Map<String, Map<String, String>> typeTable = TABLE.get(type.toUpperCase());
        return typeTable == null ? null : typeTable.get(status.toUpperCase());
    }

    private static Map<String, String> transitions(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
