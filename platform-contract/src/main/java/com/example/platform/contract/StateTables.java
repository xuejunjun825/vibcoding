package com.example.platform.contract;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The state machines of the platform, as data.
 *
 * <p>Three requirement types plus defects. The table is the <b>only</b> source of "which action
 * is legal now" and "where does it land": services assert against it, detail responses expose it
 * as {@code allowedActions}, and the frontend renders what the server sends instead of copying
 * the table. A second copy is how "button enabled, API returns 409" drift starts.</p>
 *
 * <p>Action codes are lower-case on the wire ({@code start}, {@code verify_pass},
 * {@code review_pass}); callers normalise before asking. Unknown type/status/action returns
 * an empty set / {@code null}, and {@code *StateMachine} turns that into a 409 — never a
 * silent no-op.</p>
 */
public final class StateTables {

    public static final String BUS = "BUS";
    public static final String PDR = "PDR";
    public static final String RD = "RD";
    public static final String DEFECT = "DEFECT";

    private static final Map<String, Map<String, Map<String, String>>> TABLE = new LinkedHashMap<>();
    private static final Map<String, Set<String>> TERMINAL = Map.of(
            BUS, Set.of("CLOSED"),
            PDR, Set.of("ACCEPTED_CLOSED"),
            RD, Set.of("DONE"),
            DEFECT, Set.of("CLOSED"));

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

    /** All statuses the type can be in (used by lists/filters, in stable order). */
    public static Set<String> statuses(String type) {
        Map<String, Map<String, String>> typeTable = TABLE.get(type);
        return typeTable == null ? Set.of() : new LinkedHashSet<>(typeTable.keySet());
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
