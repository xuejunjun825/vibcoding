package com.example.deliver.quality.gate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.deliver.quality.aggregate.QualityAggregate;
import com.example.deliver.quality.aggregate.QualityAggregator;
import com.example.deliver.quality.aggregate.QualityDigest;
import com.example.deliver.support.Rows;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.contract.quality.GateSnapshot;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * The only implementation of QG-01 … QG-05 (AC-5, AC-6).
 *
 * <p>{@code req-service} must never recompute a rule: it reads {@code failedRules} from the
 * snapshot this class produced, otherwise the admission dialog and the report page would disagree
 * about the same release. Two decisions are worth knowing when reading the code:</p>
 *
 * <ul>
 *   <li><b>Counts, then one comparison.</b> Rates are compared as integers
 *       ({@code passed * 100 >= threshold * planned}) so a 18/19 release cannot pass a 95% rule
 *       because {@code MetricFormulas} rounds 0.9474 up to 0.95 for display. The displayed text
 *       keeps the unrounded 94.7% for the same reason.</li>
 *   <li><b>Evidence is captured, not re-queried.</b> {@link #document(Evaluation, long, String)}
 *       writes the report reference, the digest, the open defect ids and the waivers that applied
 *       into the snapshot JSON, so AC-7 replay shows what the gate saw even after the data moved.</li>
 * </ul>
 */
@Component
public class GateEvaluator {

    /** The report the gate accepted; empty when no signed RELEASE report exists. */
    public record ReportFacts(long reportId,
                              long versionId,
                              int versionNo,
                              String conclusion,
                              LocalDateTime signedAt,
                              String resultDigest,
                              Map<Long, Integer> planRevisions) {
    }

    /** An approved waiver that is still inside its validity window. */
    public record WaiverFacts(long id, String ruleCode, Long approverId, LocalDateTime expiryAt) {
    }

    /** Everything the five rules need, read once per evaluation. */
    public record Facts(long releaseId,
                        String releaseName,
                        long domainId,
                        String gateMode,
                        int validityDays,
                        Long gateId,
                        String gateStatus,
                        String gateEvaluatedAt,
                        List<GateRuleset.RuleConfig> config,
                        QualityAggregate aggregate,
                        String digest,
                        ReportFacts report,
                        List<Long> openP0P1,
                        List<WaiverFacts> waivers,
                        long totalRequirements,
                        long coveredRequirements) {
    }

    /** One rule's outcome plus the evidence slice stored in the snapshot. */
    public record RuleOutcome(GateSnapshot.GateRuleResult result, Map<String, Object> evidence) {
    }

    /** A full evaluation: the aggregate result, every rule, and the facts behind them. */
    public record Evaluation(String result, List<RuleOutcome> outcomes, Facts facts) {

        public List<GateSnapshot.GateRuleResult> ruleResults() {
            List<GateSnapshot.GateRuleResult> rules = new ArrayList<>(outcomes.size());
            for (RuleOutcome outcome : outcomes) {
                rules.add(outcome.result());
            }
            return rules;
        }

        public List<GateSnapshot.GateRuleResult> failed() {
            List<GateSnapshot.GateRuleResult> failed = new ArrayList<>();
            for (GateSnapshot.GateRuleResult rule : ruleResults()) {
                if (!rule.passed()) {
                    failed.add(rule);
                }
            }
            return failed;
        }
    }

    private final ScopedJdbc jdbcRead;
    private final QualityAggregator aggregator;
    private final QualityDigest digest;
    private final GateRuleset ruleset;
    private final ObjectMapper json;

    public GateEvaluator(ScopedJdbc jdbcRead,
                         QualityAggregator aggregator,
                         QualityDigest digest,
                         GateRuleset ruleset,
                         ObjectMapper qualityObjectMapper) {
        this.jdbcRead = jdbcRead;
        this.aggregator = aggregator;
        this.digest = digest;
        this.ruleset = ruleset;
        this.json = qualityObjectMapper;
    }

    // ------------------------------------------------------------------ facts

    /** Read the release, its gate configuration, execution rollup, report and defects. */
    public Facts facts(long releaseId) {
        Map<String, Object> release = jdbcRead.one("""
                SELECT rel.id, rel.name, rel.domain_id, rel.gate_mode, rel.report_validity_days
                FROM `release` rel
                WHERE /*scope:rel*/ AND rel.id = :releaseId
                """, Map.of("releaseId", releaseId));
        long domainId = Rows.toLong(Rows.object(release, "domain_id"), 0L);
        String gateMode = Rows.str(release, "gate_mode", "STRICT");
        int validityDays = Rows.toInt(Rows.object(release, "report_validity_days"), 7);

        Map<String, Object> gate = jdbcRead.one("""
                SELECT g.id, g.status, g.evaluated_at, g.ruleset_version
                FROM quality_gate g
                JOIN `release` rel ON rel.id = g.release_id
                WHERE /*scope:rel*/ AND g.release_id = :releaseId
                """, Map.of("releaseId", releaseId));

        List<Map<String, Object>> ruleRows = jdbcRead.list("""
                SELECT r.rule_code, r.enabled, r.threshold_json
                FROM quality_gate_rule r
                JOIN quality_gate g ON g.id = r.gate_id
                JOIN `release` rel ON rel.id = g.release_id
                WHERE /*scope:rel*/ AND g.release_id = :releaseId
                """, Map.of("releaseId", releaseId));
        List<GateRuleset.RuleConfig> config = mergeConfig(ruleRows);

        QualityAggregate aggregate = aggregator.aggregateRelease(releaseId);
        String resultDigest = digest.digest(releaseId);
        ReportFacts report = reportFacts(releaseId);
        List<Long> openP0P1 = openP0P1(releaseId);
        List<WaiverFacts> waivers = activeWaivers(releaseId);
        long totalRequirements = requirementCount(releaseId);
        long coveredRequirements = coveredRequirementCount(releaseId);

        Long gateId = gate == null ? null : Rows.toLong(Rows.object(gate, "id"));
        String gateStatus = gate == null ? null : Rows.str(gate, "status");
        String gateEvaluatedAt = gate == null ? null : iso(Rows.toDateTime(Rows.object(gate, "evaluated_at")));

        return new Facts(releaseId, Rows.str(release, "name", ""), domainId, gateMode, validityDays,
                gateId, gateStatus, gateEvaluatedAt, config, aggregate, resultDigest, report,
                openP0P1, waivers, totalRequirements, coveredRequirements);
    }

    private List<GateRuleset.RuleConfig> mergeConfig(List<Map<String, Object>> rows) {
        Map<String, GateRuleset.RuleConfig> byCode = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String code = Rows.str(row, "rule_code");
            if (code == null) {
                continue;
            }
            String thresholdJson = Rows.str(row, "threshold_json", ruleset.defaultJson(code));
            byCode.put(code, new GateRuleset.RuleConfig(code,
                    Rows.toInt(Rows.object(row, "enabled"), 1) == 1, thresholdJson));
        }
        List<GateRuleset.RuleConfig> merged = new ArrayList<>();
        for (GateRuleset.RuleDef def : ruleset.rules()) {
            GateRuleset.RuleConfig configured = byCode.get(def.code());
            merged.add(configured != null ? configured
                    : new GateRuleset.RuleConfig(def.code(), def.defaultEnabled(), def.defaultJson()));
        }
        return merged;
    }

    private ReportFacts reportFacts(long releaseId) {
        Map<String, Object> row = jdbcRead.one("""
                SELECT r.id AS report_id, v.id AS version_id, v.version_no, v.conclusion,
                       v.signed_at, v.result_digest, v.snapshot_json
                FROM test_report r
                JOIN test_report_version v ON v.report_id = r.id AND v.version_no = r.current_version
                WHERE /*scope:r*/ AND r.release_id = :releaseId AND r.scope = 'RELEASE'
                  AND r.status = 'SIGNED' AND v.status = 'SIGNED' AND v.signed_at IS NOT NULL
                ORDER BY v.signed_at DESC, v.id DESC
                LIMIT 1
                """, Map.of("releaseId", releaseId));
        if (row == null) {
            return null;
        }
        return new ReportFacts(
                Rows.toLong(Rows.object(row, "report_id"), 0L),
                Rows.toLong(Rows.object(row, "version_id"), 0L),
                Rows.toInt(Rows.object(row, "version_no"), 1),
                Rows.str(row, "conclusion", ""),
                Rows.toDateTime(Rows.object(row, "signed_at")),
                Rows.str(row, "result_digest", ""),
                planRevisions(Rows.str(row, "snapshot_json")));
    }

    /** {@code scope.plans[].planId → revision} exactly as the report froze it (AC-4 report staleness). */
    public Map<Long, Integer> planRevisions(String snapshotJson) {
        Map<Long, Integer> revisions = new LinkedHashMap<>();
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return revisions;
        }
        try {
            Map<String, Object> root = json.readValue(snapshotJson,
                    new TypeReference<Map<String, Object>>() {
                    });
            Object scope = root.get("scope");
            if (!(scope instanceof Map<?, ?> scopeMap)) {
                return revisions;
            }
            Object plans = scopeMap.get("plans");
            if (!(plans instanceof List<?> list)) {
                return revisions;
            }
            for (Object element : list) {
                if (element instanceof Map<?, ?> plan) {
                    Long planId = Rows.toLong(plan.get("planId"));
                    Integer revision = Rows.toInt(plan.get("revision"), -1);
                    if (planId != null) {
                        revisions.put(planId, revision);
                    }
                }
            }
        } catch (Exception ex) {
            // A report whose snapshot cannot be parsed must not pass as fresh: empty map makes
            // QG-02 fall back to the digest check instead of throwing a 500 on admission.
            return revisions;
        }
        return revisions;
    }

    private List<Long> openP0P1(long releaseId) {
        List<Map<String, Object>> rows = jdbcRead.list("""
                SELECT d.id
                FROM defect d
                WHERE /*scope:d*/ AND d.release_id = :releaseId
                  AND d.severity IN ('P0', 'P1') AND d.status <> 'CLOSED'
                ORDER BY d.id ASC
                """, Map.of("releaseId", releaseId));
        List<Long> ids = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            ids.add(Rows.toLong(Rows.object(row, "id"), 0L));
        }
        return ids;
    }

    private List<WaiverFacts> activeWaivers(long releaseId) {
        List<Map<String, Object>> rows = jdbcRead.list("""
                SELECT w.id, w.rule_code, w.approver_id, w.expiry_at
                FROM quality_gate_waiver w
                JOIN `release` rel ON rel.id = w.release_id
                WHERE /*scope:rel*/ AND w.release_id = :releaseId
                  AND w.status = 'ACTIVE' AND w.expiry_at > :now
                ORDER BY w.id ASC
                """, Map.of("releaseId", releaseId, "now", LocalDateTime.now()));
        List<WaiverFacts> waivers = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            waivers.add(new WaiverFacts(
                    Rows.toLong(Rows.object(row, "id"), 0L),
                    Rows.str(row, "rule_code", ""),
                    Rows.toLong(Rows.object(row, "approver_id")),
                    Rows.toDateTime(Rows.object(row, "expiry_at"))));
        }
        return waivers;
    }

    private long requirementCount(long releaseId) {
        return jdbcRead.count("""
                SELECT COUNT(*) AS total
                FROM release_requirement rr
                JOIN requirement rq ON rq.id = rr.requirement_id
                WHERE /*scope:rq*/ AND rr.release_id = :releaseId
                """, Map.of("releaseId", releaseId));
    }

    private long coveredRequirementCount(long releaseId) {
        return jdbcRead.count("""
                SELECT COUNT(*) AS total
                FROM release_requirement rr
                JOIN requirement rq ON rq.id = rr.requirement_id
                WHERE /*scope:rq*/ AND rr.release_id = :releaseId
                  AND EXISTS (SELECT 1
                              FROM test_plan_case pc
                              JOIN test_plan p ON p.id = pc.plan_id
                              WHERE pc.requirement_id = rq.id
                                AND p.release_id = :releaseId
                                AND p.approved_at IS NOT NULL
                                AND p.status <> 'REJECTED')
                """, Map.of("releaseId", releaseId));
    }

    // ------------------------------------------------------------------ rules

    /** Evaluate every configured rule against the facts (AC-5.3 single-rule precision). */
    public Evaluation evaluate(Facts facts) {
        List<RuleOutcome> outcomes = new ArrayList<>(facts.config().size());
        for (GateRuleset.RuleConfig config : facts.config()) {
            outcomes.add(switch (config.code()) {
                case GateRuleset.QG_01 -> qg01(facts, config);
                case GateRuleset.QG_02 -> qg02(facts, config);
                case GateRuleset.QG_03 -> qg03(facts, config);
                case GateRuleset.QG_04 -> qg04(facts, config);
                case GateRuleset.QG_05 -> qg05(facts, config);
                default -> skip(config.code(), "未知规则，已跳过");
            });
        }
        List<GateSnapshot.GateRuleResult> results = new ArrayList<>(outcomes.size());
        for (RuleOutcome outcome : outcomes) {
            results.add(outcome.result());
        }
        return new Evaluation(result(results), outcomes, facts);
    }

    public List<GateSnapshot.GateRuleResult> rules(Facts facts) {
        return evaluate(facts).ruleResults();
    }

    /** FAIL dominates; otherwise any WAIVED makes the pass conditional; otherwise PASS. */
    public String result(List<GateSnapshot.GateRuleResult> rules) {
        boolean waived = false;
        for (GateSnapshot.GateRuleResult rule : rules) {
            if ("FAIL".equals(rule.status())) {
                return GateSnapshot.FAIL;
            }
            if ("WAIVED".equals(rule.status())) {
                waived = true;
            }
        }
        return waived ? GateSnapshot.PASS_WITH_WAIVER : GateSnapshot.PASS;
    }

    private RuleOutcome qg01(Facts facts, GateRuleset.RuleConfig config) {
        int requiredPercent = ruleset.intOf(config.thresholdJson(), "coveragePercent", 100);
        long total = facts.totalRequirements();
        long covered = facts.coveredRequirements();
        int percent = total == 0 ? 100 : (int) Math.floor(covered * 100.0 / total);
        boolean coverageOk = percent >= requiredPercent;
        boolean reportOk = facts.report() != null;
        boolean pass = coverageOk && reportOk;

        String actual = "需求覆盖 " + percent + "%（" + covered + "/" + total + "）· "
                + (reportOk ? "报告 v" + facts.report().versionNo() + " 已会签" : "无已会签报告");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("totalRequirements", total);
        evidence.put("coveredRequirements", covered);
        evidence.put("coveragePercent", percent);
        evidence.put("signedReport", reportOk);
        return outcome(config.code(), pass, facts, actual,
                "覆盖 ≥ " + requiredPercent + "% · 已会签 RELEASE 报告", evidence);
    }

    private RuleOutcome qg02(Facts facts, GateRuleset.RuleConfig config) {
        int maxAgeDays = ruleset.intOf(config.thresholdJson(), "maxAgeDays", facts.validityDays());
        List<String> acceptable = ruleset.stringsOf(config.thresholdJson(), "conclusions",
                List.of("PASS", "CONDITIONAL"));
        String threshold = "签署 ≤ " + maxAgeDays + " 天内 · 结论 "
                + String.join("/", acceptable) + "（CONDITIONAL 需已批豁免）· 与执行数据一致";

        ReportFacts report = facts.report();
        Map<String, Object> evidence = new LinkedHashMap<>();
        if (report == null) {
            evidence.put("code", "REPORT_NOT_SIGNED");
            return outcome(config.code(), false, facts,
                    "REPORT_NOT_SIGNED（无已会签 RELEASE 报告）", threshold, evidence);
        }
        evidence.put("reportId", report.reportId());
        evidence.put("reportVersionId", report.versionId());
        evidence.put("versionNo", report.versionNo());
        evidence.put("conclusion", report.conclusion());
        evidence.put("signedAt", iso(report.signedAt()));
        evidence.put("resultDigest", report.resultDigest());

        int validityDays = Math.min(maxAgeDays, facts.validityDays());
        LocalDate expiryDate = report.signedAt().toLocalDate().plusDays(validityDays);
        boolean expired = LocalDate.now().isAfter(expiryDate);
        evidence.put("expiresOn", expiryDate.toString());
        if (expired) {
            evidence.put("code", "REPORT_EXPIRED");
            return outcome(config.code(), false, facts,
                    "REPORT_EXPIRED（签署于 " + report.signedAt().toLocalDate() + "，有效期至 "
                            + expiryDate + "）", threshold, evidence);
        }

        boolean conclusionAcceptable = false;
        for (String candidate : acceptable) {
            if (candidate.equalsIgnoreCase(report.conclusion())) {
                conclusionAcceptable = true;
                break;
            }
        }
        if (!conclusionAcceptable) {
            evidence.put("code", "REPORT_CONCLUSION_REJECTED");
            return outcome(config.code(), false, facts,
                    "REPORT_CONCLUSION_REJECTED（结论 " + report.conclusion() + "）", threshold, evidence);
        }

        boolean conditional = "CONDITIONAL".equalsIgnoreCase(report.conclusion());
        if (conditional) {
            // A CONDITIONAL conclusion never passes on its own: with an approved QG-02 waiver the
            // shared waiver layer below turns this FAIL into WAIVED, which is what makes the gate
            // PASS_WITH_WAIVER rather than PASS (AC-5.4). Reporting it as PASS would hide the fact
            // that a human took the risk.
            evidence.put("conditional", true);
            evidence.put("code", "REPORT_CONDITIONAL_UNAPPROVED");
            return outcome(config.code(), false, facts,
                    "REPORT_CONDITIONAL_UNAPPROVED（条件通过结论需 QG-02 已批豁免）", threshold, evidence);
        }

        if (!facts.digest().equalsIgnoreCase(report.resultDigest())) {
            evidence.put("code", "REPORT_STALE");
            evidence.put("currentDigest", facts.digest());
            return outcome(config.code(), false, facts,
                    "REPORT_STALE（执行数据已变化：报告 digest 与当前不一致）", threshold, evidence);
        }

        for (Map.Entry<Long, Integer> entry : report.planRevisions().entrySet()) {
            Integer current = facts.aggregate().planRevisions().get(entry.getKey());
            if (current == null || !current.equals(entry.getValue())) {
                evidence.put("code", "REPORT_PLAN_REVISION_STALE");
                evidence.put("planId", entry.getKey());
                evidence.put("reportRevision", entry.getValue());
                evidence.put("currentRevision", current);
                return outcome(config.code(), false, facts,
                        "REPORT_PLAN_REVISION_STALE（计划 #" + entry.getKey() + " 已从 r"
                                + entry.getValue() + " 变更为 r" + (current == null ? "?" : current) + "）",
                        threshold, evidence);
            }
        }

        return outcome(config.code(), true, facts,
                "报告 v" + report.versionNo() + " · " + report.conclusion() + " · 有效期至 "
                        + expiryDate + " · digest 一致", threshold, evidence);
    }

    private RuleOutcome qg03(Facts facts, GateRuleset.RuleConfig config) {
        boolean strict = "STRICT".equalsIgnoreCase(facts.gateMode());
        int configured = ruleset.intOf(config.thresholdJson(), "minPassRate", 95);
        boolean strictRequiresAll = ruleset.boolOf(config.thresholdJson(), "strictRequiresAll", true);
        int thresholdPercent = strict && strictRequiresAll ? 100 : configured;

        QualityAggregate aggregate = facts.aggregate();
        long planned = aggregate.totalPlanned();
        long passed = aggregate.passed();
        boolean pass = planned > 0 && passed * 100 >= (long) thresholdPercent * planned;

        String actual = planned == 0
                ? "无用例（0/0）"
                : displayPercent(passed, planned) + "（" + passed + "/" + planned + " 通过）";
        String threshold = strict && strictRequiresAll
                ? "≥ 100%（STRICT 版本需全部通过）"
                : "≥ " + thresholdPercent + "%";
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("planned", planned);
        evidence.put("executed", aggregate.executed());
        evidence.put("passed", passed);
        evidence.put("failed", aggregate.failed());
        evidence.put("blocked", aggregate.blocked());
        evidence.put("thresholdPercent", thresholdPercent);
        evidence.put("gateMode", facts.gateMode());
        return outcome(config.code(), pass, facts, actual, threshold, evidence);
    }

    private RuleOutcome qg04(Facts facts, GateRuleset.RuleConfig config) {
        int allowed = ruleset.intOf(config.thresholdJson(), "maxOpenP0P1", 0);
        List<Long> open = facts.openP0P1();
        boolean pass = open.size() <= allowed;
        String actual = open.isEmpty() ? "0 个未关闭 P0/P1"
                : open.size() + " 个未关闭 P0/P1（" + joinIds(open) + "）";
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("openP0P1", open);
        evidence.put("allowed", allowed);
        return outcome(config.code(), pass, facts, actual, "≤ " + allowed + " 个", evidence);
    }

    private RuleOutcome qg05(Facts facts, GateRuleset.RuleConfig config) {
        List<String> sources = ruleset.stringsOf(config.thresholdJson(), "sources",
                List.of("SONAR", "ARTIFACT", "BUILD"));
        if (!config.enabled()) {
            return skip(config.code(), "未启用（本轮无外部质量数据源接入）");
        }
        // No external evidence store exists in this round: an enabled rule cannot pretend to pass,
        // and failing admission on an unimplemented integration would block every release.
        return skip(config.code(), "外部数据源（" + String.join("/", sources) + "）采集尚未接入，本轮跳过判定");
    }

    // ------------------------------------------------------------------ snapshot document

    /**
     * The immutable evidence document written to {@code quality_gate_snapshot.snapshot_json}.
     *
     * <p>Insertion order is fixed by {@link LinkedHashMap} so the SHA-256 of this document is
     * reproducible: two evaluations with the same evidence hash equal, which is what makes the
     * (deployOrderId, hash) snapshot reuse in {@link QualityGateService} sound.</p>
     */
    public Map<String, Object> document(Evaluation evaluation, long deployOrderId, String computedAt) {
        Facts facts = evaluation.facts();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("releaseId", facts.releaseId());
        doc.put("releaseName", facts.releaseName());
        doc.put("deployOrderId", deployOrderId);
        doc.put("gateMode", facts.gateMode());
        doc.put("result", evaluation.result());
        doc.put("computedAt", computedAt);
        doc.put("digest", facts.digest());

        Map<String, Object> requirements = new LinkedHashMap<>();
        requirements.put("total", facts.totalRequirements());
        requirements.put("covered", facts.coveredRequirements());
        requirements.put("coveragePercent", facts.totalRequirements() == 0 ? 100
                : (int) Math.floor(facts.coveredRequirements() * 100.0 / facts.totalRequirements()));
        doc.put("requirements", requirements);

        QualityAggregate aggregate = facts.aggregate();
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("planned", aggregate.totalPlanned());
        execution.put("executed", aggregate.executed());
        execution.put("passed", aggregate.passed());
        execution.put("failed", aggregate.failed());
        execution.put("blocked", aggregate.blocked());
        execution.put("notRun", aggregate.notRun());
        execution.put("passRate", aggregate.passRate());
        doc.put("execution", execution);

        doc.put("report", facts.report() == null ? null : reportDoc(facts.report()));
        doc.put("openP0P1", new ArrayList<>(facts.openP0P1()));

        List<Map<String, Object>> waivers = new ArrayList<>(facts.waivers().size());
        for (WaiverFacts waiver : facts.waivers()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", waiver.id());
            item.put("ruleCode", waiver.ruleCode());
            item.put("approverId", waiver.approverId());
            item.put("expiryAt", iso(waiver.expiryAt()));
            waivers.add(item);
        }
        doc.put("waivers", waivers);

        List<Map<String, Object>> rules = new ArrayList<>(evaluation.outcomes().size());
        for (RuleOutcome outcome : evaluation.outcomes()) {
            GateSnapshot.GateRuleResult rule = outcome.result();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", rule.code());
            item.put("name", ruleset.name(rule.code()));
            item.put("status", rule.status());
            item.put("required", rule.required());
            item.put("actual", rule.actual());
            item.put("threshold", rule.threshold());
            item.put("fixHint", rule.fixHint());
            item.put("evidence", outcome.evidence());
            rules.add(item);
        }
        doc.put("rules", rules);
        return doc;
    }

    private Map<String, Object> reportDoc(ReportFacts report) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("reportId", report.reportId());
        doc.put("versionId", report.versionId());
        doc.put("versionNo", report.versionNo());
        doc.put("conclusion", report.conclusion());
        doc.put("signedAt", iso(report.signedAt()));
        doc.put("resultDigest", report.resultDigest());
        return doc;
    }

    // ------------------------------------------------------------------ helpers

    private WaiverFacts waiverFor(Facts facts, String code) {
        for (WaiverFacts waiver : facts.waivers()) {
            if (code.equals(waiver.ruleCode())) {
                return waiver;
            }
        }
        return null;
    }

    private boolean waived(Facts facts, String code) {
        for (WaiverFacts waiver : facts.waivers()) {
            if (code.equals(waiver.ruleCode())) {
                return true;
            }
        }
        return false;
    }

    private RuleOutcome outcome(String code,
                                boolean pass,
                                Facts facts,
                                String actual,
                                String threshold,
                                Map<String, Object> evidence) {
        boolean waived = !pass && ruleset.waivable(code) && waived(facts, code);
        String status = pass ? "PASS" : waived ? "WAIVED" : "FAIL";
        evidence.put("waived", waived);
        String text = actual;
        if (waived) {
            WaiverFacts waiver = waiverFor(facts, code);
            if (waiver != null) {
                evidence.put("waiverId", waiver.id());
                text = actual + " · 豁免 #" + waiver.id() + " 至 " + iso(waiver.expiryAt());
            }
        }
        boolean required = !GateRuleset.QG_05.equals(code);
        GateSnapshot.GateRuleResult rule = new GateSnapshot.GateRuleResult(
                code, status, required, text, threshold, ruleset.fixHint(code));
        return new RuleOutcome(rule, evidence);
    }

    private RuleOutcome skip(String code, String actual) {
        GateSnapshot.GateRuleResult rule = new GateSnapshot.GateRuleResult(
                code, "SKIPPED", false, actual, "—", ruleset.fixHint(code));
        return new RuleOutcome(rule, Map.of("skipped", true));
    }

    /** Percent with one decimal, unrounded beyond display: 18/19 renders 94.7%, never 95.0%. */
    private static String displayPercent(long numerator, long denominator) {
        if (denominator <= 0) {
            return "—";
        }
        BigDecimal percent = BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100L))
                .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP);
        return percent.toPlainString() + "%";
    }

    private static String joinIds(List<Long> ids) {
        StringBuilder text = new StringBuilder();
        for (Long id : ids) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append('#').append(id);
        }
        return text.toString();
    }

    /** ISO-8601 instant string (the only datetime form deliver's Jackson can serialize). */
    public static String iso(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        return value.atZone(ZoneId.systemDefault()).toInstant().toString();
    }

    public static Instant instant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }
}
