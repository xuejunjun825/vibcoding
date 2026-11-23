package com.example.req.automation.rule;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.PageResponse;
import com.example.platform.common.tenant.TenantContext;
import com.example.req.automation.api.AutomationDtos.CodeLabel;
import com.example.req.automation.api.AutomationDtos.MetaView;
import com.example.req.automation.api.AutomationDtos.RuleCmd;
import com.example.req.automation.api.AutomationDtos.RuleView;
import com.example.req.automation.api.AutomationDtos.RunBrief;
import com.example.req.automation.api.AutomationDtos.WebhookOption;
import com.example.req.automation.spec.AutomationErrors;
import com.example.req.automation.spec.AutomationSpecs;
import com.example.req.automation.spec.AutomationSpecs.ActionSpec;
import com.example.req.automation.spec.AutomationSpecs.ConditionSpec;
import com.example.req.automation.spec.AutomationTriggerType;
import jakarta.persistence.criteria.Predicate;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 规则的增 / 改 / 启停 / 查与 {@code /automation/meta}。
 *
 * <p>三条纪律：</p>
 * <ol>
 *   <li><b>校验只在入口做一次</b>：schema、作用域、收窄、体积、名称全部经
 *       {@link AutomationRuleValidation} 与 {@link AutomationSpecs}，controller 不重复。</li>
 *   <li><b>没有 DELETE</b>：停用即永久保留（历史执行日志要能指着一条规则），
 *       因此不存在「删了规则导致日志悬空」这种状态。</li>
 *   <li><b>乐观锁</b>：PUT 与 PATCH 都必须带 version，落后即 409 并回当前值；
 *       否则「停用」会覆盖别人刚改的条件——停用从来不是「用旧值覆盖全部」的意思。</li>
 * </ol>
 *
 * <p>审计：CREATE / UPDATE / ENABLE / DISABLE 各写一条 {@code AUTOMATION_RULE_*}，
 * detail 只放标量前后值与版本，不放条件全文与任何密钥（AC-2 的另一半）。</p>
 */
@Service
public class AutomationRuleService {

