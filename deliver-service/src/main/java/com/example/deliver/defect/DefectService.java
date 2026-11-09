package com.example.deliver.defect;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.example.deliver.defect.DefectViews.CreateDefect;
import com.example.deliver.defect.DefectViews.DefectFilter;
import com.example.deliver.defect.DefectViews.DefectView;
import com.example.deliver.defect.DefectViews.HistoryView;
import com.example.deliver.defect.DefectViews.TransitionCmd;
import com.example.deliver.reqread.ReleaseWindowReadModel;
import com.example.deliver.reqread.ReqReadModel;
import com.example.deliver.reqread.RequirementSnapshot;
import com.example.deliver.support.Visibility;
import com.example.platform.common.audit.AuditService;
import com.example.platform.common.code.CodeGenerator;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.notify.NotificationWriter;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.PermissionMatrix;
import com.example.platform.contract.ScopeTier;
import com.example.platform.contract.StateTables;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Defect lifecycle: create (ownership derived), transition through the shared state table,
 * reopen counting, and the 403 for a row outside the caller's scope.
 *
 * <p>The state machine is not re-implemented here: {@link StateTables} decides what is legal and
 * where it lands. That is what makes "button disabled but API accepts" impossible — the detail
 * response exposes {@code allowedActions} from the same table the transition asserts against.</p>
 */
@Service
public class DefectService {

    private static final Set<String> SEVERITIES = Set.of("P0", "P1", "P2", "P3");
    /** 缺陷来源；与 {@code defect.source VARCHAR(8)} 的取值域一致。 */
    private static final Set<String> SOURCES = Set.of("MANUAL", "TEST", "PROD", "BUILD");
    private static final String SOURCE_PROD = "PROD";
    /** 构建失败转缺陷的来源；由内部端点写入，普通创建入口也接受（来源只是标签）。 */
    private static final String SOURCE_BUILD = "BUILD";
    /** 生产缺陷提报的功能码；DENY 的角色连提报入口都不该有（服务层再判一次）。 */
    private static final String PROD_DEFECT_FUNCTION = "proddefect:report";

    private final DefectRepository defects;
    private final DefectHistoryRepository history;
    private final ReqReadModel readModel;
    private final ReleaseWindowReadModel releaseWindows;
    private final CodeGenerator codeGenerator;
    private final AuditService audit;
    private final NotificationWriter notifications;
    private final ScopedJdbc jdbc;

    public DefectService(DefectRepository defects,
                         DefectHistoryRepository history,
                         ReqReadModel readModel,
                         ReleaseWindowReadModel releaseWindows,
                         CodeGenerator codeGenerator,
                         AuditService audit,
                         NotificationWriter notifications,
                         ScopedJdbc jdbc) {
        this.defects = defects;
        this.history = history;
        this.readModel = readModel;
        this.releaseWindows = releaseWindows;
        this.codeGenerator = codeGenerator;
        this.audit = audit;
        this.notifications = notifications;
        this.jdbc = jdbc;
    }

