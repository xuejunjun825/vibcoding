package com.example.deliver.gate;

/**
 * One planned case as the gate sees it: the pinned version, the latest result and the note that
 * explains a blocked/skipped outcome. A pure value so {@link GatePolicy} can be unit-tested
 * without a database.
 *
 * @param caseId       test case id
 * @param caseCode     human code (TC-0007), used in blocker text
 * @param caseVersion  the version pinned by the plan row
 * @param result       NOT_RUN / PASS / FAIL / BLOCKED / SKIP
 * @param note         executor's note; required by LOOSE mode for BLOCKED/SKIP
 * @param requirementId requirement the plan row was added for
 * @param planId       plan the row belongs to
 */
public record GateCase(long caseId,
                       String caseCode,
                       int caseVersion,
                       String result,
                       String note,
                       Long requirementId,
                       long planId) {

    public GateCase {
        result = result == null || result.isBlank() ? "NOT_RUN" : result;
    }
}
