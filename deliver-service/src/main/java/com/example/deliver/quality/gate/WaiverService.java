package com.example.deliver.quality.gate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.deliver.support.Rows;
import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.PageResponse;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.StateTables;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rule-level waivers: apply → approve/reject → revoke/expire (AC-6).
 *
 * <p>Three constraints are enforced here, on the server, because hiding a button proves nothing:</p>
 * <ul>
 *   <li><b>QG-04 never.</b> The open-P0/P1 red line cannot be waived (AC-6.2) or disabled; the
 *       check runs before any insert so a rejected request leaves no row behind.</li>
 *   <li><b>1–30 days.</b> A waiver is a dated exception, not a permanent mute; the window is
 *       validated here and {@code expiry_at} is the only thing the evaluator trusts.</li>
 *   <li><b>Approver ≠ any applicant below PMO.</b> Approval requires PMO / management / platform
 *       admin; a QA applicant self-approving is refused with {@code SCOPE_DENIED} (AC-6.3).</li>
 * </ul>
 *
 * <p>Expiry is lazy — {@link #expireLapsed(long)} flips ACTIVE rows whose window has passed to
 * EXPIRED whenever the gate is evaluated or the waiver list is read. There is no scheduled job,
 * and a snapshot that recorded a waiver keeps that record forever (AC-6.5/AC-7).</p>
 */
@Service
public class WaiverService {

    /** Roles that may approve a waiver. Mirrors AC-6.3 (PMO or administrator). */
    private static final Set<String> APPROVER_ROLES = Set.of("PMO", "MGMT", "PLATFORM_ADMIN");

    private static final String SELECT_ROW = """
            SELECT w.id, w.gate_id, w.release_id, w.rule_code, w.reason, w.impact_scope, w.expiry_at,
                   w.applicant_id, w.approver_id, w.approved_at, w.status, w.revoked_by, w.revoked_at,
                   w.revoke_reason, w.created_at,
                   rel.name AS release_name,
                   applicant.name AS applicant_name,
                   approver.name AS approver_name
            FROM quality_gate_waiver w
            JOIN `release` rel ON rel.id = w.release_id
            LEFT JOIN sys_user applicant ON applicant.id = w.applicant_id
            LEFT JOIN sys_user approver ON approver.id = w.approver_id
            WHERE /*scope:rel*/ AND w.id = :id
            """;

    private final ScopedJdbc jdbcRead;
    private final NamedParameterJdbcTemplate jdbc;
    private final GateRuleset ruleset;
    private final AuditService audit;

    public WaiverService(ScopedJdbc jdbcRead,
                         NamedParameterJdbcTemplate jdbc,
                         GateRuleset ruleset,
                         AuditService audit) {
        this.jdbcRead = jdbcRead;
        this.jdbc = jdbc;
        this.ruleset = ruleset;
        this.audit = audit;
    }

    /**
     * The release's gate row, created on first touch.
     *
     * <p>Lives here (not in {@link QualityGateService}) so a waiver can be filed before the first
     * evaluation without the two services depending on each other; {@code gate} has no
     * {@code gate}↔{@code waiver} cycle at the bean level. {@code INSERT … SELECT} keeps the
     * domain id coming from the release row instead of trusting the caller, and
     * {@code ON DUPLICATE KEY} makes concurrent first touches safe under {@code uk_gate_release}.</p>
     */
    @Transactional
    public long ensureGate(long releaseId) {
        long tenantId = TenantContext.require().tenantId();
        Long actor = actorId();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        jdbc.update("""
                INSERT INTO quality_gate
                    (tenant_id, release_id, status, ruleset_version, domain_id, created_by,
                     created_at, updated_at)
                SELECT :tenantId, rel.id, 'NOT_EVALUATED', 1, rel.domain_id, :createdBy, :now, :now
                FROM `release` rel
                WHERE rel.id = :releaseId AND rel.tenant_id = :tenantId
                ON DUPLICATE KEY UPDATE updated_at = quality_gate.updated_at
                """, new LinkedHashMap<>(Map.of(
                "tenantId", tenantId,
                "releaseId", releaseId,
                "createdBy", actor == null ? 0L : actor,
                "now", now)));
        Long id = jdbc.queryForObject("""
                SELECT id FROM quality_gate WHERE tenant_id = :tenantId AND release_id = :releaseId
                """, Map.of("tenantId", tenantId, "releaseId", releaseId), Long.class);
        if (id == null) {
            throw new ApiException(404, "RELEASE_NOT_FOUND", "版本不存在或无权访问");
        }
        return id;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Apply for one rule on one release; always starts PENDING (AC-6.1). */
    @Transactional
    public QualityGateViews.WaiverRow apply(QualityGateViews.WaiverApplyCmd cmd) {
        if (cmd == null) {
            throw new ApiException(400, "WAIVER_FIELDS_REQUIRED", "请求体不能为空");
        }
        if (cmd.ruleCodes() != null && !cmd.ruleCodes().isEmpty()) {
            throw new ApiException(400, "WAIVER_RULE_SINGLE_ONLY", "一次豁免只能针对一条规则，请拆分申请");
        }
        if (isBlank(cmd.ruleCode()) || cmd.releaseId() == null || isBlank(cmd.reason())
                || isBlank(cmd.impactScope()) || cmd.expiryDays() == null) {
            throw new ApiException(400, "WAIVER_FIELDS_REQUIRED",
                    "ruleCode、releaseId、reason、impactScope、expiryDays 均为必填");
        }
        GateRuleset.RuleDef def = ruleset.find(cmd.ruleCode());
        if (def == null) {
            throw new ApiException(400, "GATE_RULE_UNKNOWN", "未知质量规则：" + cmd.ruleCode());
        }
        if (!def.waivable()) {
            throw new ApiException(400, "RULE_NOT_WAIVABLE", def.code() + " 是硬红线，不可豁免");
        }
        int days = cmd.expiryDays();
        if (days < 1 || days > 30) {
            throw new ApiException(400, "WAIVER_EXPIRY_INVALID", "豁免有效期必须在 1–30 天之间");
        }

        long gateId = ensureGate(cmd.releaseId());
        long tenantId = TenantContext.require().tenantId();
        Long applicantId = actorId();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        jdbc.update("""
                INSERT INTO quality_gate_waiver
                    (tenant_id, gate_id, rule_code, release_id, reason, impact_scope, expiry_at,
                     applicant_id, status, created_at)
                VALUES (:tenantId, :gateId, :ruleCode, :releaseId, :reason, :impactScope, :expiryAt,
                        :applicantId, 'PENDING', :now)
                """, new LinkedHashMap<>(Map.of(
                "tenantId", tenantId,
                "gateId", gateId,
                "ruleCode", def.code(),
                "releaseId", cmd.releaseId(),
                "reason", cmd.reason().trim(),
                "impactScope", cmd.impactScope().trim(),
                "expiryAt", now.plusDays(days),
                "applicantId", applicantId == null ? 0L : applicantId,
                "now", now)));
        long id = lastInsertId();
        keepAudit("WAIVER_APPLY", id, Map.of(
                "releaseId", cmd.releaseId(), "ruleCode", def.code(), "expiryDays", days));
        return row(id);
    }

    /** PENDING → ACTIVE. Approval is a PMO/administrator act (AC-6.3). */
    @Transactional
    public QualityGateViews.WaiverRow approve(long id, String comment, boolean confirmSelfApproval) {
        requireApprover();
        Map<String, Object> current = locked(id);
        requireNotSelfApproval(current, confirmSelfApproval);
        requireTransition(current, "approve");
        long tenantId = TenantContext.require().tenantId();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        Long actor = actorId();
        jdbc.update("""
                UPDATE quality_gate_waiver
                SET status = 'ACTIVE', approver_id = :approverId, approved_at = :now
                WHERE tenant_id = :tenantId AND id = :id
                """, Map.of("approverId", actor == null ? 0L : actor, "now", now,
                "tenantId", tenantId, "id", id));
        keepAudit("WAIVER_APPROVE", id, Map.of("comment", safe(comment)));
        return row(id);
    }

    /** PENDING → REJECTED. The rejection note is kept in {@code revoke_reason} (no separate column). */
    @Transactional
    public QualityGateViews.WaiverRow reject(long id, String comment) {
        requireApprover();
        Map<String, Object> current = locked(id);
        requireNotSelfApproval(current);
        requireTransition(current, "reject");
        long tenantId = TenantContext.require().tenantId();
        jdbc.update("""
                UPDATE quality_gate_waiver
                SET status = 'REJECTED', approver_id = :approverId, approved_at = :now,
                    revoke_reason = :comment
                WHERE tenant_id = :tenantId AND id = :id
                """, Map.of("approverId", actorId() == null ? 0L : actorId(),
                "now", LocalDateTime.now().withNano(0),
                "comment", safe(comment), "tenantId", tenantId, "id", id));
        keepAudit("WAIVER_REJECT", id, Map.of("comment", safe(comment)));
        return row(id);
    }

    /** ACTIVE → REVOKED; takes effect on the next evaluation (AC-6.5). */
    @Transactional
    public QualityGateViews.WaiverRow revoke(long id, String reason) {
        if (isBlank(reason)) {
            throw new ApiException(400, "WAIVER_FIELDS_REQUIRED", "撤销豁免必须填写原因");
        }
        Map<String, Object> current = locked(id);
        requireTransition(current, "revoke");
        long tenantId = TenantContext.require().tenantId();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        jdbc.update("""
                UPDATE quality_gate_waiver
                SET status = 'REVOKED', revoked_by = :actorId, revoked_at = :now, revoke_reason = :reason
                WHERE tenant_id = :tenantId AND id = :id
                """, Map.of("actorId", actorId() == null ? 0L : actorId(), "now", now,
                "reason", reason.trim(), "tenantId", tenantId, "id", id));
        keepAudit("WAIVER_REVOKE", id, Map.of("reason", reason.trim()));
        return row(id);
    }

    /** Lazy expiry: ACTIVE rows past their window become EXPIRED. Returns how many flipped. */
    @Transactional
    public int expireLapsed(long releaseId) {
        long tenantId = TenantContext.require().tenantId();
        return jdbc.update("""
                UPDATE quality_gate_waiver
                SET status = 'EXPIRED'
                WHERE tenant_id = :tenantId AND release_id = :releaseId
                  AND status = 'ACTIVE' AND expiry_at <= :now
                """, Map.of("tenantId", tenantId, "releaseId", releaseId,
                "now", LocalDateTime.now().withNano(0)));
    }

    /** Every waiver of a release, newest first — the gate detail page lists all states. */
    @Transactional
    public List<QualityGateViews.WaiverRow> listByRelease(long releaseId) {
        expireLapsed(releaseId);
        List<Map<String, Object>> rows = jdbcRead.list("""
                SELECT w.id, w.gate_id, w.release_id, w.rule_code, w.reason, w.impact_scope, w.expiry_at,
                       w.applicant_id, w.approver_id, w.approved_at, w.status, w.revoked_by, w.revoked_at,
                       w.revoke_reason, w.created_at,
                       rel.name AS release_name,
                       applicant.name AS applicant_name,
                       approver.name AS approver_name
                FROM quality_gate_waiver w
                JOIN `release` rel ON rel.id = w.release_id
                LEFT JOIN sys_user applicant ON applicant.id = w.applicant_id
                LEFT JOIN sys_user approver ON approver.id = w.approver_id
                WHERE /*scope:rel*/ AND w.release_id = :releaseId
                ORDER BY w.id DESC
                """, Map.of("releaseId", releaseId));
        List<QualityGateViews.WaiverRow> waivers = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            waivers.add(map(row));
        }
        return waivers;
    }

    // ------------------------------------------------------------------ queries

    @Transactional
    public PageResponse<QualityGateViews.WaiverRow> page(String status, Long releaseId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        StringBuilder sql = new StringBuilder("""
                SELECT w.id, w.gate_id, w.release_id, w.rule_code, w.reason, w.impact_scope, w.expiry_at,
                       w.applicant_id, w.approver_id, w.approved_at, w.status, w.revoked_by, w.revoked_at,
                       w.revoke_reason, w.created_at,
                       rel.name AS release_name,
                       applicant.name AS applicant_name,
                       approver.name AS approver_name
                FROM quality_gate_waiver w
                JOIN `release` rel ON rel.id = w.release_id
                LEFT JOIN sys_user applicant ON applicant.id = w.applicant_id
                LEFT JOIN sys_user approver ON approver.id = w.approver_id
                WHERE /*scope:rel*/
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        if (releaseId != null) {
            sql.append(" AND w.release_id = :releaseId");
            params.put("releaseId", releaseId);
            expireLapsed(releaseId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND w.status = :status");
            params.put("status", status.trim().toUpperCase());
        }
        sql.append(" ORDER BY w.id DESC");
        PageResponse<Map<String, Object>> raw = jdbcRead.page(sql.toString(), params, safePage, safeSize);
        List<QualityGateViews.WaiverRow> items = new ArrayList<>(raw.items().size());
        for (Map<String, Object> row : raw.items()) {
            items.add(map(row));
        }
        return new PageResponse<>(items, raw.total(), raw.page(), raw.size());
    }

    // ------------------------------------------------------------------ internals

    private Map<String, Object> locked(long id) {
        Map<String, Object> row = jdbcRead.one(SELECT_ROW, Map.of("id", id));
        if (row == null) {
            throw new ApiException(404, "WAIVER_NOT_FOUND", "豁免记录不存在或无权访问");
        }
        return row;
    }

    private void requireTransition(Map<String, Object> row, String action) {
        String status = Rows.str(row, "status", "");
        String target = StateTables.target(StateTables.WAIVER, status, action);
        if (target == null) {
            throw new ApiException(409, "WAIVER_STATE_INVALID",
                    "豁免当前状态为 " + status + "，不能执行 " + action);
        }
    }

    private void requireApprover() {
        Set<String> roles = Set.copyOf(TenantContext.roles());
        for (String role : roles) {
            if (APPROVER_ROLES.contains(role)) {
                return;
            }
        }
        throw new ApiException(403, "SCOPE_DENIED", "审批豁免需要 PMO、管理层或平台管理员角色",
                Map.of("requiredRoles", List.copyOf(APPROVER_ROLES), "actorRoles", List.copyOf(roles)));
    }

    /**
     * AC-6.3: nobody rubber-stamps their own waiver. PMO / platform administrators are the single
     * exception (they are the approval role and there may be nobody above them), everything else is
     * a scope violation — a hidden button in the UI is not a control.
     */
    private void requireNotSelfApproval(Map<String, Object> row) {
        requireNotSelfApproval(row, false);
    }

    private void requireNotSelfApproval(Map<String, Object> row, boolean confirmSelfApproval) {
        Long applicant = Rows.toLong(Rows.object(row, "applicant_id"));
        Long actor = actorId();
        if (applicant == null || actor == null || !applicant.equals(actor)) {
            return;
        }
        Set<String> roles = Set.copyOf(TenantContext.roles());
        if (roles.contains("PMO")) {
            return;
        }
        if (confirmSelfApproval && roles.contains("PLATFORM_ADMIN")) {
            return;
        }
        throw new ApiException(403, "SCOPE_DENIED", "申请人不得自行审批自己的豁免（PMO 除外；管理员需二次确认）",
                Map.of("applicantId", applicant, "actorId", actor, "actorRoles", List.copyOf(roles),
                        "confirmRequired", roles.contains("PLATFORM_ADMIN")));
    }

    /**
     * Audit is best-effort on the internal (no JWT) path: platform-common's AuditService unboxes a
     * null principal id there (AuditService.java:69), and losing evidence must never turn a gate
     * evaluation into a 500. The admission caller keeps its own evidence (req admission_result_json).
     */
    private void keepAudit(String action, long id, Map<String, Object> detail) {
        if (TenantContext.principal() == null) {
            return;
        }
        audit.record(action, "QUALITY_GATE_WAIVER", id, detail);
    }

    private QualityGateViews.WaiverRow row(long id) {
        return map(locked(id));
    }

    private QualityGateViews.WaiverRow map(Map<String, Object> row) {
        String ruleCode = Rows.str(row, "rule_code", "");
        return new QualityGateViews.WaiverRow(
                Rows.toLong(Rows.object(row, "id"), 0L),
                Rows.toLong(Rows.object(row, "gate_id")),
                Rows.toLong(Rows.object(row, "release_id"), 0L),
                Rows.str(row, "release_name"),
                ruleCode,
                ruleset.name(ruleCode),
                Rows.str(row, "reason"),
                Rows.str(row, "impact_scope"),
                GateEvaluator.iso(Rows.toDateTime(Rows.object(row, "expiry_at"))),
                Rows.toLong(Rows.object(row, "applicant_id")),
                Rows.str(row, "applicant_name"),
                Rows.toLong(Rows.object(row, "approver_id")),
                Rows.str(row, "approver_name"),
                GateEvaluator.iso(Rows.toDateTime(Rows.object(row, "approved_at"))),
                Rows.str(row, "status"),
                Rows.toLong(Rows.object(row, "revoked_by")),
                GateEvaluator.iso(Rows.toDateTime(Rows.object(row, "revoked_at"))),
                Rows.str(row, "revoke_reason"),
                GateEvaluator.iso(Rows.toDateTime(Rows.object(row, "created_at"))));
    }

    private long lastInsertId() {
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Map.of(), Long.class);
        return id == null ? 0L : id;
    }

    private static Long actorId() {
        TenantContext.Principal principal = TenantContext.principal();
        return principal == null ? null : principal.userId();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
