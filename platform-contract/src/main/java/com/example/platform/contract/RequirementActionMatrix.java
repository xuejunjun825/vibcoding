package com.example.platform.contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who may fire which requirement-transition action — the single authorization truth.
 *
 * <p>Before r17 the whole {@code POST /requirements/{id}/actions} endpoint was guarded by one
 * function code ({@code req:review}) in {@link PermissionMatrix}'s route table, so the reporter's
 * own {@code submit} and the reviewer's {@code review_start} shared a single verdict: a BUSINESS
 * account (holding only {@code req:report:bus}) could see the button but always got 403, while a
 * vendor account whose role code happened to be {@code PMO} was allowed to act on other people's
 * requirements. A permission <b>code</b> cannot express that split — {@code submit} is not a
 * function, it is a relationship between one row and one caller — so this class carries one
 * predicate per action and both consumers call it: {@code RequirementService.allowedActions}
 * filters the button list with {@link #filter}, and {@code RequirementService.transition}
 * authorizes the actual transition with {@link #can}. Two implementations would drift into
 * "button enabled, API returns 403" on the first change.</p>
 *
 * <p>Live here in {@code platform-contract} for the same reason {@link StateTables} and
 * {@link PermissionMatrix} do: the gateway and both services must agree, and this module is
 * deliberately dependency-free (JDK types only). It therefore takes a {@link Caller} snapshot
 * rather than {@code TenantContext} or {@code RequirementEntity}, and reads the legal action set
 * from {@link StateTables} instead of keeping a second transition table. A missing row is a
 * denial: {@link #can} returns {@code false} for anything not registered, and callers must treat
 * {@link #known} {@code == false} as "illegal action → 409", never as "403".</p>
 *
 * <p>{@code submit}/{@code withdraw} are self-service: the reporter may always act on their own
 * requirement (whatever their role), and a reviewer may act on someone else's only when they hold
 * a <b>full</b> {@code req:review} grant <b>and</b> are not a vendor account. The vendor half is
 * load-bearing: vendor1's role code is literally {@code PMO}, so no permission code separates it
 * from the product manager — only {@code vendorId == null} does. That is also why the reviewer
 * branch asks for {@link PermissionMatrix.Access#ALLOW} rather than "not DENY": delegating
 * someone else's submit is an elevation, not a range-narrowed read.</p>
 *
 * <p>Review-side actions ({@code approve}, {@code reject}, {@code review_start},
 * {@code review_pass}, {@code review_reject}, {@code accept}, {@code close}) additionally require
 * an <b>internal</b> account: a vendor login is a vendor even when its role code is {@code PMO},
 * and reviewing, admitting to the pool or accepting delivery is the buyer's job (2026-11-13 QA
 * BUG-1: vendor1 could {@code review_start} an internal requirement because a function code only
 * describes the role, never the row). Everything else is a plain function check
 * ({@code req:review}, {@code req:deliver}, {@code test:quick}, {@code release:plan}), evaluated
 * with the same "not DENY passes" rule as {@code PermissionInterceptor} —
 * {@link PermissionMatrix.Access#RESTRICTED} still passes the function gate and is narrowed later
 * by {@link DataScope}. {@code decompose} and
 * {@code assign-vendor} are <b>not</b> here: they are separate endpoints that own a real function
 * code ({@code req:split} / {@code req:assign-vendor}) and stay guarded by the route table.</p>
 */
public final class RequirementActionMatrix {

    /** Function code that gates review-side actions and elevates submit/withdraw. */
    public static final String REVIEW = "req:review";

    /** The two actions whose verdict depends on who reported the requirement. */
    private static final Set<String> SELF_SERVICE = Set.of("submit", "withdraw");

    /**
     * Action → the function code it requires, in a stable order (the order the detail payload and
     * the contract test read). Actions listed here are exactly the 16 transitions declared by
     * {@link StateTables} for BUS/PDR/RD; the contract test keeps the two sets equal.
     */
    private static final Map<String, String> REQUIRED_FUNCTION = new LinkedHashMap<>();

    static {
        // Review side: the reviewer/pool owner decides whether a submitted requirement enters review.
        REQUIRED_FUNCTION.put("submit", REVIEW);
        REQUIRED_FUNCTION.put("approve", REVIEW);
        REQUIRED_FUNCTION.put("reject", REVIEW);
        REQUIRED_FUNCTION.put("withdraw", REVIEW);
        REQUIRED_FUNCTION.put("review_start", REVIEW);
        REQUIRED_FUNCTION.put("review_pass", REVIEW);
        REQUIRED_FUNCTION.put("review_reject", REVIEW);
        REQUIRED_FUNCTION.put("accept", REVIEW);
        REQUIRED_FUNCTION.put("close", REVIEW);
        // Delivery side.
        REQUIRED_FUNCTION.put("schedule", "release:plan");
        REQUIRED_FUNCTION.put("start_dev", "req:deliver");
        REQUIRED_FUNCTION.put("deliver", "req:deliver");
        REQUIRED_FUNCTION.put("merge", "req:deliver");
        REQUIRED_FUNCTION.put("finish", "req:deliver");
        // Test side.
        REQUIRED_FUNCTION.put("submit_test", "test:quick");
        REQUIRED_FUNCTION.put("test_pass", "test:quick");
    }

    private RequirementActionMatrix() {
    }

    /**
     * Caller snapshot assembled by the service from {@code TenantContext}.
     *
     * <p>Immutable; {@code roles} is defensively copied and never {@code null}. {@code vendorId}
     * is {@code null} for internal (non-vendor) accounts, which is the only thing that tells a
     * product manager apart from vendor1 holding the same {@code PMO} role code.</p>
     */
    public record Caller(long userId, List<String> roles, Long vendorId) {

        public Caller {
            roles = copyRoles(roles);
        }
    }

    /** Whether the action has a row in this matrix (i.e. it is a known requirement transition). */
    public static boolean known(String action) {
        return action != null && REQUIRED_FUNCTION.containsKey(action);
    }

    /**
     * The single authorization predicate: may {@code caller} fire {@code action} on a requirement
     * reported by {@code reporterId}?
     *
     * <p>Returns {@code false} for unknown actions and for a missing caller — fail-closed. Callers
     * must check {@link #known} first: an unknown action is a 409, not a 403.</p>
     */
    public static boolean can(String action, Long reporterId, Caller caller) {
        if (action == null || caller == null) {
            return false;
        }
        String function = REQUIRED_FUNCTION.get(action);
        if (function == null) {
            return false;
        }
        if (SELF_SERVICE.contains(action)) {
            if (reporterId != null && reporterId.longValue() == caller.userId()) {
                return true;
            }
            // 代他人提交/撤回：需要「全量」评审授权，且调用者不能是厂商账号。
            return caller.vendorId() == null
                    && PermissionMatrix.accessOf(function, caller.roles())
                    == PermissionMatrix.Access.ALLOW;
        }
        // 评审侧（approve/reject/review_*/accept/close）是内部职责：厂商账号即使持 req:review
        // （vendor1 的角色码就是 PMO）也不得评审/入池/验收。权限码只描述角色，表达不了
        // 「行属于内部还是某厂商」，只有 vendorId 能区分。
        if (REVIEW.equals(function) && caller.vendorId() != null) {
            return false;
        }
        return PermissionMatrix.accessOf(function, caller.roles())
                != PermissionMatrix.Access.DENY;
    }

    /**
     * The actions {@code caller} may fire right now: {@link StateTables}' legal set for
     * {@code (type, status)}, filtered through {@link #can} in declaration order.
     *
     * <p>This is the <b>only</b> source of the {@code allowedActions} payload — the frontend renders
     * it verbatim, so the button list and the executed transition can never disagree. Unknown
     * type/status yields an empty list (the state machine turns that into its own 409).</p>
     */
    public static List<String> filter(String type, String status, Long reporterId, Caller caller) {
        Set<String> legal;
        try {
            legal = StateTables.actions(type, status);
        } catch (RuntimeException ex) {
            // Mirrors RequirementStateMachine's tolerance: an unusable table is "no actions",
            // never a 500 and never a silent pass.
            return List.of();
        }
        if (legal.isEmpty()) {
            return List.of();
        }
        List<String> allowed = new ArrayList<>(legal.size());
        for (String action : legal) {
            if (can(action, reporterId, caller)) {
                allowed.add(action);
            }
        }
        return allowed.isEmpty() ? List.of() : List.copyOf(allowed);
    }

    /** Every registered action, in stable order (contract test / diagnostics). */
    public static Set<String> actions() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(REQUIRED_FUNCTION.keySet()));
    }

    /** Function code required by the action, or {@code null} when the action is not registered. */
    public static String requiredFunction(String action) {
        return action == null ? null : REQUIRED_FUNCTION.get(action);
    }

    private static List<String> copyRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        List<String> copy = new ArrayList<>(roles.size());
        for (String role : roles) {
            if (role != null && !role.isBlank()) {
                copy.add(role);
            }
        }
        return copy.isEmpty() ? List.of() : Collections.unmodifiableList(copy);
    }
}
