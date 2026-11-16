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
 * <p>r22 split the two halves this class used to mix. It no longer holds a compiled
 * {@code action → function code} table and no longer reads {@link PermissionMatrix}: the action's
 * access level is a <b>runtime</b> fact ({@code sys_action} seeded from {@link ActionRegistry},
 * granted per tenant in {@code sys_role_permission}), so the matrix asks
 * {@link ActionPermissionResolver#decide} for the {@code req.action.<action>} cell instead. What
 * stays here is the part no table can express: <b>relationship semantics</b>. The matrix does not
 * know {@code sys_role_permission} exists; the resolver does not know the requirement state
 * machine exists. Two "single sources of truth" with one job each — reading {@code functionCodes}
 * back into this class would resurrect the second copy of the truth that r18/r21 spent two
 * iterations collapsing.</p>
 *
 * <p>{@code submit}/{@code withdraw} are self-service: the reporter may act on their own
 * requirement whatever their role (the action cell itself must still not be DENY — r21's "an
 * action denial closes the button for everyone, self-service included"), and a reviewer may act on
 * someone else's only when they hold a <b>full</b> {@code req.action.approve} grant <b>and</b> are
 * not a vendor account. The vendor half is load-bearing: vendor1's role code is literally
 * {@code PMO}, so no permission code separates it from the product manager — only
 * {@code vendorId == null} does. That is also why the reviewer branch asks for
 * {@link PermissionMatrix.Access#ALLOW} rather than "not DENY": delegating someone else's submit
 * is an elevation, not a range-narrowed read.</p>
 *
 * <p>Review-side actions ({@link #REVIEW_SIDE}) additionally require an <b>internal</b> account: a
 * vendor login is a vendor even when its role code is {@code PMO}, and reviewing, admitting to the
 * pool or accepting delivery is the buyer's job (2026-11-13 QA BUG-1: vendor1 could
 * {@code review_start} an internal requirement because a function code only describes the role,
 * never the row). The set is written out explicitly rather than derived from
 * {@link ActionRegistry}: "which actions are review-side" is relationship semantics, and letting
 * one registry edit silently move the vendor boundary is exactly the kind of drift the alignment
 * assertions in {@code RequirementActionMatrixTest} are meant to turn red. Everything else is a
 * plain action-cell check, evaluated with the same "not DENY passes" rule as
 * {@code PermissionInterceptor} — {@link PermissionMatrix.Access#RESTRICTED} still passes the
 * action gate and is narrowed later by {@link DataScope}. {@code decompose} and
 * {@code assign-vendor} are <b>not</b> here: they are separate endpoints that own a real function
 * code ({@code req:split} / {@code req:assign-vendor}) and stay guarded by the route table.</p>
 */
public final class RequirementActionMatrix {

    /**
     * The page every requirement action belongs to ({@link ActionRegistry} says the same for all 16
     * requirement actions). Kept as a literal because {@link PermissionRouteTable} declares the
     * action endpoint as "this page + body action code": the three must agree, and the alignment
     * assertion is what keeps them agreeing.
     */
    public static final String PAGE = "req.pool";

    /**
     * Legacy function code of the review side.
     *
     * <p>r22 no longer authorizes with it — the {@code req.action.*} cell carries the verdict now —
     * but it stays a declared constant because {@code scripts/tests/lint.sh} L19 pins the a5 D3
     * judge to this literal: the endpoint's verdict must come from the request body, and the review
     * code is how that rule names the requirement domain.</p>
     */
    public static final String REVIEW = "req:review";

    /** Action code prefix; {@code submit} → {@code req.action.submit}. */
    public static final String ACTION_PREFIX = "req.action.";

    /** The two actions whose verdict depends on who reported the requirement. */
    private static final Set<String> SELF_SERVICE = Set.of("submit", "withdraw");

    /**
     * Review-side actions: an internal account only, whatever the role's grants say.
     *
     * <p>Explicit on purpose — see the class javadoc. 7 of the 16, and {@code RequirementActionMatrixTest}
     * asserts the alignment with {@link ActionRegistry}'s review function codes, so a registry edit
     * that would move the boundary shows up as a red test instead of as a silent widening.</p>
     */
    private static final Set<String> REVIEW_SIDE = Set.of(
            "approve", "reject", "review_start", "review_pass", "review_reject", "accept", "close");

    /** {@code approve}'s action code — the elevation check for someone else's submit/withdraw. */
    private static final String APPROVE_CODE = ACTION_PREFIX + "approve";

    /**
     * The 16 requirement transitions, in the stable order the detail payload and the contract test
     * read. Exactly the set {@link StateTables} declares for BUS/PDR/RD; the contract test keeps
     * the two equal.
     */
    private static final List<String> ACTIONS = List.of(
            "submit", "approve", "reject", "withdraw",
            "review_start", "review_pass", "review_reject", "accept", "close",
            "schedule", "start_dev", "deliver", "merge", "finish",
            "submit_test", "test_pass");

    /** Action → its own name, for the {@link #known} lookup without a linear scan per call. */
    private static final Map<String, String> BY_NAME = new LinkedHashMap<>();

    static {
        for (String action : ACTIONS) {
            BY_NAME.put(action, ACTION_PREFIX + action);
        }
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

    /** Every registered action, in stable declaration order. */
    public static Set<String> actions() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(ACTIONS));
    }

    /** Whether the action is a known requirement transition (i.e. has an action code). */
    public static boolean known(String action) {
        return action != null && BY_NAME.containsKey(action);
    }

    /**
     * The {@code sys_action} code of the action ({@code req.action.<action>}), or {@code null} when
     * the action is not registered here.
     *
     * <p>Replaces the pre-r22 {@code requiredFunction}: the matrix maps an action to <b>its own
     * cell</b>, not to a legacy function code. An unregistered action has no code and therefore no
     * cell — fail-closed, never a default page-level pass.</p>
     */
    public static String actionCode(String action) {
        return action == null ? null : BY_NAME.get(action);
    }

    /**
     * The single authorization predicate: may {@code caller} fire {@code action} on a requirement
     * reported by {@code reporterId}?
     *
     * <p>Evaluated in this order, and every branch fails closed:</p>
     * <ol>
     *   <li>unknown action / missing caller / missing resolver → {@code false};</li>
     *   <li>the action's own {@code req.action.<action>} cell (page {@link #PAGE} included, which
     *       {@link ActionPermissionResolver#decide} applies first) {@code == DENY} → {@code false};</li>
     *   <li>self-service on one's own requirement → {@code true} (the cell was checked in step 2);</li>
     *   <li>self-service on someone else's → internal account, {@code req.action.approve} cell
     *       {@code == ALLOW} (full, not RESTRICTED) and this action's cell not DENY;</li>
     *   <li>review-side → internal account only;</li>
     *   <li>otherwise → the action's cell is not DENY.</li>
     * </ol>
     *
     * <p>Callers must check {@link #known} first: an unknown action is a 409, not a 403. A
     * {@code null} return from a buggy resolver is treated as DENY here — the port's contract says
     * "never null", but an authorization path does not get to trust that.</p>
     */
    public static boolean can(String action, Long reporterId, Caller caller,
                              ActionPermissionResolver resolver) {
        if (action == null || caller == null || resolver == null) {
            return false;
        }
        String code = actionCode(action);
        if (code == null) {
            return false;
        }
        if (cell(resolver, code) == PermissionMatrix.Access.DENY) {
            return false;
        }
        if (SELF_SERVICE.contains(action)) {
            if (reporterId != null && reporterId.longValue() == caller.userId()) {
                return true;
            }
            // 代他人提交/撤回：需要「全量」批准授权（RESTRICTED 不算），且调用者不能是厂商账号。
            return caller.vendorId() == null
                    && cell(resolver, APPROVE_CODE) == PermissionMatrix.Access.ALLOW;
        }
        // 评审侧（approve/reject/review_*/accept/close）是内部职责：厂商账号即使拿到该动作
        // 的 ALLOW（vendor1 的角色码就是 PMO）也不得评审/入池/验收。动作档位只描述角色授权，
        // 表达不了「行属于内部还是某厂商」，只有 vendorId 能区分。
        if (REVIEW_SIDE.contains(action) && caller.vendorId() != null) {
            return false;
        }
        return true;
    }

    /**
     * The actions {@code caller} may fire right now: {@link StateTables}' legal set for
     * {@code (type, status)}, filtered through {@link #can} in declaration order.
     *
     * <p>This is the <b>only</b> source of the {@code allowedActions} payload — the frontend renders
     * it verbatim, so the button list and the executed transition can never disagree. Unknown
     * type/status yields an empty list (the state machine turns that into its own 409).</p>
     */
    public static List<String> filter(String type, String status, Long reporterId, Caller caller,
                                      ActionPermissionResolver resolver) {
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
            if (can(action, reporterId, caller, resolver)) {
                allowed.add(action);
            }
        }
        return allowed.isEmpty() ? List.of() : List.copyOf(allowed);
    }

    /** One cell lookup, page first; a {@code null} answer from the resolver is DENY. */
    private static PermissionMatrix.Access cell(ActionPermissionResolver resolver, String code) {
        PermissionMatrix.Access access = resolver.decide(PAGE, code);
        return access == null ? PermissionMatrix.Access.DENY : access;
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