    @Transactional
    public DefectView create(CreateDefect cmd) {
        DataScope scope = TenantContext.require();
        String title = requireText(cmd.title(), "title");
        String severity = normaliseSeverity(cmd.severity());
        String priority = normaliseSeverity(cmd.priority() == null ? severity : cmd.priority());
        String source = normaliseSource(cmd.source());
        String foundPhase = trimToNull(cmd.foundPhase());
        if (foundPhase != null && foundPhase.length() > 16) {
            throw new ApiException(400, "VALIDATION_FAILED", "foundPhase 不能超过 16 字",
                    Map.of("field", "foundPhase", "max", 16));
        }

        RequirementSnapshot requirement = requireRequirement(cmd.requirementId());
        Long releaseId = cmd.releaseId() != null ? cmd.releaseId() : requirement.releaseId();
        if (SOURCE_PROD.equals(source)) {
            requireProdDefectPermission(scope);
            // 生产缺陷必须显式指明版本：需求上的版本只说明"在哪个版本做"，发布观察窗
            // 只能按缺陷自己声明的版本算——回退需求版本会把窗口算到别的发布上。
            if (cmd.releaseId() == null || releaseId == null) {
                throw new ApiException(400, "PROD_DEFECT_RELEASE_REQUIRED",
                        "生产缺陷必须关联版本（releaseId）：没有版本就没有观察窗，无法判定是否逃逸",
                        Map.of("field", "releaseId"));
            }
        }

        DefectEntity entity = new DefectEntity();
        entity.setTenantId(scope.tenantId());
        entity.setCode(codeGenerator.next("DEFECT", scope.tenantId()));
        // Ownership is derived, never accepted from the body (AC-13).
        entity.setDomainId(requirement.domainId());
        entity.setVendorId(requirement.vendorId());
        entity.setRequirementId(requirement.id());
        entity.setReleaseId(releaseId);
        entity.setTestCaseId(cmd.testCaseId());
        entity.setTestCaseVersion(cmd.testCaseVersion());
        entity.setTestExecutionId(cmd.testExecutionId());
        entity.setTitle(title);
        entity.setDescription(cmd.description());
        entity.setReproSteps(cmd.reproSteps());
        entity.setSeverity(severity);
        entity.setPriority(priority);
        entity.setStatus("NEW");
        entity.setReporterId(scope.userId());
        entity.setHandlerId(cmd.handlerId());
        entity.setReopenCount(0);
        entity.setExpectedFixDate(cmd.expectedFixDate());
        entity.setSource(source);
        entity.setFoundPhase(foundPhase);
        LocalDateTime foundAt = LocalDateTime.now();
        entity.setFoundAt(foundAt);
        entity.setCreatedBy(scope.userId());
        entity.setUpdatedBy(scope.userId());
        // 逃逸判定只在 INSERT 这一刻发生；窗口终点一起固化，之后改配置不改写历史（AC-8.3）。
        applyEscapeVerdict(entity, source, releaseId, foundAt);
        defects.save(entity);

        record(entity, "create", null, "NEW", scope.userId(), null);
        audit.record("DEFECT_CREATE", "DEFECT", entity.getId(), Map.of(
                "code", entity.getCode(),
                "domainId", entity.getDomainId(),
                "vendorId", String.valueOf(entity.getVendorId()),
                "source", source,
                "isEscape", Integer.valueOf(1).equals(entity.getIsEscape())));
        if (entity.getHandlerId() != null) {
            notifyHandler(entity, "新缺陷指派", "缺陷 " + entity.getCode() + " 已指派给你：" + title);
        }
        return view(entity, true);
    }

    /** Files a defect from a failed execution, carrying its pinned case version across. */
    @Transactional
    public DefectView createFromExecution(long executionId, CreateDefect cmd) {
        DataScope scope = TenantContext.require();
        List<Map<String, Object>> rows = jdbc.list("""
                SELECT e.id AS id, e.plan_id AS plan_id, e.case_id AS case_id,
                       e.case_version AS case_version, e.requirement_id AS requirement_id
                FROM test_execution e WHERE e.id = :id AND /*scope:e*/
                """, Map.of("id", executionId));
        if (rows.isEmpty()) {
            throw new ApiException(404, "EXECUTION_NOT_FOUND", "执行记录不存在或不在你的数据范围内", null);
        }
        Map<String, Object> row = rows.get(0);
        Long requirementId = com.example.deliver.support.Rows.toLong(
                com.example.deliver.support.Rows.object(row, "requirement_id"));
        CreateDefect merged = new CreateDefect(
                requirementId,
                cmd == null ? null : cmd.releaseId(),
                com.example.deliver.support.Rows.toLong(
                        com.example.deliver.support.Rows.object(row, "case_id")),
                com.example.deliver.support.Rows.toInt(
                        com.example.deliver.support.Rows.object(row, "case_version")),
                executionId,
                cmd == null || cmd.title() == null ? "执行失败转缺陷" : cmd.title(),
                cmd == null ? null : cmd.description(),
                cmd == null ? null : cmd.reproSteps(),
                cmd == null ? null : cmd.severity(),
                cmd == null ? null : cmd.priority(),
                cmd == null ? null : cmd.handlerId(),
                cmd == null ? null : cmd.expectedFixDate(),
                "TEST",
                cmd == null ? null : cmd.foundPhase());
        return create(merged);
    }

