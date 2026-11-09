package com.example.deliver.quality.gate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.deliver.quality.aggregate.QualityDigest;
import com.example.deliver.support.Rows;
import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.PageResponse;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.quality.GateSnapshot;
import com.example.platform.contract.quality.QualityGatePort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gate configuration, snapshot freezing, replay and the {@link QualityGatePort} the {@code req}
 * service calls (AC-5, AC-7).
 *
 * <p>The rule algorithm is {@link GateEvaluator}; this class owns what surrounds it:</p>
 * <ul>
 *   <li><b>Frozen snapshots.</b> A snapshot row is inserted and never updated. Its id is what
 *       {@code deploy_order.quality_gate_snapshot_id} points at, so admission can show the exact
 *       evidence a release was allowed on, months later (AC-7.1/7.2).</li>
 *   <li><b>Idempotent freezing.</b> Re-submitting the same deploy order with the same evidence
 *       hashes equal, and the existing snapshot is returned instead of a second row
 *       (AC-5.6). The hash is the SHA-256 of the snapshot document, produced by
 *       {@link QualityDigest#sha256(String)} — the one place in deliver allowed to hash.</li>
 *   <li><b>Preview without writing.</b> {@code deployOrderId=0} (the page's "evaluate now" button)
 *       returns the same view with {@code snapshotId=0}; it must not pollute the immutable
 *       history with an evaluation nobody admitted on.</li>
 * </ul>
 */
@Service
public class QualityGateService implements QualityGatePort {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ScopedJdbc jdbcRead;
    private final NamedParameterJdbcTemplate jdbc;
    private final GateEvaluator evaluator;
    private final GateRuleset ruleset;
    private final WaiverService waivers;
    private final AuditService audit;
    private final ObjectMapper json;

    public QualityGateService(ScopedJdbc jdbcRead,
                              NamedParameterJdbcTemplate jdbc,
                              GateEvaluator evaluator,
                              GateRuleset ruleset,
                              WaiverService waivers,
                              AuditService audit,
                              ObjectMapper qualityObjectMapper) {
        this.jdbcRead = jdbcRead;
        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.ruleset = ruleset;
        this.waivers = waivers;
        this.audit = audit;
        this.json = qualityObjectMapper;
    }

    // ------------------------------------------------------------------ port

    /** Freeze an evaluation for a deploy order (req's admission step 6). */
    @Override
    @Transactional
    public GateSnapshot evaluate(long releaseId, long deployOrderId, List<Long> requirementIds) {
        return run(releaseId, deployOrderId, deployOrderId > 0).snapshot();
    }

    /** Read one snapshot verbatim; unknown id is a 404, never a recomputation. */
    @Override
    @Transactional(readOnly = true)
    public GateSnapshot snapshot(long snapshotId) {
        return toSnapshot(loadSnapshot(snapshotId));
    }

    /**
     * The internal/port call: evaluate and freeze, returning the view shape. Same code path as
     * {@link #evaluate(long, long, List)}, which is what keeps the frozen JSON and the HTTP
     * answer byte-identical.
     */
    @Transactional
    public QualityGateViews.Snapshot freeze(long releaseId, long deployOrderId, List<Long> requirementIds) {
        if (deployOrderId <= 0) {
            throw new com.example.platform.common.error.ApiException(400, "DEPLOY_ORDER_REQUIRED",
                    "固化快照必须携带 deployOrderId");
        }
        return run(releaseId, deployOrderId, true).view();
    }

    /** The frozen snapshot in view shape — timestamps as ISO text, no recomputation (AC-7). */
    @Transactional(readOnly = true)
    public QualityGateViews.Snapshot viewOf(long snapshotId) {
        return viewFromRow(loadSnapshot(snapshotId));
    }

    /**
     * The SPA's evaluate button: same evaluation, view shape, <b>never</b> freezes a snapshot.
     * Only the internal/port call freezes one (AC-7.4 — the browser cannot mint an immutable
     * record, otherwise a page refresh would rewrite the gate history).
     */
    @Transactional
    public QualityGateViews.Snapshot evaluateView(long releaseId, Long deployOrderId, List<Long> requirementIds) {
        long orderId = deployOrderId == null ? 0L : deployOrderId;
        return run(releaseId, orderId, false).view();
    }

    // ------------------------------------------------------------------ configuration

    /** Configure the five rules for a release; QG-04 cannot be disabled (AC-6.2). */
    @Transactional
    public QualityGateViews.GateDetail configure(long releaseId, QualityGateViews.ConfigCmd cmd) {
        GateEvaluator.Facts facts = evaluator.facts(releaseId);
        Map<String, GateRuleset.RuleConfig> effective = new LinkedHashMap<>();
        for (GateRuleset.RuleConfig config : facts.config()) {
            effective.put(config.code(), config);
        }
        if (cmd != null && cmd.rules() != null) {
            for (QualityGateViews.RuleConfigCmd requested : cmd.rules()) {
                GateRuleset.RuleDef def = ruleset.find(requested.code());
                if (def == null) {
                    throw new ApiException(400, "GATE_RULE_UNKNOWN", "未知质量规则：" + requested.code());
                }
                if (GateRuleset.QG_04.equals(def.code()) && Boolean.FALSE.equals(requested.enabled())) {
                    throw new ApiException(400, "RULE_NOT_WAIVABLE",
                            "QG-04 是硬红线，既不可豁免也不可停用");
                }
                GateRuleset.RuleConfig current = effective.get(def.code());
                boolean enabled = requested.enabled() == null ? current.enabled() : requested.enabled();
                String thresholdJson = current.thresholdJson();
                if (requested.threshold() != null) {
                    if (!GateRuleset.QG_03.equals(def.code())) {
                        throw new ApiException(400, "THRESHOLD_INVALID",
                                def.code() + " 不接受数值阈值");
                    }
                    int percent = requested.threshold();
                    if (percent < 1 || percent > 100) {
                        throw new ApiException(400, "THRESHOLD_INVALID", "QG-03 阈值必须在 1–100 之间");
                    }
                    thresholdJson = "{\"minPassRate\":" + percent + ",\"strictRequiresAll\":true}";
                }
                effective.put(def.code(), new GateRuleset.RuleConfig(def.code(), enabled, thresholdJson));
            }
        }

        long gateId = waivers.ensureGate(releaseId);
        long tenantId = TenantContext.require().tenantId();
        for (GateRuleset.RuleDef def : ruleset.rules()) {
            GateRuleset.RuleConfig config = effective.get(def.code());
            jdbc.update("""
                    INSERT INTO quality_gate_rule (tenant_id, gate_id, rule_code, enabled, threshold_json, status)
                    VALUES (:tenantId, :gateId, :ruleCode, :enabled, :thresholdJson, 'SKIPPED')
                    ON DUPLICATE KEY UPDATE enabled = :enabled, threshold_json = :thresholdJson
                    """, new LinkedHashMap<>(Map.of(
                    "tenantId", tenantId,
                    "gateId", gateId,
                    "ruleCode", def.code(),
                    "enabled", config.enabled() ? 1 : 0,
                    "thresholdJson", config.thresholdJson())));
        }
        jdbc.update("""
                UPDATE quality_gate SET ruleset_version = ruleset_version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :gateId
                """, Map.of("now", LocalDateTime.now().withNano(0), "tenantId", tenantId, "gateId", gateId));
        keepAudit("QUALITY_GATE_CONFIGURE", gateId,
                Map.of("releaseId", releaseId, "rules", ruleset.codes()));
        return detail(releaseId);
    }

    // ------------------------------------------------------------------ views

    /** Detail: configured rules merged with a live evaluation, the recent snapshots and all waivers. */
    @Transactional
    public QualityGateViews.GateDetail detail(long releaseId) {
        waivers.expireLapsed(releaseId);
        GateEvaluator.Facts facts = evaluator.facts(releaseId);
        GateEvaluator.Evaluation evaluation = evaluator.evaluate(facts);
        Map<String, GateSnapshot.GateRuleResult> live = new LinkedHashMap<>();
        for (GateSnapshot.GateRuleResult rule : evaluation.ruleResults()) {
            live.put(rule.code(), rule);
        }
        Map<String, Object> gate = gateRow(releaseId);
        String gateStatus = gate == null ? "NOT_EVALUATED" : Rows.str(gate, "status", "NOT_EVALUATED");
        String evaluatedAt = gate == null ? null
                : GateEvaluator.iso(Rows.toDateTime(Rows.object(gate, "evaluated_at")));
        Integer rulesetVersion = gate == null ? 1 : Rows.toInt(Rows.object(gate, "ruleset_version"), 1);

        List<QualityGateViews.GateRule> rules = new ArrayList<>();
        for (GateRuleset.RuleConfig config : facts.config()) {
            GateSnapshot.GateRuleResult result = live.get(config.code());
            GateRuleset.RuleDef def = ruleset.find(config.code());
            boolean waivable = def != null && def.waivable();
            Integer threshold = GateRuleset.QG_03.equals(config.code())
                    ? ruleset.intOf(config.thresholdJson(), "minPassRate", 95) : null;
            rules.add(new QualityGateViews.GateRule(
                    config.code(),
                    ruleset.name(config.code()),
                    result == null ? "SKIPPED" : result.status(),
                    config.enabled(),
                    waivable,
                    !GateRuleset.QG_05.equals(config.code()) && config.enabled(),
                    threshold,
                    ruleset.thresholdText(config.code(), config.thresholdJson()),
                    result == null ? "—" : result.actual(),
                    result == null ? ruleset.fixHint(config.code()) : result.fixHint(),
                    evaluatedAt));
        }
        return new QualityGateViews.GateDetail(releaseId, facts.releaseName(), gateStatus,
                rulesetVersion, evaluatedAt, rules, recentSnapshots(releaseId),
                waivers.listByRelease(releaseId));
    }

    /** Release-level list: one row per release, latest snapshot as the summary. */
    @Transactional(readOnly = true)
    public PageResponse<QualityGateViews.GateRow> page(Long releaseId, String status, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        StringBuilder sql = new StringBuilder("""
                SELECT rel.id AS release_id, rel.name AS release_name,
                       g.id AS gate_id, g.status AS gate_status, g.evaluated_at, g.report_id AS gate_report_id,
                       s.id AS snapshot_id, s.hash AS snapshot_hash, s.report_version_id,
                       s.snapshot_json, v.version_no AS report_version_no,
                       (SELECT COUNT(*) FROM quality_gate_waiver w
                        WHERE w.gate_id = g.id AND w.status = 'ACTIVE' AND w.expiry_at > NOW(3))
                           AS active_waiver_count
                FROM `release` rel
                LEFT JOIN quality_gate g ON g.release_id = rel.id AND g.tenant_id = rel.tenant_id
                LEFT JOIN quality_gate_snapshot s
                       ON s.id = (SELECT MAX(s2.id) FROM quality_gate_snapshot s2 WHERE s2.gate_id = g.id)
                LEFT JOIN test_report_version v ON v.id = s.report_version_id
                WHERE /*scope:rel*/
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        if (releaseId != null) {
            sql.append(" AND rel.id = :releaseId");
            params.put("releaseId", releaseId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND COALESCE(g.status, 'NOT_EVALUATED') = :status");
            params.put("status", status.trim().toUpperCase());
        }
        sql.append(" ORDER BY rel.id DESC");
        PageResponse<Map<String, Object>> raw = jdbcRead.page(sql.toString(), params, safePage, safeSize);
        List<QualityGateViews.GateRow> items = new ArrayList<>(raw.items().size());
        for (Map<String, Object> row : raw.items()) {
            items.add(mapRow(row));
        }
        return new PageResponse<>(items, raw.total(), raw.page(), raw.size());
    }

    /** Replay: the frozen view plus which rules would render differently today. Never writes. */
    @Transactional(readOnly = true)
    public QualityGateViews.Replay replay(long snapshotId) {
        Map<String, Object> row = loadSnapshot(snapshotId);
        QualityGateViews.Snapshot frozen = viewFromRow(row);
        long releaseId = Rows.toLong(Rows.object(row, "release_id"), 0L);
        Map<String, GateSnapshot.GateRuleResult> today = new LinkedHashMap<>();
        for (GateSnapshot.GateRuleResult rule : evaluator.evaluate(evaluator.facts(releaseId)).ruleResults()) {
            today.put(rule.code(), rule);
        }
        List<GateSnapshot.GateRuleResult> changed = new ArrayList<>();
        for (GateSnapshot.GateRuleResult old : frozen.rules()) {
            GateSnapshot.GateRuleResult current = today.get(old.code());
            if (current == null || !current.status().equals(old.status())) {
                changed.add(current == null ? old : current);
            }
        }
        return new QualityGateViews.Replay(frozen.snapshotId(), frozen.releaseId(), frozen.deployOrderId(),
                frozen.reportVersionId(), frozen.result(), frozen.hash(), frozen.computedAt(),
                frozen.rules(), frozen.openP0P1(), frozen.waivers(), frozen.report(),
                new QualityGateViews.ReplayDiff(changed));
    }

    // ------------------------------------------------------------------ evaluation core

    private record Outcome(GateSnapshot snapshot, QualityGateViews.Snapshot view) {
    }

    private Outcome run(long releaseId, long deployOrderId, boolean persist) {
        waivers.expireLapsed(releaseId);
        GateEvaluator.Facts facts = evaluator.facts(releaseId);
        GateEvaluator.Evaluation evaluation = evaluator.evaluate(facts);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        String computedAt = GateEvaluator.iso(now);
        Map<String, Object> document = evaluator.document(evaluation, deployOrderId, computedAt);
        String documentJson = write(document);
        String hash = QualityDigest.sha256(documentJson);
        Long reportVersionId = facts.report() == null ? null : facts.report().versionId();

        if (!persist) {
            GateSnapshot preview = new GateSnapshot(0L, releaseId, deployOrderId, reportVersionId,
                    evaluation.result(), hash, GateEvaluator.instant(now),
                    evaluation.ruleResults(), facts.openP0P1());
            // A preview is not persisted and has no gate row of its own; the ids stay null.
            return new Outcome(preview, viewOf(preview, document, null, null));
        }

        Map<String, Object> existing = findReusable(deployOrderId, hash);
        if (existing != null) {
            GateSnapshot snapshot = toSnapshot(existing);
            return new Outcome(snapshot, viewFromRow(existing));
        }

        long gateId = waivers.ensureGate(releaseId);
        long tenantId = TenantContext.require().tenantId();
        Long actor = actorId();
        jdbc.update("""
                INSERT INTO quality_gate_snapshot
                    (tenant_id, gate_id, release_id, deploy_order_id, report_version_id, result,
                     snapshot_json, hash, computed_at, created_by)
                VALUES (:tenantId, :gateId, :releaseId, :deployOrderId, :reportVersionId, :result,
                        :snapshotJson, :hash, :computedAt, :createdBy)
                """, new LinkedHashMap<>(Map.of(
                "tenantId", tenantId,
                "gateId", gateId,
                "releaseId", releaseId,
                "deployOrderId", deployOrderId,
                "reportVersionId", reportVersionId == null ? 0L : reportVersionId,
                "result", evaluation.result(),
                "snapshotJson", documentJson,
                "hash", hash,
                "computedAt", now,
                "createdBy", actor == null ? 0L : actor)));
        long snapshotId = lastInsertId();

        Long reportId = facts.report() == null ? null : facts.report().reportId();
        jdbc.update("""
                UPDATE quality_gate
                SET status = :status, report_id = :reportId, evaluated_at = :now, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :gateId
                """, new LinkedHashMap<>(Map.of(
                "status", evaluation.result(),
                "tenantId", tenantId,
                "gateId", gateId,
                "now", now,
                "reportId", reportId == null ? 0L : reportId)));
        for (GateSnapshot.GateRuleResult rule : evaluation.ruleResults()) {
            jdbc.update("""
                    UPDATE quality_gate_rule SET status = :status, evidence_json = :evidence,
                                                 evaluated_at = :now
                    WHERE tenant_id = :tenantId AND gate_id = :gateId AND rule_code = :code
                    """, Map.of("status", rule.status(), "evidence", write(evidenceOf(evaluation, rule)),
                    "now", now, "tenantId", tenantId, "gateId", gateId, "code", rule.code()));
        }
        keepAudit("QUALITY_GATE_EVALUATE", gateId, Map.of(
                "releaseId", releaseId, "deployOrderId", deployOrderId,
                "snapshotId", snapshotId, "result", evaluation.result()));

        GateSnapshot snapshot = new GateSnapshot(snapshotId, releaseId, deployOrderId, reportVersionId,
                evaluation.result(), hash, GateEvaluator.instant(now),
                evaluation.ruleResults(), facts.openP0P1());
        return new Outcome(snapshot, viewOf(snapshot, document, gateId, reportId));
    }

    private Map<String, Object> evidenceOf(GateEvaluator.Evaluation evaluation, GateSnapshot.GateRuleResult rule) {
        for (GateEvaluator.RuleOutcome outcome : evaluation.outcomes()) {
            if (outcome.result().code().equals(rule.code())) {
                return outcome.evidence();
            }
        }
        return Map.of();
    }

    private Map<String, Object> findReusable(long deployOrderId, String hash) {
        if (deployOrderId <= 0) {
            return null;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, release_id, deploy_order_id, report_version_id, gate_id, result, hash, computed_at, snapshot_json
                FROM quality_gate_snapshot
                WHERE tenant_id = :tenantId AND deploy_order_id = :deployOrderId AND hash = :hash
                ORDER BY id ASC
                LIMIT 1
                """, Map.of("tenantId", TenantContext.require().tenantId(),
                "deployOrderId", deployOrderId, "hash", hash));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> loadSnapshot(long snapshotId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, release_id, deploy_order_id, report_version_id, gate_id, result, hash, computed_at, snapshot_json
                FROM quality_gate_snapshot
                WHERE tenant_id = :tenantId AND id = :id
                """, Map.of("tenantId", TenantContext.require().tenantId(), "id", snapshotId));
        if (rows.isEmpty()) {
            throw new ApiException(404, "GATE_SNAPSHOT_NOT_FOUND", "质量门快照不存在或无权访问");
        }
        return rows.get(0);
    }

    private List<QualityGateViews.Snapshot> recentSnapshots(long releaseId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, release_id, deploy_order_id, report_version_id, gate_id, result, hash, computed_at, snapshot_json
                FROM quality_gate_snapshot
                WHERE tenant_id = :tenantId AND release_id = :releaseId
                ORDER BY id DESC
                LIMIT 20
                """, Map.of("tenantId", TenantContext.require().tenantId(), "releaseId", releaseId));
        List<QualityGateViews.Snapshot> snapshots = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            snapshots.add(viewFromRow(row));
        }
        return snapshots;
    }

    private Map<String, Object> gateRow(long releaseId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, status, evaluated_at, ruleset_version, report_id
                FROM quality_gate WHERE tenant_id = :tenantId AND release_id = :releaseId
                """, Map.of("tenantId", TenantContext.require().tenantId(), "releaseId", releaseId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private QualityGateViews.GateRow mapRow(Map<String, Object> row) {
        String status = Rows.str(row, "gate_status", "NOT_EVALUATED");
        String snapshotJson = Rows.str(row, "snapshot_json");
        List<String> failedCodes = new ArrayList<>();
        Integer passed = null;
        Integer total = null;
        if (snapshotJson != null && !snapshotJson.isBlank()) {
            try {
                Map<String, Object> frozen = read(snapshotJson);
                List<Map<String, Object>> rules = maps(frozen.get("rules"));
                int passedCount = 0;
                int judged = 0;
                for (Map<String, Object> rule : rules) {
                    String ruleStatus = String.valueOf(rule.get("status"));
                    if ("SKIPPED".equals(ruleStatus)) {
                        continue;
                    }
                    judged++;
                    if ("FAIL".equals(ruleStatus)) {
                        failedCodes.add(String.valueOf(rule.get("code")));
                    } else {
                        passedCount++;
                    }
                }
                passed = passedCount;
                total = judged;
            } catch (Exception ex) {
                // A snapshot whose JSON cannot be read must not blank the whole list.
                passed = null;
                total = null;
            }
        }
        return new QualityGateViews.GateRow(
                Rows.toLong(Rows.object(row, "release_id"), 0L),
                Rows.str(row, "release_name"),
                Rows.toLong(Rows.object(row, "gate_id")),
                status,
                GateEvaluator.iso(Rows.toDateTime(Rows.object(row, "evaluated_at"))),
                Rows.toInt(Rows.object(row, "active_waiver_count"), 0),
                Rows.toLong(Rows.object(row, "gate_report_id")),
                Rows.toLong(Rows.object(row, "report_version_id")),
                Rows.toInt(Rows.object(row, "report_version_no"), 0),
                Rows.toInt(Rows.object(row, "snapshot_id"), 0),
                Rows.str(row, "snapshot_hash"),
                failedCodes.isEmpty() ? null : failedCodes,
                passed,
                total);
    }

    // ------------------------------------------------------------------ mapping helpers

    private QualityGateViews.Snapshot viewOf(GateSnapshot snapshot, Map<String, Object> document,
                                             Long gateId, Long reportId) {
        return new QualityGateViews.Snapshot(snapshot.snapshotId(), snapshot.releaseId(),
                snapshot.deployOrderId(), snapshot.reportVersionId(), gateId, reportId,
                snapshot.result(), snapshot.hash(),
                GateEvaluator.iso(toLocal(snapshot.computedAt())), snapshot.rules(), snapshot.openP0P1(),
                maps(document.get("waivers")), map(document.get("report")));
    }

    private QualityGateViews.Snapshot viewFromRow(Map<String, Object> row) {
        String snapshotJson = Rows.str(row, "snapshot_json", "{}");
        Map<String, Object> document;
        try {
            document = read(snapshotJson);
        } catch (Exception ex) {
            throw new ApiException(500, "SNAPSHOT_CORRUPTED", "质量门快照内容无法解析");
        }
        List<GateSnapshot.GateRuleResult> rules = new ArrayList<>();
        for (Map<String, Object> rule : maps(document.get("rules"))) {
            rules.add(new GateSnapshot.GateRuleResult(
                    String.valueOf(rule.get("code")),
                    String.valueOf(rule.get("status")),
                    Boolean.TRUE.equals(rule.get("required")),
                    rule.get("actual") == null ? "" : String.valueOf(rule.get("actual")),
                    rule.get("threshold") == null ? "" : String.valueOf(rule.get("threshold")),
                    rule.get("fixHint") == null ? "" : String.valueOf(rule.get("fixHint"))));
        }
        List<Long> openP0P1 = new ArrayList<>();
        if (document.get("openP0P1") instanceof List<?> list) {
            for (Object element : list) {
                Long id = Rows.toLong(element);
                if (id != null) {
                    openP0P1.add(id);
                }
            }
        }
        Map<String, Object> reportDoc = map(document.get("report"));
        return new QualityGateViews.Snapshot(
                Rows.toLong(Rows.object(row, "id"), 0L),
                Rows.toLong(Rows.object(row, "release_id"), 0L),
                Rows.toLong(Rows.object(row, "deploy_order_id"), 0L),
                Rows.toLong(Rows.object(row, "report_version_id")),
                Rows.lng(row, "gate_id"),
                reportDoc == null ? null : Rows.toLong(reportDoc.get("reportId")),
                Rows.str(row, "result", String.valueOf(document.get("result"))),
                Rows.str(row, "hash"),
                GateEvaluator.iso(Rows.toDateTime(Rows.object(row, "computed_at"))),
                rules, openP0P1, maps(document.get("waivers")), map(document.get("report")));
    }

    private GateSnapshot toSnapshot(Map<String, Object> row) {
        QualityGateViews.Snapshot view = viewFromRow(row);
        return new GateSnapshot(view.snapshotId(), view.releaseId(), view.deployOrderId(),
                view.reportVersionId(), view.result(), view.hash(),
                toInstant(Rows.toDateTime(Rows.object(row, "computed_at"))),
                view.rules(), view.openP0P1());
    }

    /**
     * Audit is best-effort on the internal (no JWT) path: platform-common's AuditService unboxes a
     * null principal id there (AuditService.java:69), and losing evidence must never turn a gate
     * evaluation into a 500. The admission caller keeps its own evidence (req admission_result_json).
     */
    private void keepAudit(String action, long gateId, Map<String, Object> detail) {
        if (TenantContext.principal() == null) {
            return;
        }
        audit.record(action, "QUALITY_GATE", gateId, detail);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw new ApiException(500, "SNAPSHOT_WRITE_FAILED", "质量门快照序列化失败");
        }
    }

    private Map<String, Object> read(String text) {
        try {
            return json.readValue(text, MAP_TYPE);
        } catch (Exception ex) {
            throw new ApiException(500, "SNAPSHOT_CORRUPTED", "质量门快照内容无法解析");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }


    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private long lastInsertId() {
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Map.of(), Long.class);
        return id == null ? 0L : id;
    }

    private static Long actorId() {
        TenantContext.Principal principal = TenantContext.principal();
        return principal == null ? null : principal.userId();
    }

    private static LocalDateTime toLocal(java.time.Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
    }

    private static java.time.Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(java.time.ZoneId.systemDefault()).toInstant();
    }
}
