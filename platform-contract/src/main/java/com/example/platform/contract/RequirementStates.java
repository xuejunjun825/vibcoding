package com.example.platform.contract;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The requirement lifecycle states that mean "this requirement is done", derived — not listed.
 *
 * <p>Every place that needs "is this requirement finished" (analytics completion rate, the
 * workbench overview card, report cross-tab denominators, the portal todo counter) reads this
 * class instead of writing {@code status IN ('DONE', ...)}. A hand-written set is how the same
 * product ends up with three answers: the earlier drafts of the r9 technical design listed
 * {@code MERGED} and {@code CANCELLED} as completed, and neither is a completed state —
 * {@code MERGED} is a mid-flight RD state ({@code MERGED -> RELEASED}), and {@code CANCELLED}
 * does not exist in {@link StateTables} at all.</p>
 *
 * <p>The set comes from {@link StateTables#isTerminal} over the three requirement flows
 * ({@code BUS}, {@code PDR}, {@code RD}), so adding a terminal state to the state table
 * automatically moves every metric with it. Today that resolves to
 * {@code CLOSED} (BUS), {@code ACCEPTED_CLOSED} (PDR), {@code DONE} (RD).</p>
 *
 * <p>Not to be confused with "in flight": {@link #SUBMITTED} and {@link #PENDING_TEST} are
 * shared by more than one flow and only mean something together with the flow type
 * ({@code StateTables.target(type, ...)}), so they are constants here for readable queries,
 * not members of the completed set.</p>
 */
public final class RequirementStates {

    /** BUS: submitted, waiting for the reviewer. Also the PDR/RD entry state. */
    public static final String SUBMITTED = "SUBMITTED";

    /** BUS/PDR: accepted by the reviewer and queued for delivery (capacity-pooled). */
    public static final String POOLED = "POOLED";

    /** RD: developed, waiting for the test execution. */
    public static final String PENDING_TEST = "PENDING_TEST";

    /** The three requirement flows; a requirement is one of BUS / PDR / RD. */
    private static final List<String> FLOWS = List.of(StateTables.BUS, StateTables.PDR, StateTables.RD);

    private static final Set<String> COMPLETE = deriveComplete();

    private RequirementStates() {
    }

    /**
     * Terminal statuses across the requirement flows, in flow order and stable.
     *
     * <p>Unmodifiable: a caller that mutated this set would change every metric in the process
     * without a code change, which is exactly the drift the class exists to prevent.</p>
     */
    public static Set<String> completeStatuses() {
        return COMPLETE;
    }

    /** Whether the status means "requirement finished", case-insensitively as the state table is. */
    public static boolean isComplete(String status) {
        return status != null && COMPLETE.contains(status.toUpperCase(Locale.ROOT));
    }

    private static Set<String> deriveComplete() {
        Set<String> complete = new LinkedHashSet<>();
        for (String flow : FLOWS) {
            // A terminal state is a leaf: it has no outgoing transition, so StateTables has no row
            // for it (statuses(BUS) = SUBMITTED/APPROVING/PENDING_REVIEW/POOLED — CLOSED is only
            // ever a *target*). Deriving from statuses() alone therefore yields an empty set, which
            // is exactly the silent "0 requirements are done" failure this class must not ship.
            // So walk the targets too and let isTerminal() decide; both halves are public API, and
            // the status list is still what defines the universe, not a literal here.
            Set<String> candidates = new LinkedHashSet<>(StateTables.statuses(flow));
            for (String status : StateTables.statuses(flow)) {
                for (String action : StateTables.actions(flow, status)) {
                    String target = StateTables.target(flow, status, action);
                    if (target != null) {
                        candidates.add(target);
                    }
                }
            }
            for (String status : candidates) {
                if (StateTables.isTerminal(flow, status)) {
                    complete.add(status);
                }
            }
        }
        return Collections.unmodifiableSet(complete);
    }
}