    @Transactional
    public DefectView transition(long id, TransitionCmd cmd) {
        DataScope scope = TenantContext.require();
        DefectEntity entity = load(id);
        DataScopeGuard.assertVisible(scope, entity, audit);

        String action = cmd == null || cmd.action() == null ? "" : cmd.action().trim().toLowerCase();
        if (action.isEmpty()) {
            throw new ApiException(400, "ACTION_REQUIRED", "缺少 action", null);
        }
        if ("assign".equals(action)) {
            if (cmd.handlerId() == null) {
                throw new ApiException(400, "HANDLER_REQUIRED", "转派必须指定处理人", null);
            }
            entity.setHandlerId(cmd.handlerId());
            entity.setUpdatedBy(scope.userId());
            defects.save(entity);
            record(entity, "assign", entity.getStatus(), entity.getStatus(), scope.userId(), cmd.comment());
            notifyHandler(entity, "缺陷转派", "缺陷 " + entity.getCode() + " 已转派给你");
            return view(entity, true);
        }

        String from = entity.getStatus();
        String target = StateTables.target(StateTables.DEFECT, from, action);
        if (target == null) {
            throw illegal(from, action);
        }
        entity.setStatus(target);
        entity.setUpdatedBy(scope.userId());
        if (StateTables.isReopen(StateTables.DEFECT, action)) {
            entity.setReopenCount(entity.getReopenCount() + 1);
            entity.setClosedAt(null);
        }
        if ("CLOSED".equals(target)) {
            entity.setClosedAt(LocalDateTime.now());
        }
        defects.save(entity);
        record(entity, action, from, target, scope.userId(), cmd.comment());
        return view(entity, true);
    }

    /**
     * 构建失败转缺陷（AC-3.6）。幂等键是 {@code job#buildNumber}，写进 description 的
     * {@code BUILD_KEY:} 标记里——缺陷表没有外部幂等键列，而加一列只为 CI 服务不值得
     * （缺陷的真实身份仍然是「这条需求下的一个问题」，不是「某个构建」）。
     *
     * <p>为什么带 {@code source=BUILD} 而不是 MANUAL：来源决定统计口径（构建失败率 vs
     * 人工提报），而且它是这条缺陷唯一能自我解释的证据。</p>
     */
    @Transactional
    public DefectView createFromBuild(DefectViews.CreateFromBuild cmd) {
        DataScope scope = TenantContext.require();
        if (cmd == null) {
            throw new ApiException(400, "VALIDATION_FAILED", "请求体不能为空", null);
        }
        String key = trimToNull(cmd.idempotencyKey());
        Long requirementId = cmd.requirementId();
        if (requirementId != null && key != null) {
            String marker = "BUILD_KEY: " + key;
            List<DefectEntity> existing = defects
                    .findByTenantIdAndRequirementId(scope.tenantId(), requirementId);
            for (DefectEntity candidate : existing) {
                if (SOURCE_BUILD.equals(candidate.getSource())
                        && candidate.getDescription() != null
                        && candidate.getDescription().contains(marker)) {
                    return view(candidate, true);
                }
            }
        }
        String title = trimToNull(cmd.title());
        if (title == null) {
            title = "构建失败";
        }
        if (title.length() > 200) {
            title = title.substring(0, 200);
        }
        DefectViews.CreateDefect delegate = new DefectViews.CreateDefect(
                requirementId,
                cmd.releaseId(),
                null,
                null,
                null,
                title,
                cmd.description(),
                null,
                cmd.severity() == null || cmd.severity().isBlank() ? "P1" : cmd.severity(),
                null,
                null,
                null,
                SOURCE_BUILD,
                SOURCE_BUILD);
        return create(delegate);
    }

    @Transactional(readOnly = true)
    public Object detail(long id) {
        DataScope scope = TenantContext.require();
        DefectEntity entity = load(id);
        DataScopeGuard.assertVisible(scope, entity, audit);
        // AC-13: business-side roles (business department / second-level enterprise) see a
        // summary only. Their tier is BIZ or SELF, never the delivery-side tiers.
        if (scope.tier() == ScopeTier.BIZ || scope.tier() == ScopeTier.SELF) {
            return summary(entity);
        }
        return view(entity, true);
    }

    private DefectViews.Summary summary(DefectEntity entity) {
        return new DefectViews.Summary(entity.getId(), entity.getCode(), entity.getTitle(),
                entity.getStatus(), entity.getSeverity(), entity.getExpectedFixDate(), true);
    }

