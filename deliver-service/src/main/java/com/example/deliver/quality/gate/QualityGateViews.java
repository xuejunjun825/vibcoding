package com.example.deliver.quality.gate;

import java.util.List;
import java.util.Map;

import com.example.platform.contract.quality.GateSnapshot;

/**
 * The HTTP shapes of the quality gate (TECHNICAL §5.3) — view records, not the contract records.
 *
 * <p>Why not return {@link GateSnapshot} directly: its {@code computedAt} is an {@link
 * java.time.Instant}, and deliver-service's mapper is a plain Jackson 2 {@code ObjectMapper}
 * without the JavaTimeModule, so serializing it throws "Java 8 date/time type not supported".
 * Every quality view therefore renders timestamps as ISO-8601 strings — which is also what the
 * {@code req} side parses ({@code Instant.parse}) and what the SPA formats.</p>
 *
 * <p>Waivers and the report block travel as generic maps inside a snapshot because they are a
 * verbatim slice of the frozen {@code snapshot_json} (AC-7 replay must show exactly what was
 * stored, not a re-query).</p>
 */
public final class QualityGateViews {

    private QualityGateViews() {
    }

    /** One immutable gate snapshot as the API renders it. {@code waivers}/{@code report} come from the stored JSON. */
    public record Snapshot(long snapshotId,
                           long releaseId,
                           long deployOrderId,
                           Long reportVersionId,
                           // AC-5.6: the internal evaluate response must expose the gate row and the
                           // report it froze, so req can persist deploy_order.quality_gate_id/report_id.
                           Long gateId,
                           Long reportId,
                           String result,
                           String hash,
                           String computedAt,
                           List<GateSnapshot.GateRuleResult> rules,
                           List<Long> openP0P1,
                           List<Map<String, Object>> waivers,
                           Map<String, Object> report) {
    }

    /** One release's current gate state on the overview list. */
    public record GateRow(long releaseId,
                          String releaseName,
                          Long gateId,
                          String status,
                          String evaluatedAt,
                          int activeWaiverCount,
                          Long reportId,
                          Long reportVersionId,
                          Integer reportVersionNo,
                          Integer snapshotId,
                          String hash,
                          List<String> failedRules,
                          Integer passedRules,
                          Integer totalRules) {
    }

    /** A configured rule merged with the live evaluation of it (so the page needs one request). */
    public record GateRule(String code,
                           String name,
                           String status,
                           boolean enabled,
                           boolean waivable,
                           boolean required,
                           Integer threshold,
                           String thresholdText,
                           String actual,
                           String fixHint,
                           String lastEvaluatedAt) {
    }

    public record GateDetail(long releaseId,
                             String releaseName,
                             String status,
                             Integer rulesetVersion,
                             String evaluatedAt,
                             List<GateRule> rules,
                             List<Snapshot> snapshots,
                             List<WaiverRow> waivers) {
    }

    /** One row of {@code PUT /quality-gates/{releaseId}}; {@code threshold} is QG-03's percent. */
    public record RuleConfigCmd(String code, Boolean enabled, Integer threshold) {
    }

    public record ConfigCmd(List<RuleConfigCmd> rules) {
    }

    public record EvaluateCmd(Long deployOrderId, List<Long> requirementIds) {
    }

    public record WaiverRow(long id,
                            Long gateId,
                            long releaseId,
                            String releaseName,
                            String ruleCode,
                            String ruleName,
                            String reason,
                            String impactScope,
                            String expiryAt,
                            Long applicantId,
                            String applicantName,
                            Long approverId,
                            String approverName,
                            String approvedAt,
                            String status,
                            Long revokedBy,
                            String revokedAt,
                            String revokeReason,
                            String createdAt) {
    }

    /**
     * Waiver application. {@code ruleCode} is the documented field; {@code ruleCodes} exists only so
     * a multi-rule payload is rejected with {@code WAIVER_RULE_SINGLE_ONLY} instead of being
     * silently narrowed to one rule (AC-6.1: one waiver covers one rule).
     */
    public record WaiverApplyCmd(Long releaseId,
                                 String ruleCode,
                                 List<String> ruleCodes,
                                 String reason,
                                 String impactScope,
                                 Integer expiryDays) {
    }

    /**
     * Approve/reject carry a comment; revoke carries a reason. Both are read leniently.
     * {@code confirmSelfApproval} is the PRD's "二次确认": a platform administrator who approves
     * their own waiver must say so explicitly, everyone else is refused outright.
     */
    public record DecisionCmd(String comment, String reason, Boolean confirmSelfApproval) {
    }

    public record ReplayDiff(List<GateSnapshot.GateRuleResult> changedRules) {
    }

    /** Replay is a flat snapshot plus the diff against today's evidence — no recomputation of the result. */
    public record Replay(long snapshotId,
                         long releaseId,
                         long deployOrderId,
                         Long reportVersionId,
                         String result,
                         String hash,
                         String computedAt,
                         List<GateSnapshot.GateRuleResult> rules,
                         List<Long> openP0P1,
                         List<Map<String, Object>> waivers,
                         Map<String, Object> report,
                         ReplayDiff diff) {
    }
}
