package com.example.req.requirement;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.ActionPermissionResolver;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.RequirementActionMatrix;
import com.example.req.approval.ApprovalEngine;
import com.example.req.approval.ApprovalHistory;
import com.example.req.approval.ApprovalInstance;
import com.example.req.approval.ApprovalInstanceRepository;
import com.example.req.approval.ApprovalStore;
import com.example.req.approval.ApprovalTask;
import com.example.req.approval.StartApproval;
import com.example.req.approval.WorkflowJson;
import com.example.req.approval.WorkflowVersion;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工时评估初审（第五类审批单据，r26）：提交/重提、六态派生、前置门判定与 allowedActions 过滤。
 *
 * <p>六态派生与门判定必须同源（AC-11）：{@link #statusOf} 是唯一的状态真相，
 * {@link #applyGate} 与 {@link #ensureGate} 都只读它——否则「详情不含 schedule」与
 * 「接口放行 schedule」会各说各话。</p>
 *
 * <p>零 schema 变更：单据落在既有 {@code wf_instance/wf_task/wf_history}，人天与说明存
 * {@code StartApproval.attributes} → SUBMIT 流水快照。</p>
 */
@Component
public class WbsEstimateService {

    public static final String BIZ_TYPE = WorkflowVersion.BIZ_WBS_ESTIMATE;
    /** 人天偏差阈值：严格大于即失效（10→12.0 放行、10→12.5 拦截）。 */
    public static final BigDecimal STALE_THRESHOLD = new BigDecimal("0.20");

    public static final String STATUS_NONE = "NONE";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";
    public static final String STATUS_STALE = "STALE";

    /** 说明上限（字）。 */
    public static final int NOTE_MAX = 500;

    private static final String ACTION_SCHEDULE = "schedule";
    private static final String ACTION_START_DEV = "start_dev";

    private final RequirementService requirements;
    private final ObjectProvider<ApprovalEngine> engine;
    private final ApprovalStore store;
    private final ApprovalInstanceRepository instances;
    private final AuditService auditService;
    private final ActionPermissionResolver actionPermissions;
    private final WorkflowJson json;
    private final RequirementStateMachine stateMachine;

    public WbsEstimateService(RequirementService requirements,
                              ObjectProvider<ApprovalEngine> engine,
                              ApprovalStore store,
                              ApprovalInstanceRepository instances,
                              AuditService auditService,
                              ActionPermissionResolver actionPermissions,
                              WorkflowJson json,
                              RequirementStateMachine stateMachine) {
        this.requirements = requirements;
        this.engine = engine;
        this.store = store;
        this.instances = instances;
        this.auditService = auditService;
        this.actionPermissions = actionPermissions;
        this.json = json;
        this.stateMachine = stateMachine;
    }

    // ------------------------------------------------------------------ 提交

    /** 提交（重提）：校验 → 已通过且人天未变则 409 → 作废旧 PENDING → engine.start。 */
    @Transactional
    public WbsEstimateViews.EstimateView submit(long requirementId, BigDecimal estimateDays,
                                                String note) {
        // 顺序即契约（AC-1/AC-2/AC-5）：可见性/权限 → 类型 → 终态 → 人天 → 说明 → 已通过。
        DataScope scope = TenantContext.require();
        RequirementEntity entity = requirements.requireVisible(requirementId);
        requireDeliverPermission(entity, scope);

        if (RequirementRules.TYPE_BUS.equals(entity.getType())) {
            throw RequirementErrors.validation("业务需求不需要工时评估", Map.of("field", "type"));
        }
        if (stateMachine.isTerminal(entity.getType(), entity.getStatus())) {
            throw RequirementErrors.validation("需求已关闭，不能提交工时评估",
                    Map.of("field", "status"));
        }
        if (estimateDays == null || estimateDays.signum() <= 0) {
            throw RequirementErrors.validation("评估人天必须大于 0",
                    Map.of("field", "estimateDays"));
        }
        RequirementRules.Violation precision =
                RequirementRules.checkHalfDay(estimateDays, "estimateDays");
        if (precision != null) {
            throw RequirementErrors.rule(precision);
        }
        String trimmedNote = note == null ? null : note.strip();
        if (trimmedNote != null && trimmedNote.length() > NOTE_MAX) {
            throw RequirementErrors.validation("说明不能超过 " + NOTE_MAX + " 字",
                    Map.of("field", "note", "max", NOTE_MAX));
        }

        ApprovalInstance approved = latest(requirementId, ApprovalInstance.STATUS_APPROVED);
        if (approved != null) {
            BigDecimal approvedDays = EstimateSnapshot.days(
                    EstimateSnapshot.document(store, json, approved.getId()));
            BigDecimal deviation = deviation(estimateDays, approvedDays);
            // 人天未变，或变化仍在 ±20% 内：已有有效单据，不重复建单（AC-5）。
            if (deviation == null || deviation.compareTo(STALE_THRESHOLD) <= 0) {
                throw RequirementErrors.estimateAlreadyApproved(requirementId, approved.getId());
            }
        }

        supersedeActive(requirementId, -1L, scope.userId());
        long instanceId = start(requirementId, entity, estimateDays, trimmedNote);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("instanceId", instanceId);
        event.put("estimateDays", estimateDays);
        event.put("note", trimmedNote == null ? "" : trimmedNote);
        auditService.record("ESTIMATE_SUBMIT", RequirementService.BIZ_TYPE, requirementId, event);
        return view(entity);
    }

    private long start(long requirementId, RequirementEntity entity, BigDecimal estimateDays,
                       String note) {
        ApprovalEngine approvalEngine = engine.getIfAvailable();
        if (approvalEngine == null) {
            throw RequirementErrors.approvalUnavailable();
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("estimateDays", estimateDays);
        if (note != null && !note.isBlank()) {
            attributes.put("note", note);
        }
        attributes.put("source", "UI");
        return approvalEngine.start(new StartApproval(BIZ_TYPE, requirementId, entity.getType(),
                entity.getDomainId(), attributes, null, null)).id();
    }

    // ------------------------------------------------------------------ 视图

    /** 详情页 estimate 块；BUS 返回 null（调用方判空）。NONE 是完整对象。 */
    @Transactional(readOnly = true)
    public WbsEstimateViews.EstimateView view(RequirementEntity entity) {
        if (entity == null || RequirementRules.TYPE_BUS.equals(entity.getType())) {
            return null;
        }
        if (entity.getId() == null) {
            return WbsEstimateViews.EstimateView.none();
        }
        String status = statusOf(entity);
        ApprovalInstance instance = effectiveInstance(entity);
        if (instance == null) {
            return WbsEstimateViews.EstimateView.none();
        }
        Map<String, Object> document =
                EstimateSnapshot.document(store, json, instance.getId());
        BigDecimal proposedDays = EstimateSnapshot.days(document);
        BigDecimal approvedDays = ApprovalInstance.STATUS_APPROVED.equals(instance.getStatus())
                ? proposedDays : null;
        String reason = reasonOf(instance, status, entity, approvedDays);
        return new WbsEstimateViews.EstimateView(status, instance.getId(), instance.getStatus(),
                proposedDays, approvedDays, EstimateSnapshot.note(document), reason,
                approverOf(instance.getId()), instance.getStartedAt(), instance.getEndedAt(),
                WbsEstimateViews.EstimateView.REQUIRED);
    }

    /** 六态之一：NONE|PENDING|APPROVED|REJECTED|SUPERSEDED|STALE。 */
    public String statusOf(RequirementEntity entity) {
        if (entity == null || entity.getId() == null) {
            return STATUS_NONE;
        }
        if (activeInstance(entity).isPresent()) {
            return STATUS_PENDING;
        }
        ApprovalInstance last = effectiveInstance(entity);
        if (last == null) {
            return STATUS_NONE;
        }
        String status = last.getStatus();
        if (ApprovalInstance.STATUS_APPROVED.equals(status)) {
            return isStale(entity, last) ? STATUS_STALE : STATUS_APPROVED;
        }
        if (ApprovalInstance.STATUS_REJECTED.equals(status)) {
            return STATUS_REJECTED;
        }
        if (ApprovalInstance.STATUS_SUPERSEDED.equals(status)) {
            return STATUS_SUPERSEDED;
        }
        return STATUS_PENDING;
    }

    // -------------------------------------------------------------------- 门

    /** 该 (type, action) 是否受门约束：PDR+schedule、RD+start_dev。 */
    public boolean gated(String type, String action) {
        if (type == null || action == null) {
            return false;
        }
        return (RequirementRules.TYPE_PDR.equals(type) && ACTION_SCHEDULE.equals(action))
                || (RequirementRules.TYPE_RD.equals(type) && ACTION_START_DEV.equals(action));
    }

    /** 门未满足抛 409 ESTIMATE_NOT_APPROVED（detail 三键）；满足直接返回。 */
    public void ensureGate(RequirementEntity entity, String action) {
        if (entity == null || !gated(entity.getType(), action)) {
            return;
        }
        String status = statusOf(entity);
        if (STATUS_APPROVED.equals(status)) {
            return;
        }
        throw RequirementErrors.estimateNotApproved(entity.getId(), status);
    }

    /** 从 allowedActions 里摘掉被门挡住的受门动作（空白列表返回原集合语义）。 */
    public List<String> applyGate(RequirementEntity entity, List<String> actions) {
        if (entity == null || actions == null || actions.isEmpty()
                || !gatedType(entity.getType())) {
            return actions;
        }
        if (STATUS_APPROVED.equals(statusOf(entity))) {
            return actions;
        }
        List<String> filtered = new ArrayList<>(actions.size());
        for (String action : actions) {
            if (!gated(entity.getType(), action)) {
                filtered.add(action);
            }
        }
        return filtered.isEmpty() ? List.of() : List.copyOf(filtered);
    }

    // ------------------------------------------------------------------ 内部

    private boolean gatedType(String type) {
        return RequirementRules.TYPE_PDR.equals(type) || RequirementRules.TYPE_RD.equals(type);
    }

    /** 已通过的人天相对当前人天的偏差（0.20 = 20%）；无法比较时返回 null。 */
    static BigDecimal deviation(BigDecimal current, BigDecimal approved) {
        if (current == null || approved == null || approved.signum() <= 0) {
            return null;
        }
        return current.subtract(approved).abs()
                .divide(approved, 4, RoundingMode.HALF_UP);
    }

    private boolean isStale(RequirementEntity entity, ApprovalInstance approved) {
        BigDecimal approvedDays = EstimateSnapshot.days(
                EstimateSnapshot.document(store, json, approved.getId()));
        BigDecimal deviation = deviation(entity.getEstimateDays(), approvedDays);
        return deviation == null || deviation.compareTo(STALE_THRESHOLD) > 0;
    }

    private String reasonOf(ApprovalInstance instance, String status, RequirementEntity entity,
                            BigDecimal approvedDays) {
        if (STATUS_REJECTED.equals(status)) {
            return lastRejectComment(instance.getId());
        }
        if (!STATUS_STALE.equals(status)) {
            return null;
        }
        BigDecimal current = entity.getEstimateDays();
        BigDecimal deviation = deviation(current, approvedDays);
        if (deviation == null) {
            return "评估后需求人天缺失，评估单已失效";
        }
        return "人天偏差 " + deviation.multiply(new BigDecimal("100"))
                .setScale(1, RoundingMode.HALF_UP).toPlainString() + "%（评估 "
                + approvedDays.stripTrailingZeros().toPlainString() + " 天 → 当前 "
                + current.stripTrailingZeros().toPlainString() + " 天），超过 20% 阈值";
    }

    private String lastRejectComment(long instanceId) {
        String comment = null;
        for (ApprovalHistory history : store.history(instanceId)) {
            if (ApprovalHistory.REJECT.equals(history.getAction())) {
                comment = history.getComment();
            }
        }
        return comment;
    }

    private Long approverOf(long instanceId) {
        Long decided = null;
        Long pending = null;
        for (ApprovalTask task : store.tasks(instanceId)) {
            if (ApprovalTask.STATUS_APPROVED.equals(task.getStatus())
                    || ApprovalTask.STATUS_REJECTED.equals(task.getStatus())) {
                if (decided == null) {
                    decided = task.getApproverId();
                }
            } else if (task.isPending() && pending == null) {
                pending = task.getApproverId();
            }
        }
        return decided != null ? decided : pending;
    }

    private Optional<ApprovalInstance> activeInstance(RequirementEntity entity) {
        if (entity == null || entity.getId() == null || entity.getTenantId() == null) {
            return Optional.empty();
        }
        return instances.findFirstByTenantIdAndBizTypeAndBizIdAndActiveFlag(
                entity.getTenantId(), BIZ_TYPE, entity.getId(), ApprovalInstance.ACTIVE);
    }

    /**
     * 当前生效的单据（AC-5/AC-9）：撤回（WITHDRAWN）不是「评估结论」，不得遮蔽上一张有效单——
     * 研发撤回一次重提后，门应回落到此前已通过的评估，而不是把整条需求判成 NONE。
     * 全部单据都被撤回时才返回 null（真的是从未评估过）。
     */
    private ApprovalInstance effectiveInstance(RequirementEntity entity) {
        Optional<ApprovalInstance> active = activeInstance(entity);
        if (active.isPresent()) {
            return active.get();
        }
        ApprovalInstance last = latestInstance(entity);
        if (last == null || !ApprovalInstance.STATUS_WITHDRAWN.equals(last.getStatus())) {
            return last;
        }
        if (entity == null || entity.getId() == null || entity.getTenantId() == null) {
            return null;
        }
        for (ApprovalInstance instance : instances.findAllByTenantIdAndBizTypeAndBizIdOrderByIdDesc(
                entity.getTenantId(), BIZ_TYPE, entity.getId())) {
            if (!ApprovalInstance.STATUS_WITHDRAWN.equals(instance.getStatus())) {
                return instance;
            }
        }
        return null;
    }

    private ApprovalInstance latestInstance(RequirementEntity entity) {
        if (entity == null || entity.getId() == null || entity.getTenantId() == null) {
            return null;
        }
        return instances.findFirstByTenantIdAndBizTypeAndBizIdOrderByIdDesc(
                entity.getTenantId(), BIZ_TYPE, entity.getId()).orElse(null);
    }

    private ApprovalInstance latest(long requirementId, String status) {
        return instances.findFirstByTenantIdAndBizTypeAndBizIdOrderByIdDesc(
                        TenantContext.require().tenantId(), BIZ_TYPE, requirementId)
                .filter(instance -> status.equals(instance.getStatus()))
                .orElse(null);
    }

    /** 旧的在途单置 SUPERSEDED 并关闭其待办；{@code exceptInstanceId} 用于回调里排除刚通过的那张。 */
    void supersedeActive(long requirementId, long exceptInstanceId, long operatorId) {
        Optional<ApprovalInstance> active = instances.findFirstByTenantIdAndBizTypeAndBizIdAndActiveFlag(
                TenantContext.require().tenantId(), BIZ_TYPE, requirementId, ApprovalInstance.ACTIVE);
        if (active.isEmpty() || active.get().getId() == exceptInstanceId) {
            return;
        }
        ApprovalInstance instance = active.get();
        LocalDateTime now = LocalDateTime.now();
        for (ApprovalTask task : store.tasks(instance.getId())) {
            if (!task.isPending()) {
                continue;
            }
            task.setStatus(ApprovalTask.STATUS_CANCELLED);
            task.setComment("评估单已重新提交，旧单作废");
            task.setHandledAt(now);
            task.setUpdatedAt(now);
            task.setUpdatedBy(operatorId);
            store.saveTask(task);
            appendHistory(instance, task, operatorId, "评估单已重新提交，旧单作废");
        }
        instance.finish(ApprovalInstance.STATUS_SUPERSEDED, now);
        instance.setUpdatedBy(operatorId);
        store.saveInstance(instance);
        appendHistory(instance, null, operatorId, "评估单已重新提交，旧单作废");
    }

    /**
     * 新单通过后旧 APPROVED 必须被取代（AC-5：任意时刻 APPROVED ≤ 1）。
     * 提交阶段旧 APPROVED 保持 APPROVED（AC-5 原文），只有新单真正通过时才落终态——
     * 这样「重提→被驳回」不会让需求失去原有的有效评估。
     */
    void supersedeApproved(long requirementId, long exceptInstanceId, long operatorId) {
        for (ApprovalInstance instance : instances.findAllByTenantIdAndBizTypeAndBizIdOrderByIdDesc(
                TenantContext.require().tenantId(), BIZ_TYPE, requirementId)) {
            if (instance.getId() == exceptInstanceId
                    || !ApprovalInstance.STATUS_APPROVED.equals(instance.getStatus())) {
                continue;
            }
            LocalDateTime now = LocalDateTime.now();
            instance.finish(ApprovalInstance.STATUS_SUPERSEDED, now);
            instance.setUpdatedBy(operatorId);
            store.saveInstance(instance);
            appendHistory(instance, null, operatorId, "评估单已被新的评估单取代");
        }
    }

    private void appendHistory(ApprovalInstance instance, ApprovalTask task, long operatorId,
                               String comment) {
        ApprovalHistory entry = new ApprovalHistory();
        entry.setTenantId(instance.getTenantId());
        entry.setInstanceId(instance.getId());
        entry.setTaskId(task == null ? null : task.getId());
        entry.setAction(task == null
                ? ApprovalHistory.INSTANCE_WITHDRAWN : ApprovalHistory.TASK_CANCELLED);
        entry.setOperatorId(operatorId);
        entry.setComment(comment);
        entry.setCreatedAt(LocalDateTime.now());
        store.appendHistory(entry);
    }

    private void requireDeliverPermission(RequirementEntity entity, DataScope scope) {
        if (RequirementActionMatrix.can("deliver", entity.getReporterId(), callerOf(scope),
                actionPermissions)) {
            return;
        }
        // 沿用发起码的语义：没有交付/发起档位就不能提单（F4）。
        throw new ApiException(403, RequirementErrors.FORBIDDEN, "无权提交工时评估",
                Map.of("required", "req:deliver"));
    }

    private RequirementActionMatrix.Caller callerOf(DataScope scope) {
        TenantContext.Principal principal = TenantContext.principal();
        return principal != null
                ? new RequirementActionMatrix.Caller(principal.userId(), principal.roles(),
                principal.vendorId())
                : new RequirementActionMatrix.Caller(scope.userId(), TenantContext.roles(), null);
    }
}