    private final AutomationRuleRepository repository;
    private final AutomationRuleValidation validation;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AutomationRuleService(AutomationRuleRepository repository,
                                 AutomationRuleValidation validation,
                                 AuditService audit,
                                 JdbcTemplate jdbc,
                                 ObjectMapper objectMapper) {
        this.repository = repository;
        this.validation = validation;
        this.audit = audit;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ 写

    @Transactional
    public RuleView create(RuleCmd cmd) {
        long tenantId = tenantId();
        long userId = userId();
        String name = validation.checkName(cmd.name());
        AutomationTriggerType trigger = validation.checkTrigger(cmd.triggerType());
        validation.checkScope(cmd.scopeType(), cmd.scopeId());
        String scopeLabel = requireScopeVisible(tenantId, cmd.scopeType(), cmd.scopeId());
        ConditionSpec spec = ConditionSpec.parse(cmd.condition());
        List<ActionSpec> actions = ActionSpec.parseArray(cmd.actions());
        validation.checkSize(cmd.condition(), cmd.actions());
        validation.checkConditionScope(cmd.scopeType(), cmd.scopeId(), spec);
        requireWebhookConfigs(tenantId, actions);
        int priority = validation.checkPriority(cmd.priority());
        if (repository.existsByTenantIdAndName(tenantId, name)) {
            throw AutomationErrors.nameTaken(name);
        }

        LocalDateTime now = LocalDateTime.now();
        AutomationRuleEntity entity = new AutomationRuleEntity();
        entity.setTenantId(tenantId);
        entity.setName(name);
        entity.setEnabled(cmd.enabled() == null || cmd.enabled());
        entity.setTriggerType(trigger.code());
        entity.setConditionJson(writeJson(cmd.condition(), false));
        entity.setActionsJson(writeJson(cmd.actions(), true));
        entity.setScopeType(cmd.scopeType());
        entity.setScopeId(cmd.scopeId());
        entity.setPriority(priority);
        entity.setVersion(1);
        entity.setCreatedBy(userId);
        entity.setCreatedAt(now);
        entity.setUpdatedBy(userId);
        entity.setUpdatedAt(now);
        AutomationRuleEntity saved = save(entity, name);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", saved.getName());
        detail.put("triggerType", saved.getTriggerType());
        detail.put("scopeType", saved.getScopeType());
        detail.put("scopeId", saved.getScopeId());
        detail.put("enabled", Boolean.TRUE.equals(saved.getEnabled()));
        detail.put("priority", saved.getPriority());
        detail.put("version", saved.getVersion());
        detail.put("after", snapshot(saved));
        audit.record("AUTOMATION_RULE_CREATE", "automation_rule", saved.getId(), detail);

        return toView(tenantId, saved, scopeLabel, userName(tenantId, userId), null);
    }

    @Transactional
    public RuleView update(long id, RuleCmd cmd) {
        long tenantId = tenantId();
        long userId = userId();
        AutomationRuleEntity entity = load(tenantId, id);
        int currentVersion = versionOf(entity);
        if (cmd.version() == null) {
            throw AutomationErrors.schema("version", "更新规则必须带 version");
        }
        if (cmd.version() != currentVersion) {
            throw AutomationErrors.versionConflict(currentVersion);
        }

        String name = validation.checkName(cmd.name());
        AutomationTriggerType trigger = validation.checkTrigger(cmd.triggerType());
        validation.checkScope(cmd.scopeType(), cmd.scopeId());
        String scopeLabel = requireScopeVisible(tenantId, cmd.scopeType(), cmd.scopeId());
        ConditionSpec spec = ConditionSpec.parse(cmd.condition());
        List<ActionSpec> actions = ActionSpec.parseArray(cmd.actions());
        validation.checkSize(cmd.condition(), cmd.actions());
        validation.checkConditionScope(cmd.scopeType(), cmd.scopeId(), spec);
        requireWebhookConfigs(tenantId, actions);
        int priority = validation.checkPriority(cmd.priority());
        if (repository.existsByTenantIdAndNameAndIdNot(tenantId, name, id)) {
            throw AutomationErrors.nameTaken(name);
        }

        Map<String, Object> before = snapshot(entity);
        entity.setName(name);
        entity.setEnabled(cmd.enabled() == null ? Boolean.TRUE.equals(entity.getEnabled()) : cmd.enabled());
        entity.setTriggerType(trigger.code());
        entity.setConditionJson(writeJson(cmd.condition(), false));
        entity.setActionsJson(writeJson(cmd.actions(), true));
        entity.setScopeType(cmd.scopeType());
        entity.setScopeId(cmd.scopeId());
        entity.setPriority(priority);
        entity.setVersion(currentVersion + 1);
        entity.setUpdatedBy(userId);
        entity.setUpdatedAt(LocalDateTime.now());
        AutomationRuleEntity saved = save(entity, name);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", before);
        detail.put("after", snapshot(saved));
        detail.put("version", saved.getVersion());
        detail.put("changed", changedFields(before, snapshot(saved)));
        audit.record("AUTOMATION_RULE_UPDATE", "automation_rule", saved.getId(), detail);

        return toView(tenantId, saved, scopeLabel, userName(tenantId, userId), lastRun(tenantId, saved.getId()));
    }

    @Transactional
    public RuleView setEnabled(long id, boolean enabled, int version) {
        long tenantId = tenantId();
        long userId = userId();
        AutomationRuleEntity entity = load(tenantId, id);
        int currentVersion = versionOf(entity);
        if (version != currentVersion) {
            throw AutomationErrors.versionConflict(currentVersion);
        }
        if (Boolean.valueOf(enabled).equals(entity.getEnabled())) {
            // 幂等：重复点击开关不产生新版本，也不写噪声审计。
            return toView(tenantId, entity, scopeLabel(tenantId, entity),
                    userName(tenantId, entity.getCreatedBy()), lastRun(tenantId, entity.getId()));
        }
        Map<String, Object> before = snapshot(entity);
        entity.setEnabled(enabled);
        entity.setVersion(currentVersion + 1);
        entity.setUpdatedBy(userId);
        entity.setUpdatedAt(LocalDateTime.now());
        AutomationRuleEntity saved = save(entity, entity.getName());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", before);
        detail.put("after", snapshot(saved));
        detail.put("version", saved.getVersion());
        audit.record(enabled ? "AUTOMATION_RULE_ENABLE" : "AUTOMATION_RULE_DISABLE",
                "automation_rule", saved.getId(), detail);

        return toView(tenantId, saved, scopeLabel(tenantId, saved),
                userName(tenantId, saved.getCreatedBy()), lastRun(tenantId, saved.getId()));
    }

    // ------------------------------------------------------------------ 读

    @Transactional(readOnly = true)
    public PageResponse<RuleView> list(String keyword, String triggerType, Boolean enabled, int page, int size) {
        long tenantId = tenantId();
        int pageNo = Math.max(1, page);
        int pageSize = Math.min(200, Math.max(1, size));
        String trimmedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        String trimmedTrigger = triggerType == null || triggerType.isBlank() ? null : triggerType.trim();

        Specification<AutomationRuleEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            if (trimmedKeyword != null) {
                predicates.add(cb.like(root.get("name"), "%" + trimmedKeyword + "%"));
            }
            if (trimmedTrigger != null) {
                predicates.add(cb.equal(root.get("triggerType"), trimmedTrigger));
            }
            if (enabled != null) {
                predicates.add(cb.equal(root.get("enabled"), enabled));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<AutomationRuleEntity> rows = repository.findAll(spec,
                PageRequest.of(pageNo - 1, pageSize, Sort.by(Sort.Direction.ASC, "priority", "id")));
        return new PageResponse<>(toViews(tenantId, rows.getContent()), rows.getTotalElements(), pageNo, pageSize);
    }

    @Transactional(readOnly = true)
    public RuleView view(long id) {
        long tenantId = tenantId();
        AutomationRuleEntity entity = load(tenantId, id);
        return toViews(tenantId, List.of(entity)).get(0);
    }

    /** {@code GET /automation/meta}：枚举与候选全部来自服务端真值。 */
    @Transactional(readOnly = true)
    public MetaView meta() {
        long tenantId = tenantId();
        List<CodeLabel> triggers = AutomationRuleViews.triggerOptions();
        List<CodeLabel> actions = AutomationRuleViews.actionOptions();
        List<String> statuses = AutomationSpecs.statusCandidates();
        return new MetaView(triggers, actions, AutomationSpecs.CONDITION_KEYS,
                AutomationSpecs.ESTIMATE_OPS, AutomationSpecs.FIELDS, statuses,
                webhookOptions(tenantId));
    }

    // ------------------------------------------------------------------ 组装

    private List<RuleView> toViews(long tenantId, List<AutomationRuleEntity> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> domainIds = new LinkedHashSet<>();
        Set<Long> vendorIds = new LinkedHashSet<>();
        Set<Long> userIds = new LinkedHashSet<>();
        Set<Long> ruleIds = new LinkedHashSet<>();
        for (AutomationRuleEntity row : rows) {
            if (AutomationRuleValidation.SCOPE_DOMAIN.equals(row.getScopeType())) {
                domainIds.add(row.getScopeId());
            } else if (AutomationRuleValidation.SCOPE_VENDOR.equals(row.getScopeType())) {
                vendorIds.add(row.getScopeId());
            }
            userIds.add(row.getCreatedBy());
            ruleIds.add(row.getId());
        }
        Map<Long, String> domains = names("SELECT id, name FROM biz_domain WHERE tenant_id = ? AND id IN (%s)",
                tenantId, domainIds);
        Map<Long, String> vendors = names("SELECT id, name FROM sys_vendor WHERE tenant_id = ? AND id IN (%s)",
                tenantId, vendorIds);
        Map<Long, String> users = names("SELECT id, name FROM sys_user WHERE tenant_id = ? AND id IN (%s)",
                tenantId, userIds);
        Map<Long, RunBrief> runs = lastRuns(tenantId, ruleIds);

        List<RuleView> views = new ArrayList<>(rows.size());
        for (AutomationRuleEntity row : rows) {
            String label = AutomationRuleValidation.SCOPE_DOMAIN.equals(row.getScopeType())
                    ? domains.getOrDefault(row.getScopeId(), "")
                    : vendors.getOrDefault(row.getScopeId(), "");
            views.add(toView(tenantId, row, label,
                    users.getOrDefault(row.getCreatedBy(), ""), runs.get(row.getId())));
        }
        return List.copyOf(views);
    }

    private RuleView toView(long tenantId, AutomationRuleEntity row, String scopeLabel,
                            String createdByName, RunBrief lastRun) {
        return AutomationRuleViews.toView(row,
                AutomationRuleViews.parseJson(objectMapper, row.getConditionJson(), false),
                AutomationRuleViews.parseJson(objectMapper, row.getActionsJson(), true),
                scopeLabel, createdByName, lastRun);
    }

    /** 单个规则的作用域标签（详情 / 启停回显）。 */
    private String scopeLabel(long tenantId, AutomationRuleEntity row) {
        String table = AutomationRuleValidation.SCOPE_DOMAIN.equals(row.getScopeType()) ? "biz_domain" : "sys_vendor";
        List<String> values = jdbc.queryForList(
                "SELECT name FROM " + table + " WHERE tenant_id = ? AND id = ? LIMIT 1",
                String.class, tenantId, row.getScopeId());
        return values.isEmpty() ? "" : values.get(0);
    }

    private Map<Long, String> names(String sqlTemplate, long tenantId, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        Object[] args = new Object[ids.size() + 1];
        args[0] = tenantId;
        int index = 1;
        for (Long id : ids) {
            args[index++] = id;
        }
        Map<Long, String> result = new LinkedHashMap<>();
        jdbc.query(String.format(sqlTemplate, placeholders), rs -> {
            result.put(rs.getLong("id"), rs.getString("name"));
        }, args);
        return result;
    }

    /**
     * 最近一次执行：同租户同规则的 MAX(id)。
     *
     * <p>为什么用 MAX(id) 而不是 MAX(created_at)：id 自增，同毫秒并发时时间戳会相等，
     * 只有 id 严格单调（run 是 INSERT 出来的，不会被回填成更早的行）。</p>
     */
    private Map<Long, RunBrief> lastRuns(long tenantId, Collection<Long> ruleIds) {
        if (ruleIds == null || ruleIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", ruleIds.stream().map(id -> "?").toList());
        Object[] args = new Object[ruleIds.size() + 1];
        args[0] = tenantId;
        int index = 1;
        for (Long id : ruleIds) {
            args[index++] = id;
        }
        List<Long> runIds = jdbc.queryForList(String.format(
                "SELECT MAX(id) FROM automation_run WHERE tenant_id = ? AND rule_id IN (%s) GROUP BY rule_id",
                placeholders), Long.class, args);
        if (runIds.isEmpty()) {
            return Map.of();
        }
        String runPlaceholders = String.join(",", runIds.stream().map(id -> "?").toList());
        Map<Long, RunBrief> result = new LinkedHashMap<>();
        jdbc.query("SELECT rule_id, created_at, status, skip_reason FROM automation_run WHERE id IN ("
                + runPlaceholders + ")", rs -> {
            java.sql.Timestamp at = rs.getTimestamp("created_at");
            result.put(rs.getLong("rule_id"), AutomationRuleViews.brief(
                    at == null ? null : at.toLocalDateTime(),
                    rs.getString("status"), rs.getString("skip_reason")));
        }, runIds.toArray());
        return result;
    }

    private RunBrief lastRun(long tenantId, long ruleId) {
        return lastRuns(tenantId, List.of(ruleId)).get(ruleId);
    }

    private List<WebhookOption> webhookOptions(long tenantId) {
        List<WebhookOption> options = new ArrayList<>();
        jdbc.query("SELECT id, `system`, name, base_url FROM integration_config "
                + "WHERE tenant_id = ? AND active_key IS NOT NULL AND enabled <> 0 ORDER BY `system`, name",
                rs -> {
                    options.add(new WebhookOption(rs.getLong("id"), rs.getString("system"),
                            rs.getString("name"), hostOf(rs.getString("base_url"))));
                }, tenantId);
        return List.copyOf(options);
    }

    /** 只回 host：AC-2 要求接口不出现完整 webhook 地址。 */
    private static String hostOf(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        try {
            String host = URI.create(baseUrl.trim()).getHost();
            return host == null ? "" : host;
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    // ------------------------------------------------------------------ 校验辅助

    /** 作用域存在且在本租户可见；返回名称供回显。 */
    private String requireScopeVisible(long tenantId, String scopeType, Long scopeId) {
        String table = AutomationRuleValidation.SCOPE_DOMAIN.equals(scopeType) ? "biz_domain" : "sys_vendor";
        List<String> names = jdbc.queryForList(
                "SELECT name FROM " + table + " WHERE tenant_id = ? AND id = ? LIMIT 1",
                String.class, tenantId, scopeId);
        if (names.isEmpty()) {
            throw AutomationErrors.invalidScope(scopeType, scopeId);
        }
        return names.get(0);
    }

    /** WEBHOOK 的 configId 必须存在且已启用（active_key 非空），否则规则存下来也打不出去。 */
    private void requireWebhookConfigs(long tenantId, List<ActionSpec> actions) {
        for (ActionSpec action : actions) {
            if (!"WEBHOOK".equals(action.type())) {
                continue;
            }
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM integration_config WHERE tenant_id = ? AND id = ? "
                            + "AND active_key IS NOT NULL AND enabled <> 0",
                    Integer.class, tenantId, action.configId());
            if (count == null || count == 0) {
                throw AutomationErrors.schema("configId",
                        "Webhook 配置不存在或未启用：" + action.configId());
            }
        }
    }

    private AutomationRuleEntity load(long tenantId, long id) {
        return repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> AutomationErrors.ruleNotFound(id));
    }

    private AutomationRuleEntity save(AutomationRuleEntity entity, String name) {
        try {
            return repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            // uk_rule_name 撞了：并发下先查后写会漏，交给唯一键兜底并翻译成同一个 409。
            throw AutomationErrors.nameTaken(name);
        }
    }

    private String writeJson(JsonNode node, boolean array) {
        if (node == null || node.isNull()) {
            return array ? "[]" : "{}";
        }
        return objectMapper.writeValueAsString(node);
    }

    private static Map<String, Object> snapshot(AutomationRuleEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", entity.getName());
        map.put("enabled", Boolean.TRUE.equals(entity.getEnabled()));
        map.put("triggerType", entity.getTriggerType());
        map.put("scopeType", entity.getScopeType());
        map.put("scopeId", entity.getScopeId());
        map.put("priority", entity.getPriority());
        map.put("version", entity.getVersion());
        return map;
    }

    private static List<String> changedFields(Map<String, Object> before, Map<String, Object> after) {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, Object> entry : after.entrySet()) {
            Object old = before.get(entry.getKey());
            if (old == null ? entry.getValue() != null : !old.equals(entry.getValue())) {
                changed.add(entry.getKey());
            }
        }
        return List.copyOf(changed);
    }

    private static int versionOf(AutomationRuleEntity entity) {
        return entity.getVersion() == null ? 1 : entity.getVersion();
    }

    private long tenantId() {
        return TenantContext.require().tenantId();
    }

    private long userId() {
        TenantContext.Principal principal = TenantContext.principal();
        if (principal == null) {
            throw new ApiException(401, "UNAUTHORIZED", "未登录");
        }
        return principal.userId();
    }

    private String userName(long tenantId, Long userId) {
        if (userId == null) {
            return "";
        }
        Optional<String> name = jdbc.queryForList(
                "SELECT name FROM sys_user WHERE tenant_id = ? AND id = ? LIMIT 1",
                String.class, tenantId, userId).stream().findFirst();
        return name.orElse("");
    }
}
