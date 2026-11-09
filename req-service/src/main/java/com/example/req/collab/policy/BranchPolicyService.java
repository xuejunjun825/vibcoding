package com.example.req.collab.policy;

import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.req.requirement.Rows;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 分支策略的 CRUD 与核验历史读取（AC-10 / TECHNICAL §2.5）。
 *
 * <p><b>规则表不存「是否合规」</b>：{@code checkResult} 由
 * {@code branch_policy_check} 最新一行派生（{@link #LATEST_CHECK} 的相关子查询）。
 * 没有记录 = UNVERIFIED。这样「断开 GitLab 后不残留上次绿色」（AC-11）不是靠记得去
 * 改标志位，而是结构上就不可能：核验写不进来，派生值就只能是未核验。</p>
 *
 * <p>读取全部走 {@link ScopedJdbc}：{@code branch_policy} 在 ScopeSql 里登记了
 * {@code domain_id} 隔离列，域外规则连行都读不到。</p>
 */
@Service
public class BranchPolicyService {

    public static final String BIZ_TYPE = "BRANCH_POLICY";

    /** 最新一条核验记录的相关子查询（按 id 而不是 checked_at 排序：同毫秒也稳定）。 */
    private static final String LATEST_CHECK = """
            LEFT JOIN branch_policy_check ck ON ck.id = (
                SELECT c2.id FROM branch_policy_check c2
                 WHERE c2.tenant_id = p.tenant_id AND c2.policy_id = p.id
                 ORDER BY c2.id DESC
                 LIMIT 1)
            """;

    private static final String SELECT_COLUMNS = """
            p.id, p.domain_id, p.gitlab_project, p.branch_pattern, p.pattern_type,
            p.`protected`, p.merge_method, p.approvals_required, p.push_roles_json,
            p.merge_roles_json, p.enabled, p.version,
            ck.result AS check_result, ck.reason AS check_reason, ck.checked_at
            """;

    private static final String INSERT = """
            INSERT INTO branch_policy
                (tenant_id, domain_id, gitlab_project, branch_pattern, pattern_type,
                 `protected`, merge_method, approvals_required, push_roles_json, merge_roles_json,
                 enabled, version, created_at, updated_at, created_by, updated_by)
            VALUES
                (:tenantId, :domainId, :project, :pattern, :patternType,
                 :protectedBranch, :mergeMethod, :approvals, :pushRoles, :mergeRoles,
                 :enabled, 0, NOW(3), NOW(3), :actor, :actor)
            """;

    private static final String UPDATE = """
            UPDATE branch_policy SET
                domain_id = :domainId,
                gitlab_project = :project,
                branch_pattern = :pattern,
                pattern_type = :patternType,
                `protected` = :protectedBranch,
                merge_method = :mergeMethod,
                approvals_required = :approvals,
                push_roles_json = :pushRoles,
                merge_roles_json = :mergeRoles,
                enabled = :enabled,
                version = version + 1,
                updated_at = NOW(3),
                updated_by = :actor
             WHERE id = :id AND tenant_id = :tenantId
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ScopedJdbc scoped;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public BranchPolicyService(NamedParameterJdbcTemplate jdbc, ScopedJdbc scoped, AuditService auditService,
                              ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.scoped = scoped;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /** 规则列表（读本地表，零外呼）。三个筛选条件都可缺省，SQL 按需拼接。 */
    @Transactional(readOnly = true)
    public List<BranchPolicyViews.PolicyView> list(Long domainId, String project, Boolean enabled) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder sql = new StringBuilder("SELECT " + SELECT_COLUMNS
                + "  FROM branch_policy p\n" + LATEST_CHECK + " WHERE /*scope:p*/");
        if (domainId != null) {
            sql.append(" AND p.domain_id = :domainId");
            params.put("domainId", domainId);
        }
        if (project != null && !project.isBlank()) {
            sql.append(" AND p.gitlab_project = :project");
            params.put("project", project.trim());
        }
        if (enabled != null) {
            sql.append(" AND p.enabled = :enabled");
            params.put("enabled", enabled ? 1 : 0);
        }
        sql.append(" ORDER BY p.id DESC");
        List<Map<String, Object>> rows = scoped.list(sql.toString(), params);
        List<BranchPolicyViews.PolicyView> views = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            views.add(toView(row));
        }
        return views;
    }

    /** 兼容蓝图签名 {@code list(Long, String)}。 */
    @Transactional(readOnly = true)
    public List<BranchPolicyViews.PolicyView> list(Long domainId, String project) {
        return list(domainId, project, null);
    }

    /**
     * 新建或更新规则。先归一再校验（缺省值先填好，避免「空格导致唯一键冲突」），
     * 写成功才落审计；审计里带 before/after，AC-10 要求能回答「谁在什么时候改了哪条」。
     */
    @Transactional
    public BranchPolicyViews.PolicyView save(BranchPolicyViews.PolicyCmd raw) {
        BranchPolicyViews.PolicyCmd cmd = BranchPolicyValidator.normalize(raw);
        BranchPolicyValidator.validate(cmd);
        DataScope scope = TenantContext.require();
        long actor = TenantContext.require().userId();
        if (cmd.id() == null) {
            requireDomain(cmd.domainId(), scope);
            Map<String, Object> params = params(cmd, scope.tenantId(), actor);
            try {
                jdbc.update(INSERT, params);
            } catch (DuplicateKeyException e) {
                throw duplicate(cmd);
            }
            Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Map.of(), Long.class);
            BranchPolicyViews.PolicyView created = require(id == null ? 0L : id);
            auditService.record("CREATE", BIZ_TYPE, created.id(), detail(null, created));
            return created;
        }
        BranchPolicyViews.PolicyView before = require(cmd.id());
        Long domainId = cmd.domainId() == null ? before.domainId() : cmd.domainId();
        requireDomain(domainId, scope);
        Map<String, Object> params = params(cmd, scope.tenantId(), actor, domainId);
        params.put("id", cmd.id());
        String sql = UPDATE;
        if (cmd.version() != null) {
            sql = sql + " AND version = :version";
            params.put("version", cmd.version());
        }
        int updated;
        try {
            updated = jdbc.update(sql, params);
        } catch (DuplicateKeyException e) {
            throw duplicate(cmd);
        }
        if (updated == 0) {
            if (cmd.version() != null) {
                throw new ApiException(409, BranchPolicyViews.CODE_VERSION_CONFLICT,
                        "规则已被他人修改，请刷新后重试",
                        Map.of("policyId", cmd.id(), "expectedVersion", cmd.version()));
            }
            throw notFound(cmd.id());
        }
        BranchPolicyViews.PolicyView after = require(cmd.id());
        auditService.record("UPDATE", BIZ_TYPE, after.id(), detail(before, after));
        return after;
    }

    /** 软删（{@code enabled=0}）：核验历史保留，AC-11 要求「只增不改」。 */
    @Transactional
    public void delete(long id) {
        BranchPolicyViews.PolicyView before = require(id);
        int updated = jdbc.update("""
                UPDATE branch_policy
                   SET enabled = 0, version = version + 1, updated_at = NOW(3), updated_by = :actor
                 WHERE id = :id AND tenant_id = :tenantId
                """,
                Map.of("actor", TenantContext.require().userId(), "id", id,
                        "tenantId", TenantContext.require().tenantId()));
        if (updated == 0) {
            throw notFound(id);
        }
        Map<String, Object> detail = new HashMap<>();
        detail.put("before", before.enabled());
        detail.put("after", false);
        detail.put("gitlabProject", before.gitlabProject());
        detail.put("branchPattern", before.branchPattern());
        auditService.record("DISABLE", BIZ_TYPE, id, detail);
    }

    /** 按 id 取一条（走作用域，域外 404 而不是 403——不泄露「存在但你没权限」）。 */
    @Transactional(readOnly = true)
    public BranchPolicyViews.PolicyView require(long id) {
        Map<String, Object> row = scoped.one("SELECT " + SELECT_COLUMNS
                + "  FROM branch_policy p\n" + LATEST_CHECK + " WHERE /*scope:p*/ AND p.id = :id",
                Map.of("id", id));
        if (row == null) {
            throw notFound(id);
        }
        return toView(row);
    }

    /** 核验历史（新→旧）。只读，不发外呼。 */
    @Transactional(readOnly = true)
    public List<BranchPolicyViews.CheckRow> checks(long policyId, int limit) {
        require(policyId);
        int size = limit <= 0 ? 20 : Math.min(limit, 100);
        List<Map<String, Object>> rows = scoped.list("""
                SELECT c.id, c.policy_id, c.result, c.reason, c.diff_json, c.checked_at, c.checked_by
                  FROM branch_policy_check c
                  JOIN branch_policy p ON p.id = c.policy_id
                 WHERE /*scope:p*/ AND c.policy_id = :policyId
                 ORDER BY c.id DESC
                 LIMIT :limit
                """, Map.of("policyId", policyId, "limit", size));
        List<BranchPolicyViews.CheckRow> checks = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            checks.add(toCheckRow(row));
        }
        return checks;
    }

    /**
     * 追加一条核验记录。唯一写入口在 {@link GitLabPolicyChecker}：它决定 result/reason，
     * 这里只负责序列化与落库（{@code diff_json} 存 items + note）。
     */
    @Transactional
    public void recordCheck(long policyId, String result, String reason,
                            List<BranchPolicyViews.CheckItem> items, String note) {
        long tenantId = TenantContext.require().tenantId();
        Map<String, Object> diff = new HashMap<>();
        diff.put("items", items == null ? List.of() : items);
        if (note != null) {
            diff.put("note", note);
        }
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("checkedBy", TenantContext.username());
        evidence.put("result", result);
        if (reason != null) {
            evidence.put("reason", reason);
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("policyId", policyId)
                .addValue("result", result)
                .addValue("reason", BranchPolicyViews.knownReason(reason) ? reason : null, Types.VARCHAR)
                .addValue("diff", json(diff))
                .addValue("evidence", json(evidence))
                .addValue("checkedBy", TenantContext.require().userId());
        jdbc.update("""
                INSERT INTO branch_policy_check
                    (tenant_id, policy_id, result, reason, diff_json, evidence_json, checked_at, checked_by)
                VALUES (:tenantId, :policyId, :result, :reason, :diff, :evidence, NOW(3), :checkedBy)
                """, params);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> params(BranchPolicyViews.PolicyCmd cmd, long tenantId, long actor) {
        return params(cmd, tenantId, actor, cmd.domainId());
    }

    private Map<String, Object> params(BranchPolicyViews.PolicyCmd cmd, long tenantId, long actor,
                                       Long domainId) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("domainId", domainId);
        params.put("project", cmd.gitlabProject());
        params.put("pattern", cmd.branchPattern());
        params.put("patternType", cmd.patternType());
        params.put("protectedBranch", Boolean.TRUE.equals(cmd.protectedBranch()) ? 1 : 0);
        params.put("mergeMethod", cmd.mergeMethod());
        params.put("approvals", cmd.approvalsRequired() == null ? 0 : cmd.approvalsRequired());
        params.put("pushRoles", json(cmd.pushRoles()));
        params.put("mergeRoles", json(cmd.mergeRoles()));
        params.put("enabled", Boolean.FALSE.equals(cmd.enabled()) ? 0 : 1);
        params.put("actor", actor);
        return params;
    }

    private void requireDomain(Long domainId, DataScope scope) {
        if (domainId == null) {
            throw new ApiException(400, BranchPolicyViews.CODE_INVALID, "必须指定所属业务域",
                    Map.of("field", "domainId"));
        }
        if (!scope.canSeeDomain(domainId)) {
            throw new ApiException(403, "FORBIDDEN", "无权在该业务域下维护分支策略",
                    Map.of("domainId", domainId));
        }
    }

    private BranchPolicyViews.PolicyView toView(Map<String, Object> row) {
        String result = Rows.str(row, "check_result");
        String reason = Rows.str(row, "check_reason");
        LocalDateTime checkedAt = Rows.toDateTime(Rows.object(row, "checked_at"));
        return new BranchPolicyViews.PolicyView(
                Rows.toLong(Rows.object(row, "id"), 0L),
                Rows.toLong(Rows.object(row, "domain_id")),
                Rows.str(row, "gitlab_project"),
                Rows.str(row, "branch_pattern"),
                defaultType(Rows.str(row, "pattern_type")),
                Rows.toLong(Rows.object(row, "protected"), 0L) != 0L,
                Rows.str(row, "merge_method"),
                (int) Rows.toLong(Rows.object(row, "approvals_required"), 0L),
                roles(Rows.str(row, "push_roles_json")),
                roles(Rows.str(row, "merge_roles_json")),
                Rows.toLong(Rows.object(row, "enabled"), 0L) != 0L,
                (int) Rows.toLong(Rows.object(row, "version"), 0L),
                result == null ? BranchPolicyViews.RESULT_UNVERIFIED : result,
                reason,
                checkedAt);
    }

    private BranchPolicyViews.CheckRow toCheckRow(Map<String, Object> row) {
        List<BranchPolicyViews.CheckItem> items = List.of();
        String note = null;
        String diffJson = Rows.str(row, "diff_json");
        if (diffJson != null && !diffJson.isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(diffJson);
                items = items(node.get("items"));
                JsonNode noteNode = node.get("note");
                note = noteNode == null || noteNode.isNull() ? null : noteNode.asText();
            } catch (Exception ignored) {
                // 历史行格式坏掉不该让整页 500：items 空着，至少 result/时间还在
            }
        }
        return new BranchPolicyViews.CheckRow(
                Rows.toLong(Rows.object(row, "id"), 0L),
                Rows.toLong(Rows.object(row, "policy_id"), 0L),
                Rows.str(row, "result"),
                Rows.str(row, "reason"),
                items,
                note,
                Rows.toDateTime(Rows.object(row, "checked_at")),
                Rows.str(row, "checked_by"));
    }

    private static List<BranchPolicyViews.CheckItem> items(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<BranchPolicyViews.CheckItem> items = new ArrayList<>(array.size());
        for (JsonNode node : array) {
            items.add(new BranchPolicyViews.CheckItem(
                    text(node, "item"),
                    text(node, "expected"),
                    text(node, "actual"),
                    node.path("ok").asBoolean(false),
                    text(node, "source")));
        }
        return items;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private List<String> roles(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(jsonText);
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<String> roles = new ArrayList<>(node.size());
            for (JsonNode item : node) {
                if (item != null && !item.isNull()) {
                    roles.add(item.asText());
                }
            }
            return roles;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            throw new IllegalStateException("分支策略 JSON 序列化失败", e);
        }
    }

    private static String defaultType(String value) {
        if (value == null || value.isBlank()) {
            return BranchPolicyViews.PATTERN_REGEX;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private Map<String, Object> detail(BranchPolicyViews.PolicyView before, BranchPolicyViews.PolicyView after) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("gitlabProject", after.gitlabProject());
        detail.put("branchPattern", after.branchPattern());
        detail.put("patternType", after.patternType());
        detail.put("protected", after.protectedBranch());
        detail.put("mergeMethod", after.mergeMethod());
        detail.put("approvalsRequired", after.approvalsRequired());
        detail.put("pushRoles", after.pushRoles());
        detail.put("mergeRoles", after.mergeRoles());
        detail.put("enabled", after.enabled());
        detail.put("version", after.version());
        if (before != null) {
            Map<String, Object> previous = new HashMap<>();
            previous.put("mergeMethod", before.mergeMethod());
            previous.put("approvalsRequired", before.approvalsRequired());
            previous.put("protected", before.protectedBranch());
            previous.put("pushRoles", before.pushRoles());
            previous.put("mergeRoles", before.mergeRoles());
            previous.put("enabled", before.enabled());
            detail.put("before", previous);
        }
        return detail;
    }

    private static ApiException duplicate(BranchPolicyViews.PolicyCmd cmd) {
        return new ApiException(409, BranchPolicyViews.CODE_DUPLICATE,
                "同项目下已存在相同分支模式的规则",
                Map.of("gitlabProject", cmd.gitlabProject(), "branchPattern", cmd.branchPattern()));
    }

    private static ApiException notFound(long id) {
        return new ApiException(404, BranchPolicyViews.CODE_NOT_FOUND,
                "分支策略不存在或无权访问", Map.of("policyId", id));
    }
}
