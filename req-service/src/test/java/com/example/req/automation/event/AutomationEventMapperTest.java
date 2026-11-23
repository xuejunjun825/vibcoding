package com.example.req.automation.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the audit -> automation event mapping (r39 T3, TECHNICAL §3.2).
 *
 * <p>These assertions are the reason a status change or a field edit can be trusted to produce
 * exactly the events the rule conditions expect: the mapping is a pure function, so every branch
 * is provable here without a database. In particular they pin the two things that silently break
 * automation if they drift — the event id (dedupe key) and the snapshot key set.</p>
 */
class AutomationEventMapperTest {

    private static final String REQUIREMENT = "REQUIREMENT";
    private static final String RELEASE = "RELEASE";

    /** Null-friendly map builder: the snapshot deliberately carries explicit nulls. */
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put((String) pairs[i], pairs[i + 1]);
        }
        return values;
    }

    @Test
    @DisplayName("CREATE -> one REQUIREMENT_CREATED event carrying the full snapshot")
    void createMapsToRequirementCreated() {
        Map<String, Object> detail = map(
                "auditId", 101L, "type", "RD", "code", "RD-1", "status", "DRAFT",
                "priority", "P1", "estimateDays", new BigDecimal("3.5"), "domainId", 7L,
                "ownerUserId", 12L, "releaseId", null);

        List<AutomationEventDraft> drafts =
                AutomationEventMapper.map("CREATE", REQUIREMENT, 500L, detail);

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).eventType()).isEqualTo(AutomationEventMapper.EVENT_REQUIREMENT_CREATED);
        assertThat(drafts.get(0).eventId()).isEqualTo("REQ:500:CREATED:101");
        assertThat(drafts.get(0).requirementId()).isEqualTo(500L);
        assertThat(drafts.get(0).payload())
                .containsEntry("type", "RD")
                .containsEntry("status", "DRAFT")
                .containsEntry("priority", "P1")
                .containsEntry("domainId", 7L);
    }

    @Test
    @DisplayName("snapshot always carries the §4.2 key set, missing keys as explicit null")
    void snapshotAlwaysCarriesEveryKey() {
        Map<String, Object> detail = map("auditId", 1L, "type", "RD", "status", "DRAFT");

        Map<String, Object> payload =
                AutomationEventMapper.map("CREATE", REQUIREMENT, 500L, detail).get(0).payload();

        assertThat(payload).hasSize(13);
        assertThat(payload.keySet()).containsExactly(
                "type", "priority", "status", "approvalResult", "estimateDays", "domainId",
                "ownerVendorId", "assigneeVendorId", "ownerUserId", "releaseId",
                "field", "before", "after");
        assertThat(payload.get("priority")).isNull();
        assertThat(payload.get("approvalResult")).isNull();
        assertThat(payload.get("field")).isNull();
    }

    @Test
    @DisplayName("approved STATE_CHANGE -> STATUS_CHANGE with approvalResult=APPROVED")
    void approvedStatusChange() {
        Map<String, Object> detail = map(
                "auditId", 102L, "type", "RD", "priority", "P1", "from", "APPROVING",
                "to", "PENDING_REVIEW", "action", "approve", "source", "APPROVAL",
                "approvalInstanceId", 9L, "approvalResult", "APPROVED", "operator", 1L);

        AutomationEventDraft draft =
                AutomationEventMapper.map("STATE_CHANGE", REQUIREMENT, 500L, detail).get(0);

        assertThat(draft.eventType()).isEqualTo(AutomationEventMapper.EVENT_STATUS_CHANGE);
        assertThat(draft.eventId()).isEqualTo("REQ:500:STATUS:102");
        assertThat(draft.payload()).containsEntry("status", "PENDING_REVIEW")
                .containsEntry("approvalResult", "APPROVED")
                .containsEntry("priority", "P1");
    }

    @Test
    @DisplayName("rejected STATE_CHANGE falls back to the action name: approvalResult=REJECTED")
    void rejectedStatusChangeDerivesApprovalResult() {
        Map<String, Object> detail = map(
                "auditId", 103L, "type", "RD", "from", "APPROVING", "to", "PENDING_REVIEW",
                "action", "reject", "source", "APPROVAL", "operator", 1L);

        try {
            AutomationEventMapper.map("STATE_CHANGE", REQUIREMENT, 500L, detail).get(0);
        } finally {
            // assertion kept below for readability of the failure message
        }
        AutomationEventDraft draft =
                AutomationEventMapper.map("STATE_CHANGE", REQUIREMENT, 500L, detail).get(0);

        assertThat(draft.payload().get("approvalResult")).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("UPDATE expands to one FIELD_CHANGE per changed field, unchanged fields skipped")
    void updateExpandsPerChangedField() {
        Map<String, Object> detail = map(
                "auditId", 104L, "type", "RD", "status", "IN_DEV",
                "before", map("priority", "P2", "ownerUserId", 12L,
                        "estimateDays", new BigDecimal("3.0"), "releaseId", null),
                "after", map("priority", "P1", "ownerUserId", 12L,
                        "estimateDays", new BigDecimal("5.0"), "releaseId", null));

        List<AutomationEventDraft> drafts =
                AutomationEventMapper.map("UPDATE", REQUIREMENT, 500L, detail);

        assertThat(drafts).hasSize(2);
        assertThat(drafts).extracting(AutomationEventDraft::eventId).containsExactly(
                "REQ:500:FIELD:priority:104", "REQ:500:FIELD:estimate_days:104");
        assertThat(drafts.get(0).payload()).containsEntry("field", "priority")
                .containsEntry("before", "P2").containsEntry("after", "P1");
        assertThat(drafts.get(1).payload()).containsEntry("field", "estimate_days")
                .containsEntry("after", new BigDecimal("5.0"));
    }

    @Test
    @DisplayName("UPDATE with no tracked value changed produces no event")
    void updateWithoutRealChangeProducesNothing() {
        Map<String, Object> detail = map(
                "auditId", 105L, "type", "RD",
                "before", map("priority", "P1", "estimateDays", new BigDecimal("3.00")),
                "after", map("priority", "P1", "estimateDays", new BigDecimal("3.0")));

        assertThat(AutomationEventMapper.map("UPDATE", REQUIREMENT, 500L, detail)).isEmpty();
    }

    @Test
    @DisplayName("ESTIMATE_APPLIED carries before.estimateDays for the FIELD_CHANGE")
    void estimateAppliedCarriesBefore() {
        Map<String, Object> detail = map(
                "auditId", 106L, "type", "RD", "estimateDays", new BigDecimal("3.5"),
                "instanceId", 77L, "before", map("estimateDays", new BigDecimal("2.0")));

        AutomationEventDraft draft =
                AutomationEventMapper.map("ESTIMATE_APPLIED", REQUIREMENT, 500L, detail).get(0);

        assertThat(draft.eventType()).isEqualTo(AutomationEventMapper.EVENT_FIELD_CHANGE);
        assertThat(draft.payload()).containsEntry("field", "estimate_days")
                .containsEntry("before", new BigDecimal("2.0"))
                .containsEntry("after", new BigDecimal("3.5"));
    }

    @Test
    @DisplayName("RELEASE_MEMBER_ADD expands changed[] into one release_id event per requirement")
    void releaseMemberAddExpandsChanged() {
        Map<String, Object> detail = map(
                "auditId", 107L, "requirementIds", List.of(501L, 502L), "count", 2,
                "changed", List.of(
                        map("id", 501L, "before", null, "after", 900L),
                        map("id", 502L, "before", null, "after", 900L)));

        List<AutomationEventDraft> drafts =
                AutomationEventMapper.map("RELEASE_MEMBER_ADD", RELEASE, 900L, detail);

        assertThat(drafts).hasSize(2);
        assertThat(drafts).extracting(AutomationEventDraft::requirementId)
                .containsExactly(501L, 502L);
        assertThat(drafts.get(0).eventId()).isEqualTo("REQ:501:FIELD:release_id:107");
        assertThat(drafts.get(0).payload()).containsEntry("field", "release_id")
                .containsEntry("releaseId", 900L);
    }

    @Test
    @DisplayName("release change without changed[] cannot be attributed -> no event")
    void releaseWithoutChangedProducesNothing() {
        Map<String, Object> detail = map("auditId", 108L, "requirementIds", List.of(501L));

        assertThat(AutomationEventMapper.map("RELEASE_MEMBER_ADD", RELEASE, 900L, detail)).isEmpty();
        assertThat(AutomationEventMapper.map("RELEASE_MEMBER_REMOVE", RELEASE, 900L, detail))
                .isEmpty();
    }

    @Test
    @DisplayName("OWNER_ASSIGN maps the nested before/after into owner_user_id")
    void ownerAssignMapsNestedPair() {
        Map<String, Object> detail = map(
                "auditId", 109L, "type", "RD", "status", "IN_DEV",
                "ownerUserId", map("before", 12L, "after", 34L),
                "source", "RULE", "ruleId", 3L, "runId", 44L, "automationOrigin", "RULE");

        AutomationEventDraft draft =
                AutomationEventMapper.map("OWNER_ASSIGN", REQUIREMENT, 500L, detail).get(0);

        assertThat(draft.payload()).containsEntry("field", "owner_user_id")
                .containsEntry("before", 12L).containsEntry("after", 34L)
                .containsEntry("ownerUserId", 34L);
        assertThat(draft.eventId()).isEqualTo("REQ:500:FIELD:owner_user_id:109");
    }

    @Test
    @DisplayName("automationOrigin/automationChainDepth pass through, depth clamped to 1..9")
    void originAndChainDepthPassThrough() {
        Map<String, Object> detail = map("auditId", 110L, "type", "RD", "status", "IN_DEV",
                "automationOrigin", "RULE", "automationChainDepth", 3);

        AutomationEventDraft draft =
                AutomationEventMapper.map("CREATE", REQUIREMENT, 500L, detail).get(0);

        assertThat(draft.origin()).isEqualTo(AutomationEventMapper.ORIGIN_RULE);
        assertThat(draft.chainDepth()).isEqualTo(3);

        Map<String, Object> deep = map("auditId", 111L, "type", "RD",
                "automationOrigin", "RULE", "automationChainDepth", 99);
        assertThat(AutomationEventMapper.map("CREATE", REQUIREMENT, 500L, deep).get(0).chainDepth())
                .isEqualTo(9);
        assertThat(AutomationEventMapper.map("CREATE", REQUIREMENT, 500L,
                map("auditId", 112L)).get(0).origin())
                .isEqualTo(AutomationEventMapper.ORIGIN_USER);
    }

    @Test
    @DisplayName("actions that are not triggers, and actions without an aggregate id, produce nothing")
    void nonTriggersProduceNothing() {
        Map<String, Object> detail = map("auditId", 113L, "type", "RD");

        assertThat(AutomationEventMapper.map("PERMISSION_DENY", REQUIREMENT, 500L, detail)).isEmpty();
        assertThat(AutomationEventMapper.map("CREATE", "USER", 500L, detail)).isEmpty();
        assertThat(AutomationEventMapper.map("CREATE", REQUIREMENT, null, detail)).isEmpty();
        assertThat(AutomationEventMapper.map(null, REQUIREMENT, 500L, detail)).isEmpty();
        assertThat(AutomationEventMapper.map("CREATE", REQUIREMENT, 500L, null)).isEmpty();
    }

    @Test
    @DisplayName("daily scan ids keep the requirement id, so N requirements are N events")
    void scheduleIdsAreRequirementScoped() {
        List<String> ids = new ArrayList<>();
        ids.add(AutomationEventMapper.scheduleEventId("2026-11-23", 501L));
        ids.add(AutomationEventMapper.scheduleEventId("2026-11-23", 502L));

        assertThat(ids).containsExactly("SCHEDULE:2026-11-23:501", "SCHEDULE:2026-11-23:502");
        assertThat(AutomationEventMapper.scheduleTruncatedEventId("2026-11-23"))
                .isEqualTo("SCHEDULE:2026-11-23:TRUNCATED");
    }
}
