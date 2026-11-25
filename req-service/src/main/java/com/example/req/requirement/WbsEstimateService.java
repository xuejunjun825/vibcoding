package com.example.req.requirement;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.ActionPermissionResolver;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.PermissionMatrix;
import com.example.platform.contract.RequirementActionMatrix;
import com.example.req.pdr.PdrReviewService;
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
    /**
     * PDR 顺序门（r41）。setter 注入而不是构造器参数：既有单测都按十参构造本类，加一个参数会让
     * 十几个测试同时编不过；未注入时门直接放行（那些单测只验评估语义，不验评审门）。
     */
    private PdrReviewService pdrGate;

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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPdrGate(PdrReviewService pdrGate) {
        this.pdrGate = pdrGate;
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
        if (approved != null && !voidedByVendorChange(approved, entity)) {
            BigDecimal approvedDays = EstimateSnapshot.days(
                    EstimateSnapshot.document(store, json, approved.getId()));
            BigDecimal deviation = deviation(estimateDays, approvedDays);
            // 人天未变，或变化仍在 ±20% 内：已有有效单据，不重复建单（AC-5）。
            if (deviation == null || deviation.compareTo(STALE_THRESHOLD) <= 0) {
                throw RequirementErrors.estimateAlreadyApproved(requirementId, approved.getId());
            }
        }

        // r41 顺序：权限（403）→ 表单/状态校验（400：类型、终态、人天、说明）→ 与既有单据的重复
        // （409 ESTIMATE_ALREADY_APPROVED）→ PDR 评审门（409 PDR_REVIEW_REQUIRED）→ 厂商门
        // （409 VENDOR_NOT_ASSIGNED / 403 OUT_OF_SCOPE）。把两个新门放在「表单对不对」之后，
        // 是为了不改变 r26 已定的 400 优先级：人天填错仍报 400，而不是先报「还没评审」。
        // 两个新门之间评审优先于厂商归属——「评都没评」比「给了谁」更根本。
        requirePdrPassed(entity);
        requireAssignedVendor(entity, scope);

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
        // r41：评估单自己记住「提交时承接厂商是谁」。换厂商后单据不被改写（它记录的是历史事实），
        // 失效由「单据厂商 ≠ 当前承接厂商」派生，因此不需要在换厂商时批量改流水。
        attributes.put("vendorId", entity.getAssigneeVendorId());
        attributes.put("participantKind", entity.getAssigneeVendorId() == null ? "INTERNAL" : "VENDOR");
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
        // 单据上的厂商是「提交时是谁」，需求上的厂商是「现在是谁」。两者不一致即 STALE（r41）：
        // 不改单据、不批量刷流水，避免历史被改写。
        Long documentVendorId = EstimateSnapshot.vendorId(document);
        String reason = reasonOf(instance, status, entity, approvedDays, documentVendorId);
        String reasonText = reasonTextOf(instance, status, entity, approvedDays, documentVendorId,
                reason);
        Long vendorId = documentVendorId != null ? documentVendorId : entity.getAssigneeVendorId();
        return new WbsEstimateViews.EstimateView(status, instance.getId(), instance.getStatus(),
                proposedDays, approvedDays, EstimateSnapshot.note(document), reason, reasonText,
                approverOf(instance.getId()), instance.getStartedAt(), instance.getEndedAt(),
                vendorId, vendorId == null ? null : requirements.vendorNameOf(vendorId),
                WbsEstimateViews.EstimateView.REQUIRED);
    }

    /** 批量派生的入参（r27）：一页需求 id + 类型 + 当前人天，不含需求实体本身。 */
    public record GateSubject(long requirementId, String type, BigDecimal estimateDays,
                              Long vendorId) {
    }

    /** 六态之一：NONE|PENDING|APPROVED|REJECTED|SUPERSEDED|STALE。 */
    public String statusOf(RequirementEntity entity) {
        if (entity == null || entity.getId() == null) {
            return STATUS_NONE;
        }
        if (activeInstance(entity).isPresent()) {
            return decide(true, null, entity.getEstimateDays(), null, null, null);
        }
        ApprovalInstance last = effectiveInstance(entity);
        Long documentVendorId = last == null || !isApproved(last)
                ? null
                : EstimateSnapshot.vendorId(EstimateSnapshot.document(store, json, last.getId()));
        return decide(false, last, entity.getEstimateDays(), approvedDaysOf(last),
                documentVendorId, entity.getAssigneeVendorId());
    }

    /**
     * 批量派生（r27）：一页需求一条 IN 取单据、至多一条 IN 取已通过单的 SUBMIT 快照，
     * 然后逐需求调用与 {@link #statusOf} 完全相同的 {@link #decide} 判定表。
     *
     * <p>只对受门类型（PDR/RD）取数：其余类型没有评估态，调用方拿到缺失即视为「无门」。
     * 分组保持仓储的倒序，因此 active/最新/WITHDRAWN 回落的选取规则与单条查询一致。</p>
     */
    public Map<Long, String> statusesOf(List<GateSubject> subjects) {
        if (subjects == null || subjects.isEmpty()) {
            return Map.of();
        }
        Map<Long, GateSubject> wanted = new LinkedHashMap<>();
        for (GateSubject subject : subjects) {
            if (subject != null && subject.requirementId() > 0 && gatedType(subject.type())) {
                wanted.putIfAbsent(subject.requirementId(), subject);
            }
        }
        if (wanted.isEmpty()) {
            return Map.of();
        }

        List<ApprovalInstance> instancesOfPage = instances
                .findAllByTenantIdAndBizTypeAndBizIdInOrderByIdDesc(
                        TenantContext.require().tenantId(), BIZ_TYPE, wanted.keySet());
        Map<Long, List<ApprovalInstance>> grouped = new LinkedHashMap<>();
        if (instancesOfPage != null) {
            for (ApprovalInstance instance : instancesOfPage) {
                if (instance == null || instance.getBizId() == null) {
                    continue;
                }
                grouped.computeIfAbsent(instance.getBizId(), key -> new ArrayList<>()).add(instance);
            }
        }

        Map<Long, ApprovalInstance> effectiveOfRow = new LinkedHashMap<>();
        Map<Long, Boolean> activeOfRow = new LinkedHashMap<>();
        List<Long> approvedIds = new ArrayList<>();
        for (Long requirementId : wanted.keySet()) {
            List<ApprovalInstance> ordered = grouped.getOrDefault(requirementId, List.of());
            boolean active = ordered.stream()
                    .anyMatch(instance -> ApprovalInstance.ACTIVE.equals(instance.getActiveFlag()));
            ApprovalInstance effective = effectiveIn(ordered);
            activeOfRow.put(requirementId, active);
            effectiveOfRow.put(requirementId, effective);
            if (!active && isApproved(effective)) {
                approvedIds.add(effective.getId());
            }
        }

        Map<Long, List<ApprovalHistory>> histories =
                approvedIds.isEmpty() ? Map.of() : store.histories(approvedIds);

        Map<Long, String> result = new LinkedHashMap<>();
        wanted.forEach((requirementId, subject) -> {
            ApprovalInstance effective = effectiveOfRow.get(requirementId);
            Map<String, Object> document = isApproved(effective)
                    ? EstimateSnapshot.document(histories.get(effective.getId()), json)
                    : Map.of();
            result.put(requirementId, decide(activeOfRow.get(requirementId), effective,
                    subject.estimateDays(), EstimateSnapshot.days(document),
                    EstimateSnapshot.vendorId(document), subject.vendorId()));
        });
        return result;
    }

    /**
     * 六态判定表（唯一一份，r27）：批量派生与详情取数都只调它。
     *
     * <p>{@code statusOf} 负责「怎么取数」，本方法负责「取到的数意味着什么」——两份实现分叉
     * 正是列表与详情不一致（列表恒给 {@code schedule}）的根因。</p>
     */
    private static String decide(boolean active, ApprovalInstance effective,
                                 BigDecimal estimateDays, BigDecimal approvedDays,
                                 Long documentVendorId, Long currentVendorId) {
        if (active) {
            return STATUS_PENDING;
        }
        if (effective == null) {
            return STATUS_NONE;
        }
        String status = effective.getStatus();
        if (ApprovalInstance.STATUS_APPROVED.equals(status)) {
            // r41：换过承接厂商的旧评估单直接失效（设计 R3）。比人天偏差优先——厂商都不对了，
            // 人天再准也没法用。
            if (documentVendorId != null && currentVendorId != null
                    && !documentVendorId.equals(currentVendorId)) {
                return STATUS_STALE;
            }
            // 与 impact 的预测共用 wouldBeStale（AC-9：预测=实际），避免两套 20% 阈值。
            if (approvedDays == null || approvedDays.signum() <= 0 || estimateDays == null) {
                return STATUS_STALE;
            }
            return wouldBeStale(approvedDays, estimateDays) ? STATUS_STALE : STATUS_APPROVED;
        }
        if (ApprovalInstance.STATUS_REJECTED.equals(status)) {
            return STATUS_REJECTED;
        }
        if (ApprovalInstance.STATUS_SUPERSEDED.equals(status)) {
            return STATUS_SUPERSEDED;
        }
        return STATUS_PENDING;
    }

    private static boolean isApproved(ApprovalInstance instance) {
        return instance != null && ApprovalInstance.STATUS_APPROVED.equals(instance.getStatus())
                && instance.getId() != null;
    }

    /** 与 {@link #effectiveInstance} 同规则，但作用在已批量取出的倒序单据列表上。 */
    private static ApprovalInstance effectiveIn(List<ApprovalInstance> ordered) {
        if (ordered == null || ordered.isEmpty()) {
            return null;
        }
        for (ApprovalInstance instance : ordered) {
            if (ApprovalInstance.ACTIVE.equals(instance.getActiveFlag())) {
                return instance;
            }
        }
        ApprovalInstance latest = ordered.get(0);
        if (!ApprovalInstance.STATUS_WITHDRAWN.equals(latest.getStatus())) {
            return latest;
        }
        for (ApprovalInstance instance : ordered) {
            if (!ApprovalInstance.STATUS_WITHDRAWN.equals(instance.getStatus())) {
                return instance;
            }
        }
        return null;
    }

    private BigDecimal approvedDaysOf(ApprovalInstance instance) {
        if (!isApproved(instance)) {
            return null;
        }
        return EstimateSnapshot.days(EstimateSnapshot.document(store, json, instance.getId()));
    }

    /**
     * 需求最近一次通过审批的评估人天；没有 APPROVED 评估时为 null。
     * 变更单 impact 的 {@code days.approvedDays} 与 20% 判定都以它为分母。
     */
    public BigDecimal approvedDaysOf(long requirementId) {
        long tenantId = TenantContext.require().tenantId();
        for (ApprovalInstance instance : instances.findAllByTenantIdAndBizTypeAndBizIdOrderByIdDesc(
                tenantId, BIZ_TYPE, requirementId)) {
            if (isApproved(instance)) {
                return approvedDaysOf(instance);
            }
        }
        return null;
    }

    /**
     * 变更后的人天是否会让「已批评估」转 STALE：未取绝对值、未舍入的严格 &gt;20%。
     * 与 {@link #decide} 共用本方法，AC-9 的「预测=实际」才成立。
     */
    public static boolean wouldBeStale(BigDecimal approvedDays, BigDecimal afterDays) {
        if (approvedDays == null || approvedDays.signum() <= 0 || afterDays == null) {
            return false;
        }
        return afterDays.subtract(approvedDays).abs()
                .compareTo(approvedDays.multiply(STALE_THRESHOLD)) > 0;
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
        if (entity == null || actions == null || actions.isEmpty()) {
            return actions;
        }
        return applyGate(entity.getType(), statusOf(entity), actions);
    }

    /**
     * 按「类型 + 已派生的评估态」过滤（r27）：批量路径的入口。
     *
     * <p>语义与 {@link #applyGate(RequirementEntity, List)} 完全一致，只是评估态由调用方
     * 一次批量算出；顺序保持不变，空结果返回不可变空表。</p>
     */
    public List<String> applyGate(String type, String estimateStatus, List<String> actions) {
        if (type == null || actions == null || actions.isEmpty() || !gatedType(type)) {
            return actions;
        }
        if (STATUS_APPROVED.equals(estimateStatus)) {
            return actions;
        }
        List<String> filtered = new ArrayList<>(actions.size());
        for (String action : actions) {
            if (!gated(type, action)) {
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

    /** STALE 的失效码（{@code reason} 字段）：门禁与客户端按码判定，不解析中文文案。 */
    public static final String STALE_VENDOR_CHANGED = "VENDOR_CHANGED";
    public static final String STALE_DAYS_MISSING = "DAYS_MISSING";
    public static final String STALE_DAYS_DEVIATION = "DAYS_DEVIATION";

    /**
     * 结论原因：{@code REJECTED} 是驳回评论（人写的，前端直接展示）；{@code STALE} 给稳定失效码
     * （{@code VENDOR_CHANGED|DAYS_MISSING|DAYS_DEVIATION}），与 {@link #reasonTextOf} 的人话配对。
     */
    private String reasonOf(ApprovalInstance instance, String status, RequirementEntity entity,
                            BigDecimal approvedDays, Long documentVendorId) {
        if (STATUS_REJECTED.equals(status)) {
            return lastRejectComment(instance.getId());
        }
        if (!STATUS_STALE.equals(status)) {
            return null;
        }
        if (vendorChanged(entity, documentVendorId)) {
            return STALE_VENDOR_CHANGED;
        }
        return deviation(entity.getEstimateDays(), approvedDays) == null
                ? STALE_DAYS_MISSING : STALE_DAYS_DEVIATION;
    }

    /** {@code reason} 的可读版本：STALE 解释「为什么失效、该怎么做」，其余状态与 reason 同值。 */
    private String reasonTextOf(ApprovalInstance instance, String status, RequirementEntity entity,
                                BigDecimal approvedDays, Long documentVendorId, String reason) {
        if (!STATUS_STALE.equals(status)) {
            return reason;
        }
        if (STALE_VENDOR_CHANGED.equals(reason)) {
            return "承接厂商已从厂商 " + documentVendorId + " 变更为厂商 "
                    + entity.getAssigneeVendorId() + "，需按新厂商重新提交工时评估";
        }
        if (STALE_DAYS_MISSING.equals(reason)) {
            return "评估后需求人天缺失，评估单已失效";
        }
        BigDecimal current = entity.getEstimateDays();
        BigDecimal deviated = deviation(current, approvedDays);
        return "人天偏差 " + deviated.multiply(new BigDecimal("100"))
                .setScale(1, RoundingMode.HALF_UP).toPlainString() + "%（评估 "
                + approvedDays.stripTrailingZeros().toPlainString() + " 天 → 当前 "
                + current.stripTrailingZeros().toPlainString() + " 天），超过 20% 阈值";
    }

    /** 单据提交时的厂商与需求当前承接厂商不一致 ⇒ 这张已通过的单据已作废（R3，STALE 的派生源）。 */
    private boolean voidedByVendorChange(ApprovalInstance instance, RequirementEntity entity) {
        if (instance == null || entity == null) {
            return false;
        }
        return vendorChanged(entity,
                EstimateSnapshot.vendorId(EstimateSnapshot.document(store, json, instance.getId())));
    }

    private static boolean vendorChanged(RequirementEntity entity, Long documentVendorId) {
        return documentVendorId != null && entity.getAssigneeVendorId() != null
                && !documentVendorId.equals(entity.getAssigneeVendorId());
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

    /**
     * 提交工时评估的写权（设计 §7「权限与动作码」、R4）。
     *
     * <p>两类账号、两条口径，缺一不可：</p>
     * <ul>
     *   <li><b>内部账号</b>（{@code vendorId == null}）沿用 r26 的 {@code req:deliver} 判定：
     *       PMO/研发能提，产品经理的 deliver 是 DENY，所以仍然 403（门禁 B2-2 就是这条）；</li>
     *   <li><b>厂商账号</b>改看动作码 {@code req.action.estimate_submit}：VENDOR_PM 在该码上是
     *       ALLOW、厂商研发经 {@code task:manage} 也是非 DENY。之前这里只查
     *       {@code req.action.deliver}，而 VENDOR_PM 的 deliver 是 DENY——「拿着评估权却被交付权
     *       挡住」，门禁 B2-4 因此 403。</li>
     * </ul>
     *
     * <p>为什么厂商分支是「非 DENY」而不是「== ALLOW」：RESTRICTED 在本平台表示「通过但收窄
     * 范围」，这里收窄由下一步 {@code requireAssignedVendor}（跨厂商 403 OUT_OF_SCOPE）执行。
     * 反过来，内部账号绝不能走这条分支：产品经理的 estimate_submit 是 RESTRICTED（继承自
     * task:manage），一旦让内部账号也看动作码，B2-2 的 403 就会变成 201。</p>
     */
    private void requireDeliverPermission(RequirementEntity entity, DataScope scope) {
        if (RequirementActionMatrix.can("deliver", entity.getReporterId(), callerOf(scope),
                actionPermissions)) {
            return;
        }
        if (scope.vendorId() != null
                && actionPermissions.decide(RequirementActionMatrix.PAGE,
                        RequirementActionMatrix.actionCode("estimate_submit"))
                != PermissionMatrix.Access.DENY) {
            return;
        }
        // 沿用发起码的语义：没有交付/发起档位就不能提单（F4）。
        throw new ApiException(403, RequirementErrors.FORBIDDEN, "无权提交工时评估",
                Map.of("required", "req:estimate_submit"));
    }

    // ------------------------------------------------------------ r41 顺序门与厂商绑定

    /**
     * PDR 顺序门（§3.2）：PDR 自己必须先有通过结论；RD 只在父 PDR 已进入过评审时才要求通过。
     * 未注入（单测）时整体放行——那些测试只验评估语义。
     */
    private void requirePdrPassed(RequirementEntity entity) {
        if (pdrGate == null) {
            return;
        }
        if (RequirementRules.TYPE_PDR.equals(entity.getType())) {
            pdrGate.gate(entity.getId()).ifPresent(gate -> {
                throw RequirementErrors.pdrReviewRequired(entity.getId(), gate.reviewId(),
                        gate.status());
            });
            return;
        }
        if (entity.getParentId() == null) {
            return;
        }
        RequirementEntity parent = requirements.requireVisible(entity.getParentId());
        if (!RequirementRules.TYPE_PDR.equals(parent.getType())) {
            return;
        }
        pdrGate.gateIfStarted(parent.getId()).ifPresent(gate -> {
            throw RequirementErrors.pdrReviewRequired(entity.getId(), gate.reviewId(),
                    gate.status());
        });
    }

    /** 厂商门（A1/A5/R4）：必须先指派厂商；厂商账号只能评承接厂商是自己的需求。 */
    private void requireAssignedVendor(RequirementEntity entity, DataScope scope) {
        Long assigned = entity.getAssigneeVendorId();
        if (assigned == null || assigned <= 0) {
            throw RequirementErrors.vendorNotAssigned(entity.getId());
        }
        Long callerVendor = callerOf(scope).vendorId();
        if (callerVendor != null && !callerVendor.equals(assigned)) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("requirementId", entity.getId());
            detail.put("vendorId", callerVendor);
            detail.put("assigneeVendorId", assigned);
            detail.put("required", "assign_vendor");
            throw new ApiException(403, RequirementErrors.OUT_OF_SCOPE,
                    "只能为承接厂商是自己厂商的需求提交工时评估", detail);
        }
    }

    /**
     * 换厂商后把已通过的评估单派生为 STALE（R3）。
     *
     * <p>STALE 本身是派生状态（单据厂商 ≠ 当前承接厂商即失效），所以这里不改单据状态，
     * 只写一条 {@code ESTIMATE_STALE_BY_VENDOR} 审计，让审计页能回答「这张单为什么失效」。</p>
     */
    void markStaleByVendor(RequirementEntity entity, Long previousVendorId, Long currentVendorId) {
        Optional<ApprovalInstance> approved = instances
                .findAllByTenantIdAndBizTypeAndBizIdOrderByIdDesc(
                        TenantContext.require().tenantId(), BIZ_TYPE, entity.getId()).stream()
                .filter(instance -> ApprovalInstance.STATUS_APPROVED.equals(instance.getStatus()))
                .findFirst();
        if (approved.isEmpty()) {
            return;
        }
        Long documentVendorId = EstimateSnapshot.vendorId(
                EstimateSnapshot.document(store, json, approved.get().getId()));
        if (documentVendorId != null && documentVendorId.equals(currentVendorId)) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("instanceId", approved.get().getId());
        detail.put("previousVendorId", previousVendorId);
        detail.put("currentVendorId", currentVendorId);
        auditService.record("ESTIMATE_STALE_BY_VENDOR", BIZ_TYPE, entity.getId(), detail);
    }

    private RequirementActionMatrix.Caller callerOf(DataScope scope) {
        TenantContext.Principal principal = TenantContext.principal();
        return principal != null
                ? new RequirementActionMatrix.Caller(principal.userId(), principal.roles(),
                principal.vendorId())
                : new RequirementActionMatrix.Caller(scope.userId(), TenantContext.roles(), null);
    }
}