    /** Non-throwing visibility probe used by list enrichment and traceability. */
    public boolean visible(DefectEntity entity) {
        DataScope scope = TenantContext.require();
        return Visibility.canSee(scope, entity.getTenantId(), entity.getDomainId(), entity.getVendorId());
    }

    public List<DefectEntity> byRequirement(long requirementId) {
        DataScope scope = TenantContext.require();
        return defects.findByTenantIdAndRequirementId(scope.tenantId(), requirementId).stream()
                .filter(this::visible)
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    private DefectEntity load(long id) {
        DataScope scope = TenantContext.require();
        DefectEntity entity = defects.findById(id)
                .orElseThrow(() -> new ApiException(404, "DEFECT_NOT_FOUND", "缺陷不存在", null));
        if (entity.getTenantId() != scope.tenantId()) {
            // Same answer as "missing": a wrong tenant must not learn that the id exists.
            throw new ApiException(404, "DEFECT_NOT_FOUND", "缺陷不存在", null);
        }
        return entity;
    }

    private RequirementSnapshot requireRequirement(Long requirementId) {
        if (requirementId == null) {
            throw new ApiException(400, "REQUIREMENT_REQUIRED",
                    "缺陷必须挂在需求下（域与厂商由需求派生）", null);
        }
        RequirementSnapshot snapshot = readModel.get(requirementId);
        if (snapshot == null) {
            throw new ApiException(404, "REQUIREMENT_NOT_FOUND", "需求不存在或不在你的数据范围内", null);
        }
        return snapshot;
    }

    private ApiException illegal(String from, String action) {
        Set<String> allowed = StateTables.actions(StateTables.DEFECT, from);
        List<String> allowedTargets = new ArrayList<>();
        for (String candidate : allowed) {
            allowedTargets.add(StateTables.target(StateTables.DEFECT, from, candidate));
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("status", from);
        detail.put("action", action);
        detail.put("allowedActions", new ArrayList<>(allowed));
        detail.put("allowedTargets", allowedTargets.stream().distinct().toList());
        return new ApiException(409, "ILLEGAL_TRANSITION",
                "缺陷当前状态 " + from + " 不允许动作 " + action, detail);
    }

    private void record(DefectEntity entity, String action, String from, String to, long operator, String comment) {
        DefectHistoryEntity entry = new DefectHistoryEntity();
        entry.setTenantId(entity.getTenantId());
        entry.setDefectId(entity.getId());
        entry.setAction(action);
        entry.setFromStatus(from);
        entry.setToStatus(to);
        entry.setOperatorId(operator);
        entry.setComment(comment == null ? null : comment.substring(0, Math.min(comment.length(), 512)));
        history.save(entry);
        audit.record("DEFECT_STATE_CHANGE", "DEFECT", entity.getId(), Map.of(
                "action", action,
                "from", String.valueOf(from),
                "to", String.valueOf(to)));
    }

    private void notifyHandler(DefectEntity entity, String title, String content) {
        Long handler = entity.getHandlerId();
        if (handler == null) {
            return;
        }
        notifications.send(handler, "DEFECT", title, content, "DEFECT", entity.getId());
    }

    DefectView view(DefectEntity entity, boolean withHistory) {
        RequirementSnapshot requirement = entity.getRequirementId() == null
                ? null : readModel.get(entity.getRequirementId());
        List<HistoryView> entries = new ArrayList<>();
        if (withHistory) {
            for (DefectHistoryEntity row : history.findByTenantIdAndDefectIdOrderByIdAsc(
                    entity.getTenantId(), entity.getId())) {
                entries.add(new HistoryView(row.getId(), row.getAction(), row.getFromStatus(),
                        row.getToStatus(), row.getOperatorId(), row.getComment(), row.getCreatedAt()));
            }
        }
        return new DefectView(
                entity.getId(),
                entity.getCode(),
                entity.getRequirementId(),
                requirement == null ? null : requirement.code(),
                requirement == null ? null : requirement.title(),
                entity.getReleaseId(),
                entity.getTestCaseId(),
                entity.getTestCaseVersion(),
                entity.getTestExecutionId(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getReproSteps(),
                entity.getSeverity(),
                entity.getPriority(),
                entity.getStatus(),
                entity.getReporterId(),
                entity.getHandlerId(),
                entity.getDomainId(),
                entity.getVendorId(),
                entity.getReopenCount(),
                entity.getExpectedFixDate(),
                entity.getFoundAt(),
                entity.getClosedAt(),
                new ArrayList<>(StateTables.actions(StateTables.DEFECT, entity.getStatus())),
                entries,
                entity.getSource(),
                entity.getFoundPhase(),
                Integer.valueOf(1).equals(entity.getIsEscape()),
                entity.getEscapeWindowEnd());
    }

    /**
     * 一次判定：PROD 缺陷是否落在"最早成功发布结束 + 观察窗小时数"之内。
     *
     * <p>窗口起点取 {@code deploy_order.execution_result='SUCCESS'} 的最早结束时间（版本表没有
     * 结束时间），经 {@link ReleaseWindowReadModel} 这一唯一跨域只读入口取得。边界按严格小于：
     * 恰好在 T+观察窗 那一刻发现的缺陷不算逃逸。没有成功发布时窗口不存在，判 0 且窗口终点留空。</p>
     */
    private void applyEscapeVerdict(DefectEntity entity, String source, Long releaseId, LocalDateTime foundAt) {
        entity.setIsEscape(0);
        entity.setEscapeWindowEnd(null);
        if (!SOURCE_PROD.equals(source) || releaseId == null) {
            return;
        }
        Instant windowEnd = releaseWindows.window(releaseId).windowEnd();
        if (windowEnd == null) {
            return;
        }
        boolean escaped = foundAt.atZone(ZoneId.systemDefault()).toInstant().isBefore(windowEnd);
        entity.setIsEscape(escaped ? 1 : 0);
        if (escaped) {
            entity.setEscapeWindowEnd(LocalDateTime.ofInstant(windowEnd, ZoneId.systemDefault()));
        }
    }

    private static void requireProdDefectPermission(DataScope scope) {
        PermissionMatrix.Access access = PermissionMatrix.accessOf(PROD_DEFECT_FUNCTION, TenantContext.roles());
        if (access == PermissionMatrix.Access.DENY) {
            throw new ApiException(403, "SCOPE_DENIED", "你没有生产缺陷提报权限",
                    Map.of("function", PROD_DEFECT_FUNCTION));
        }
    }

    private String normaliseSource(String value) {
        String normalised = value == null || value.isBlank()
                ? "MANUAL" : value.trim().toUpperCase(Locale.ROOT);
        if (!SOURCES.contains(normalised)) {
            throw new ApiException(400, "VALIDATION_FAILED", "source 只能是 MANUAL / TEST / PROD / BUILD",
                    Map.of("field", "source", "value", String.valueOf(value)));
        }
        return normalised;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(400, "VALIDATION_FAILED", field + " 不能为空", Map.of("field", field));
        }
        return value.trim();
    }

    private String normaliseSeverity(String value) {
        String upper = value == null ? "" : value.trim().toUpperCase();
        if (!SEVERITIES.contains(upper)) {
            throw new ApiException(400, "VALIDATION_FAILED", "severity/priority 只能是 P0..P3",
                    Map.of("value", String.valueOf(value)));
        }
        return upper;
    }

    /** Kept as a nested class so the 403 + audit pairing has exactly one implementation. */
    private static final class DataScopeGuard {

        private DataScopeGuard() {
        }

        static void assertVisible(DataScope scope, DefectEntity entity, AuditService audit) {
            if (Visibility.canSee(scope, entity.getTenantId(), entity.getDomainId(), entity.getVendorId())) {
                return;
            }
            audit.recordInNewTx("SCOPE_DENIED", "DEFECT", entity.getId(), Map.of(
                    "userId", scope.userId(),
                    "tier", String.valueOf(scope.tier()),
                    "rowDomain", entity.getDomainId(),
                    "rowVendor", String.valueOf(entity.getVendorId())));
            throw new ApiException(403, "FORBIDDEN", "该缺陷不在你的数据范围内", null);
        }
    }

    /** Column order of the CSV export; shared with the controller so both stay in step. */
    public static List<String> csvColumns() {
        return List.of("code", "title", "severity", "priority", "status", "reopen_count",
                "found_at", "closed_at");
    }

    /** Convenience for the controller's default filter. */
    public static DefectFilter none() {
        return DefectFilter.empty();
    }

    static Collection<String> statuses() {
        return new LinkedHashSet<>(StateTables.statuses(StateTables.DEFECT));
    }
}
