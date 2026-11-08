package com.example.deliver.defect;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.deliver.defect.DefectViews.CreateDefect;
import com.example.deliver.defect.DefectViews.DefectFilter;
import com.example.deliver.defect.DefectViews.DefectView;
import com.example.deliver.defect.DefectViews.HistoryView;
import com.example.deliver.defect.DefectViews.TransitionCmd;
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

    private final DefectRepository defects;
    private final DefectHistoryRepository history;
    private final ReqReadModel readModel;
    private final CodeGenerator codeGenerator;
    private final AuditService audit;
    private final NotificationWriter notifications;
    private final ScopedJdbc jdbc;

    public DefectService(DefectRepository defects,
                         DefectHistoryRepository history,
                         ReqReadModel readModel,
                         CodeGenerator codeGenerator,
                         AuditService audit,
                         NotificationWriter notifications,
                         ScopedJdbc jdbc) {
        this.defects = defects;
        this.history = history;
        this.readModel = readModel;
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

        RequirementSnapshot requirement = requireRequirement(cmd.requirementId());

        DefectEntity entity = new DefectEntity();
        entity.setTenantId(scope.tenantId());
        entity.setCode(codeGenerator.next("DEFECT", scope.tenantId()));
        // Ownership is derived, never accepted from the body (AC-13).
        entity.setDomainId(requirement.domainId());
        entity.setVendorId(requirement.vendorId());
        entity.setRequirementId(requirement.id());
        entity.setReleaseId(cmd.releaseId() != null ? cmd.releaseId() : requirement.releaseId());
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
        entity.setFoundAt(LocalDateTime.now());
        entity.setCreatedBy(scope.userId());
        entity.setUpdatedBy(scope.userId());
        defects.save(entity);

        record(entity, "create", null, "NEW", scope.userId(), null);
        audit.record("DEFECT_CREATE", "DEFECT", entity.getId(), Map.of(
                "code", entity.getCode(),
                "domainId", entity.getDomainId(),
                "vendorId", String.valueOf(entity.getVendorId())));
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
                cmd == null ? null : cmd.expectedFixDate());
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

    private DefectView view(DefectEntity entity, boolean withHistory) {
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
                entries);
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
