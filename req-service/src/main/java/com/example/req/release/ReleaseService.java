package com.example.req.release;

import com.example.platform.common.audit.ActualPeriodRecorder;
import com.example.platform.common.audit.AuditService;
import com.example.platform.common.code.CodeGenerator;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.ScopeTier;
import com.example.req.approval.ApprovalEngine;
import com.example.req.approval.ApprovalHistory;
import com.example.req.approval.ApprovalHistoryRepository;
import com.example.req.approval.ApprovalInstance;
import com.example.req.approval.ApprovalInstanceRepository;
import com.example.req.approval.ApprovalLevelProjector;
import com.example.req.approval.ApprovalTask;
import com.example.req.approval.ApprovalTaskRepository;
import com.example.req.approval.ApprovalViews;
import com.example.req.approval.StartApproval;
import com.example.req.approval.WorkflowDefinition;
import com.example.req.approval.WorkflowRegistry;
import com.example.req.requirement.RequirementEntity;
import com.example.req.requirement.RequirementErrors;
import com.example.req.requirement.RequirementRepository;
import com.example.req.requirement.RequirementStateMachine;
import com.example.req.requirement.RequirementViews;
import com.example.req.requirement.RequirementViews.MilestoneView;
import com.example.req.requirement.RequirementViews.Page;
import com.example.req.requirement.RequirementViews.ParentGroup;
import com.example.req.requirement.RequirementViews.ReleaseDetail;
import com.example.req.requirement.RequirementViews.ReleaseStats;
import com.example.req.requirement.RequirementViews.ReleaseView;
import com.example.req.requirement.RequirementViews.View;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 版本（release）计划：创建、列表、详情汇总、纳入/移出需求、锁版本、发起审批、里程碑。
 *
 * <p>AC-11 的三条硬约束在实现里各有落点：
 * 锁定后任何需求调整 → 409 RELEASE_LOCKED；详情按父需求分组聚合；统计与列表同源（同一批行算出来）。</p>
 */
@Service
public class ReleaseService {

    public static final String BIZ_TYPE = "RELEASE";
    public static final String STATUS_PLAN = "PLAN";
    public static final String STATUS_LOCKED = "LOCKED";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    /**
     * 列表/详情共用的行投影。r30 追加三列（等级 + 两个必填项）：
     * 版本行与等级同源，前端不必为「这一行是常规还是紧急」再发一次请求。
     */
    private static final String COLUMNS = """
            r.id AS id, r.code AS code, r.name AS name, r.domain_id AS domain_id, r.status AS status,
            r.start_date AS start_date, r.end_date AS end_date,
            r.planned_release_date AS planned_release_date, r.gate_mode AS gate_mode,
            r.locked_at AS locked_at, r.released_at AS released_at,
            r.release_level AS release_level, r.emergency_reason AS emergency_reason,
            r.previous_deploy_order_code AS previous_deploy_order_code
            """;

    private final ReleaseRepository releaseRepository;
    private final ReleaseRequirementRepository releaseRequirementRepository;
    private final MilestoneRepository milestoneRepository;
    private final RequirementRepository requirementRepository;
    private final RequirementStateMachine stateMachine;
    private final ScopedJdbc jdbc;
    private final CodeGenerator codeGenerator;
    private final AuditService auditService;
    private final ObjectProvider<ApprovalEngine> approvalEngine;
    private final ReleasePublishCascade publishCascade;
    /** r30：等级必填项校验（创建/改等级/发起审批三处共用）。 */
    private final ReleaseLevelValidator levelValidator;
    /** r30：6 槽绑定 → 该等级生效的 definitionId。 */
    private final FlowBindingService flowBindings;
    /** r30：四段链路聚合（deliver 事实 + 本地准入段），详情唯一来源。 */
    private final FlowChainService flowChains;
    /** r30：逐级审批投影（与发布单详情共用同一推导）。 */
    private final ApprovalLevelProjector approvalLevels;
    /** r30：发起审批后回 `{definitionId, definitionVersion, nodeKeys}` 需要读定义。 */
    private final WorkflowRegistry workflows;
    private final ApprovalInstanceRepository approvalInstances;
    private final ApprovalHistoryRepository approvalHistories;
    private final ApprovalTaskRepository approvalTasks;

    /**
     * release 的 r30 新列（等级/必填项/驳回五列）不在 {@link ReleaseEntity} 里——
     * 那个实体与它的迁移是既有资产，本需求的边界只到 ReleaseService。所以这些列走
     * {@link EntityManager} 原生 SQL 读写：{@link ScopedJdbc} 只读，而 release 包被 lint L9
     * 禁止直接持有 JDBC 模板（见 scripts/tests/lint.sh 的 allow_re 白名单）。
     */
    private final EntityManager entityManager;

    public ReleaseService(ReleaseRepository releaseRepository,
                          ReleaseRequirementRepository releaseRequirementRepository,
                          MilestoneRepository milestoneRepository,
                          RequirementRepository requirementRepository,
                          RequirementStateMachine stateMachine,
                          ScopedJdbc jdbc,
                          CodeGenerator codeGenerator,
                          AuditService auditService,
                          ObjectProvider<ApprovalEngine> approvalEngine,
                          ReleasePublishCascade publishCascade,
                          ReleaseLevelValidator levelValidator,
                          FlowBindingService flowBindings,
                          FlowChainService flowChains,
                          ApprovalLevelProjector approvalLevels,
                          WorkflowRegistry workflows,
                          ApprovalInstanceRepository approvalInstances,
                          ApprovalHistoryRepository approvalHistories,
                          ApprovalTaskRepository approvalTasks,
                          EntityManager entityManager) {
        this.releaseRepository = releaseRepository;
        this.releaseRequirementRepository = releaseRequirementRepository;
        this.milestoneRepository = milestoneRepository;
        this.requirementRepository = requirementRepository;
        this.stateMachine = stateMachine;
        this.jdbc = jdbc;
        this.codeGenerator = codeGenerator;
        this.auditService = auditService;
        this.approvalEngine = approvalEngine;
        this.publishCascade = publishCascade;
        this.levelValidator = levelValidator;
        this.flowBindings = flowBindings;
        this.flowChains = flowChains;
        this.approvalLevels = approvalLevels;
        this.workflows = workflows;
        this.approvalInstances = approvalInstances;
        this.approvalHistories = approvalHistories;
        this.approvalTasks = approvalTasks;
        this.entityManager = entityManager;
    }

    // ------------------------------------------------------------------ create

    @Transactional
    public ReleaseView create(CreateRelease cmd) {
        DataScope scope = TenantContext.require();
        if (cmd.name() == null || cmd.name().isBlank()) {
            throw RequirementErrors.validation("name 必填");
        }
        if (cmd.domainId() == null) {
            throw RequirementErrors.validation("domainId 必填");
        }
        requireDomainVisible(cmd.domainId(), scope);
        String gateMode = cmd.gateMode() == null || cmd.gateMode().isBlank()
                ? "STRICT" : cmd.gateMode().trim().toUpperCase();
        if (!List.of("STRICT", "LOOSE").contains(gateMode)) {
            throw RequirementErrors.validation("gateMode 只能是 STRICT/LOOSE");
        }
        if (cmd.startDate() != null && cmd.endDate() != null && cmd.endDate().isBefore(cmd.startDate())) {
            throw RequirementErrors.validation("endDate 不能早于 startDate");
        }
        // r30：等级与两个必填项在写库前校验（AC-5/AC-7），校验失败版本行不产生。
        ReleaseLevelValidator.Validated level = levelValidator.validate(scope.tenantId(),
                cmd.releaseLevel(), cmd.emergencyReason(), cmd.previousDeployOrderCode());

        ReleaseEntity entity = new ReleaseEntity();
        entity.setTenantId(scope.tenantId());
        entity.setCode(codeGenerator.next("REL", scope.tenantId()));
        entity.setName(cmd.name().trim());
        entity.setDomainId(cmd.domainId());
        entity.setStatus(STATUS_PLAN);
        entity.setStartDate(cmd.startDate());
        entity.setEndDate(cmd.endDate());
        entity.setPlannedReleaseDate(cmd.plannedReleaseDate());
        entity.setGateMode(gateMode);
        entity.setCreatedBy(scope.userId());
        entity.setUpdatedBy(scope.userId());
        ReleaseEntity saved = releaseRepository.save(entity);
        // 等级列是原生 UPDATE 写的，必须先落 INSERT 再改同一行。
        releaseRepository.flush();
        writeLevel(saved.getId(), scope.tenantId(), level);
        auditService.record("CREATE", BIZ_TYPE, saved.getId(), Map.of(
                "code", saved.getCode(), "domainId", cmd.domainId(), "gateMode", gateMode,
                "releaseLevel", level.level()));
        return toView(saved, flowState(scope.tenantId(), saved.getId()));
    }

    // ------------------------------------------------------------------- query

    @Transactional(readOnly = true)
    public Page<ReleaseView> page(Long domainId, String status, int page, int size) {
        DataScope scope = TenantContext.require();
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        requireDomainVisible(domainId, scope);
        Map<String, Object> params = new HashMap<>();
        params.put("domainId", domainId);
        params.put("status", status == null || status.isBlank() ? null : status.trim().toUpperCase());
        String where = " 1 = 1 AND /*scope:r*/"
                + " AND (:domainId IS NULL OR r.domain_id = :domainId)"
                + " AND (:status IS NULL OR r.status = :status)";
        long total = jdbc.count("SELECT COUNT(*) FROM `release` r WHERE" + where, params);
        if (total == 0 || (long) (safePage - 1) * safeSize >= total) {
            return new Page<>(List.of(), total, safePage, safeSize);
        }
        params.put("limit", safeSize);
        params.put("offset", (safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.list("SELECT " + COLUMNS + " FROM `release` r WHERE" + where
                + " ORDER BY r.id DESC LIMIT :limit OFFSET :offset", params);
        return new Page<>(rows.stream().map(this::toView).toList(), total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public ReleaseDetail detail(long id) {
        ReleaseEntity release = requireVisible(id);
        List<View> requirements = requirementsOf(release);
        List<MilestoneView> milestones = milestoneRepository
                .findByTenantIdAndReleaseIdOrderByPlannedDateAsc(release.getTenantId(), id)
                .stream().map(m -> new MilestoneView(m.getId(), m.getName(), m.getPlannedDate(), m.getStatus()))
                .toList();
        // r30：AC-8 要求「一次 GET 拿全」。四段链路只由 T2 的 FlowChainService 聚合（此处不拼段），
        // 逐级审批只由 ApprovalLevelProjector 投影（版本与发布单共用），驳回痕迹读本单五列。
        ApprovalLevelProjector.Mount mount = approvalLevels.mount(release.getTenantId(), BIZ_TYPE, id);
        FlowChainViews.FlowChain chain = flowChains.assemble(id);
        FlowState state = flowState(release.getTenantId(), id);
        return new ReleaseDetail(toView(release, state), requirements, groups(requirements),
                stats(requirements, release), riskItems(requirements, release), milestones,
                mount.activeInstanceId(), mount.levels(), chain,
                state.rejectedNodeKey(), state.rejectedNodeName(), state.rejectedBy(),
                state.rejectedAt(), state.rejectReason());
    }

    // ---------------------------------------------------------------- membership

    @Transactional
    public ReleaseDetail addRequirements(long releaseId, List<Long> requirementIds) {
        DataScope scope = TenantContext.require();
        ReleaseEntity release = requireVisible(releaseId);
        requireEditable(release);
        if (requirementIds == null || requirementIds.isEmpty()) {
            throw RequirementErrors.validation("requirementIds 不能为空");
        }
        List<Map<String, Object>> added = new ArrayList<>();
        for (Long requirementId : new LinkedHashSet<>(requirementIds)) {
            if (requirementId == null) {
                continue;
            }
            RequirementEntity requirement = requirementRepository
                    .findByIdAndTenantId(requirementId, scope.tenantId())
                    .orElseThrow(() -> RequirementErrors.notFound("需求", requirementId));
            if (requirement.getReleaseId() != null && !Objects.equals(requirement.getReleaseId(), releaseId)) {
                throw RequirementErrors.validation("需求 " + requirement.getCode() + " 已属于其他版本")
                        ;
            }
            if (!releaseRequirementRepository.existsByTenantIdAndReleaseIdAndRequirementId(
                    scope.tenantId(), releaseId, requirementId)) {
                ReleaseRequirementEntity rel = new ReleaseRequirementEntity();
                rel.setReleaseId(releaseId);
                rel.setRequirementId(requirementId);
                rel.setTenantId(scope.tenantId());
                rel.setAddedBy(scope.userId());
                releaseRequirementRepository.save(rel);
            }
            long previousReleaseId = requirement.getReleaseId() == null ? 0L : requirement.getReleaseId();
            requirement.setReleaseId(releaseId);
            requirement.setUpdatedBy(scope.userId());
            requirementRepository.save(requirement);
            // changed[] 让事件层按「每条需求一条 FIELD_CHANGE」展开（r39 §3.2）：只在版本真的
            // 变了时才记，重复加入同版本不产生事件。
            if (previousReleaseId != releaseId) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", requirementId);
                entry.put("before", previousReleaseId == 0L ? null : previousReleaseId);
                entry.put("after", releaseId);
                added.add(entry);
            }
        }
        auditService.record("RELEASE_MEMBER_ADD", BIZ_TYPE, releaseId, Map.of(
                "requirementIds", requirementIds, "count", requirementIds.size(),
                "changed", added));
        return detail(releaseId);
    }

    @Transactional
    public void removeRequirement(long releaseId, long requirementId) {
        DataScope scope = TenantContext.require();
        ReleaseEntity release = requireVisible(releaseId);
        requireEditable(release);
        releaseRequirementRepository.deleteByTenantIdAndReleaseIdAndRequirementId(
                scope.tenantId(), releaseId, requirementId);
        List<Map<String, Object>> changed = new ArrayList<>();
        requirementRepository.findByIdAndTenantId(requirementId, scope.tenantId())
                .filter(requirement -> Objects.equals(requirement.getReleaseId(), releaseId))
                .ifPresent(requirement -> {
                    requirement.setReleaseId(null);
                    requirement.setUpdatedBy(scope.userId());
                    requirementRepository.save(requirement);
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", requirementId);
                    entry.put("before", releaseId);
                    entry.put("after", null);
                    changed.add(entry);
                });
        auditService.record("RELEASE_MEMBER_REMOVE", BIZ_TYPE, releaseId, Map.of(
                "requirementId", requirementId, "changed", changed));
    }

    // ------------------------------------------------------------------- lock

    @Transactional
    public ReleaseView lock(long id) {
        DataScope scope = TenantContext.require();
        ReleaseEntity release = requireVisible(id);
        if (STATUS_LOCKED.equals(release.getStatus())) {
            return toView(release);
        }
        if (!STATUS_PLAN.equals(release.getStatus())) {
            throw RequirementErrors.releaseLocked(id);
        }
        release.setStatus(STATUS_LOCKED);
        release.setLockedAt(LocalDateTime.now());
        release.setUpdatedBy(scope.userId());
        releaseRepository.save(release);
        auditService.record("RELEASE_LOCK", BIZ_TYPE, id, Map.of("status", STATUS_LOCKED));
        return toView(release);
    }

    // --------------------------------------------------------------- approval

    /**
     * 发起审批：等级槽位 → definitionId → 既有引擎（执行语义一点不动）。
     *
     * <p>发起前按落库值再校验一次（AC-5「两处校验」的第二处）：等级可能在创建后被 PATCH 改过，
     * 而紧急原因/回滚单号的有效性会随时间变化（回滚源单据可能被改状态）。</p>
     */
    @Transactional
    public StartResult startApproval(long id) {
        DataScope scope = TenantContext.require();
        ReleaseEntity release = requireVisible(id);
        if (STATUS_PUBLISHED.equals(release.getStatus()) || STATUS_CANCELLED.equals(release.getStatus())) {
            throw RequirementErrors.validation("当前版本状态不能发起审批：" + release.getStatus());
        }
        requireEditable(release);
        ApprovalEngine engine = approvalEngine.getIfAvailable();
        if (engine == null) {
            throw RequirementErrors.validation("审批引擎未就绪");
        }
        FlowState state = flowState(scope.tenantId(), id);
        ReleaseLevelValidator.Validated level = levelValidator.validate(scope.tenantId(),
                state.releaseLevel(), state.emergencyReason(), state.previousDeployOrderCode());
        // 等级 → 槽位 → definitionId：绑定不存在时回退默认链，仍解析不到即 409 WORKFLOW_NOT_CONFIGURED。
        long definitionId = flowBindings.requireDefinitionId(scope.tenantId(), BIZ_TYPE, level.level());
        WorkflowRegistry.ResolvedWorkflow resolved = workflows.byId(definitionId);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("domainId", release.getDomainId());
        attributes.put("total", requirementsOf(release).size());
        attributes.put("gateMode", release.getGateMode());
        attributes.put("releaseLevel", level.level());
        if (level.emergencyReason() != null) {
            attributes.put("emergencyReason", level.emergencyReason());
        }
        if (level.previousDeployOrderCode() != null) {
            attributes.put("previousDeployOrderCode", level.previousDeployOrderCode());
        }
        long instanceId = engine.start(new StartApproval(BIZ_TYPE, id, null, release.getDomainId(),
                attributes, definitionId, null)).id();
        release.setStatus(STATUS_LOCKED);
        release.setLockedAt(LocalDateTime.now());
        release.setUpdatedBy(scope.userId());
        releaseRepository.save(release);
        // 新的提交意图：上一轮的驳回痕迹不再代表本次提交。
        clearRejection(id, scope.tenantId());
        List<String> nodeKeys = resolved == null ? List.of() : resolved.definition().nodes().stream()
                .filter(WorkflowDefinition.Node::isApproval)
                .map(WorkflowDefinition.Node::key)
                .toList();
        auditService.record("RELEASE_APPROVAL_START", BIZ_TYPE, id, Map.of(
                "instanceId", instanceId, "status", STATUS_LOCKED,
                "releaseLevel", level.level(), "definitionId", definitionId));
        return new StartResult(instanceId, id, definitionId,
                resolved == null ? null : resolved.version(), nodeKeys);
    }

    /** 改等级（PATCH /releases/{id}/level）：只允许编辑窗口内，写前校验，审计带 before/after。 */
    @Transactional
    public ReleaseView updateLevel(long id, UpdateLevel cmd) {
        DataScope scope = TenantContext.require();
        ReleaseEntity release = requireVisible(id);
        FlowState before = flowState(scope.tenantId(), id);
        if (!STATUS_PLAN.equals(release.getStatus()) || release.getLockedAt() != null
                || approvalInstances.findFirstByTenantIdAndBizTypeAndBizIdAndActiveFlag(
                        scope.tenantId(), BIZ_TYPE, id, ApprovalInstance.ACTIVE).isPresent()) {
            throw RequirementErrors.releaseLocked(id);
        }
        ReleaseLevelValidator.Validated after = levelValidator.validate(scope.tenantId(),
                cmd.releaseLevel(), cmd.emergencyReason(), cmd.previousDeployOrderCode());
        writeLevel(id, scope.tenantId(), after);
        auditService.record("RELEASE_LEVEL_UPDATE", BIZ_TYPE, id, Map.of(
                "before", before.levelMap(), "after", after.asMap()));
        return toView(release);
    }

    // ---------------------------------------------------------------- publish

    /**
     * 审批通过后的发布：版本 LOCKED → PUBLISHED，并按 §5.6/D5 级联需求状态。
     *
     * <p>幂等：已 PUBLISHED 直接返回，不重复级联（审批引擎可能重放回调）。</p>
     */
    @Transactional
    public ReleaseView publish(long id) {
        DataScope scope = TenantContext.require();
        ReleaseEntity release = requireVisible(id);
        if (STATUS_PUBLISHED.equals(release.getStatus())) {
            return toView(release);
        }
        if (!STATUS_LOCKED.equals(release.getStatus())) {
            throw RequirementErrors.validation("只有已锁定的版本可以发布，当前：" + release.getStatus());
        }
        release.setStatus(STATUS_PUBLISHED);
        release.setReleasedAt(LocalDateTime.now());
        release.setUpdatedBy(scope.userId());
        releaseRepository.save(release);
        // 实际期由单一写入点维护（与 V9 回填同口径）：发布是 RELEASE 唯一会开启实际期的流转，
        // 漏掉这一步，甘特发布视图的浅色实际条就永远缺失（D-01）。write-once 由 SQL 的
        // actual_start_date IS NULL 保证，审批回调重放不会覆盖首个日期。
        ActualPeriodRecorder.record(ActualPeriodRecorder.RELEASE, id, STATUS_LOCKED, STATUS_PUBLISHED);

        ReleasePublishCascade.Result result = publishCascade.cascade(release);
        auditService.record("RELEASE_PUBLISH", BIZ_TYPE, id, Map.of(
                "status", STATUS_PUBLISHED,
                "rdClosed", result.rdClosed(),
                "pdrDelivered", result.pdrDelivered(),
                "releasedAt", String.valueOf(release.getReleasedAt())));
        return toView(release);
    }

    /**
     * 审批驳回/撤回：单据回到 PLAN，保留成员与里程碑（D3：不回退需求状态）。
     *
     * <p>r30 追加：把「卡在哪一级、谁驳回的、为什么」落到 release 五列，重提时清空——
     * 这样详情页不必再猜最近的驳回属于哪一轮。</p>
     */
    @Transactional
    public ReleaseView onApprovalRejected(long id, String reason) {
        DataScope scope = TenantContext.require();
        ReleaseEntity release = requireVisible(id);
        if (STATUS_PUBLISHED.equals(release.getStatus()) || STATUS_CANCELLED.equals(release.getStatus())) {
            return toView(release);
        }
        release.setStatus(STATUS_PLAN);
        release.setLockedAt(null);
        release.setUpdatedBy(scope.userId());
        releaseRepository.save(release);
        writeRejection(id, scope.tenantId(), reason);
        auditService.record("RELEASE_APPROVAL_REJECT", BIZ_TYPE, id, Map.of(
                "status", STATUS_PLAN, "reason", Objects.requireNonNullElse(reason, "")));
        return toView(release);
    }

    // -------------------------------------------------------------- milestones

    @Transactional
    public MilestoneView addMilestone(long releaseId, String name, LocalDate plannedDate) {
        DataScope scope = TenantContext.require();
        ReleaseEntity release = requireVisible(releaseId);
        if (STATUS_PUBLISHED.equals(release.getStatus()) || STATUS_CANCELLED.equals(release.getStatus())) {
            throw RequirementErrors.releaseLocked(releaseId);
        }
        if (name == null || name.isBlank()) {
            throw RequirementErrors.validation("name 必填");
        }
        MilestoneEntity milestone = new MilestoneEntity();
        milestone.setTenantId(scope.tenantId());
        milestone.setReleaseId(releaseId);
        milestone.setName(name.trim());
        milestone.setPlannedDate(plannedDate);
        milestone.setStatus("PLANNED");
        milestone.setCreatedBy(scope.userId());
        milestone.setUpdatedBy(scope.userId());
        MilestoneEntity saved = milestoneRepository.save(milestone);
        auditService.record("RELEASE_MILESTONE_ADD", BIZ_TYPE, releaseId, Map.of(
                "milestoneId", saved.getId(), "name", saved.getName()));
        return new MilestoneView(saved.getId(), saved.getName(), saved.getPlannedDate(), saved.getStatus());
    }

    // ----------------------------------------------------------------- helpers

    public ReleaseEntity requireVisible(long id) {
        DataScope scope = TenantContext.require();
        ReleaseEntity release = releaseRepository.findByIdAndTenantId(id, scope.tenantId())
                .orElseThrow(() -> RequirementErrors.notFound("版本", id));
        requireDomainVisible(release.getDomainId(), scope);
        requireVendorVisible(release, scope);
        return release;
    }

    /**
     * Vendor-tier visibility for a release, decided by the very same predicate the list uses.
     *
     * <p>{@code release} carries no vendor column, so {@link com.example.platform.common.scope.ScopeSql}
     * derives one ({@code EXISTS} over the release's requirements). Asking {@code ScopedJdbc} with the
     * marker instead of hand-writing a second count keeps list and detail on one SQL text: a version
     * that cannot appear in {@code page()} cannot be read or written through here either, which is
     * what closes approval / lock / membership / quality-settings in a single place.</p>
     */
    private void requireVendorVisible(ReleaseEntity release, DataScope scope) {
        if (scope.tier() != ScopeTier.VENDOR || scope.vendorId() == null) {
            return;
        }
        long visible = jdbc.count("SELECT COUNT(*) FROM `release` r WHERE r.id = :id AND /*scope:r*/",
                Map.of("id", release.getId()));
        if (visible == 0) {
            auditOrSkip(release.getDomainId(), scope);
            throw RequirementErrors.outOfScope("版本", release.getDomainId());
        }
    }

    private void requireDomainVisible(Long domainId, DataScope scope) {
        if (domainId == null) {
            return;
        }
        if (scope.domainIds() != null && !scope.domainIds().isEmpty()
                && !scope.domainIds().contains(domainId)) {
            auditOrSkip(domainId, scope);
            throw RequirementErrors.outOfScope("版本", domainId);
        }
    }

    private void auditOrSkip(Long domainId, DataScope scope) {
        try {
            auditService.recordInNewTx("PERMISSION_DENY", BIZ_TYPE, domainId,
                    Map.of("userId", scope.userId(), "domainId", domainId));
        } catch (RuntimeException ignored) {
            // 审计失败不掩盖 403
        }
    }

    private void requireEditable(ReleaseEntity release) {
        if (!STATUS_PLAN.equals(release.getStatus())) {
            throw RequirementErrors.releaseLocked(release.getId());
        }
    }

    private List<View> requirementsOf(ReleaseEntity release) {
        List<Map<String, Object>> rows = jdbc.list("""
                SELECT r.id AS id, r.code AS code, r.type AS type, r.status AS status,
                       r.priority AS priority, r.title AS title, r.description AS description,
                       r.parent_id AS parent_id, r.domain_id AS domain_id,
                       r.owner_user_id AS owner_user_id, r.assignee_vendor_id AS assignee_vendor_id,
                       r.release_id AS release_id, r.estimate_days AS estimate_days,
                       r.actual_days AS actual_days, r.mr_url AS mr_url,
                       r.expected_delivery_date AS expected_delivery_date,
                       r.plan_start_date AS plan_start_date, r.plan_end_date AS plan_end_date,
                       r.closed_at AS closed_at, r.updated_at AS updated_at
                FROM release_requirement rr
                JOIN requirement r ON r.id = rr.requirement_id AND r.tenant_id = rr.tenant_id
                WHERE rr.tenant_id = :tenantId AND rr.release_id = :releaseId AND /*scope:r*/
                ORDER BY r.type, r.id
                """, Map.of("tenantId", release.getTenantId(), "releaseId", release.getId()));
        return rows.stream().map(row -> toRequirementView(row, release)).toList();
    }

    private View toRequirementView(Map<String, Object> row, ReleaseEntity release) {
        Long parentId = com.example.req.requirement.Rows.toLong(
                com.example.req.requirement.Rows.object(row, "parent_id"));
        String parentCode = parentId == null ? null : requirementRepository
                .findByIdAndTenantId(parentId, release.getTenantId())
                .map(RequirementEntity::getCode).orElse(null);
        String type = com.example.req.requirement.Rows.str(row, "type");
        String status = com.example.req.requirement.Rows.str(row, "status");
        List<String> actions = new ArrayList<>(stateMachine.allowedActions(type, status));
        return new View(
                com.example.req.requirement.Rows.toLong(com.example.req.requirement.Rows.object(row, "id")),
                com.example.req.requirement.Rows.str(row, "code"), type, status,
                com.example.req.requirement.Rows.str(row, "priority"),
                com.example.req.requirement.Rows.str(row, "title"),
                com.example.req.requirement.Rows.str(row, "description"),
                parentId, parentCode,
                com.example.req.requirement.Rows.toLong(com.example.req.requirement.Rows.object(row, "domain_id")),
                null,
                com.example.req.requirement.Rows.toLong(com.example.req.requirement.Rows.object(row, "owner_user_id")),
                null,
                com.example.req.requirement.Rows.toLong(
                        com.example.req.requirement.Rows.object(row, "assignee_vendor_id")),
                com.example.req.requirement.Rows.toLong(com.example.req.requirement.Rows.object(row, "release_id")),
                com.example.req.requirement.Rows.toDecimal(
                        com.example.req.requirement.Rows.object(row, "estimate_days")),
                com.example.req.requirement.Rows.toDecimal(
                        com.example.req.requirement.Rows.object(row, "actual_days")),
                com.example.req.requirement.Rows.str(row, "mr_url"),
                com.example.req.requirement.Rows.toDate(
                        com.example.req.requirement.Rows.object(row, "expected_delivery_date")),
                com.example.req.requirement.Rows.toDate(
                        com.example.req.requirement.Rows.object(row, "plan_start_date")),
                com.example.req.requirement.Rows.toDate(
                        com.example.req.requirement.Rows.object(row, "plan_end_date")),
                com.example.req.requirement.Rows.toDateTime(
                        com.example.req.requirement.Rows.object(row, "closed_at")),
                com.example.req.requirement.Rows.toDateTime(
                        com.example.req.requirement.Rows.object(row, "updated_at")),
                null, actions, Map.of(), null);
    }

    private List<ParentGroup> groups(List<View> requirements) {
        Map<Long, List<View>> byParent = new LinkedHashMap<>();
        for (View view : requirements) {
            byParent.computeIfAbsent(view.parentId(), key -> new ArrayList<>()).add(view);
        }
        List<ParentGroup> groups = new ArrayList<>();
        byParent.forEach((parentId, children) -> {
            String parentCode = null;
            String parentTitle = null;
            if (parentId != null) {
                RequirementEntity parent = requirementRepository.findById(parentId).orElse(null);
                parentCode = parent == null ? null : parent.getCode();
                parentTitle = parent == null ? null : parent.getTitle();
            }
            long done = children.stream().filter(child ->
                    stateMachine.isTerminal(child.type(), child.status())).count();
            groups.add(new ParentGroup(parentId, parentCode, parentTitle, children.size(), done));
        });
        return groups;
    }

    private ReleaseStats stats(List<View> requirements, ReleaseEntity release) {
        long done = requirements.stream().filter(view ->
                stateMachine.isTerminal(view.type(), view.status())).count();
        BigDecimal estimate = requirements.stream().map(view ->
                view.estimateDays() == null ? BigDecimal.ZERO : view.estimateDays())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal actual = requirements.stream().map(view ->
                view.actualDays() == null ? BigDecimal.ZERO : view.actualDays())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ReleaseStats(requirements.size(), done,
                com.example.req.requirement.RequirementRollup.scale(estimate),
                com.example.req.requirement.RequirementRollup.scale(actual));
    }

    private List<View> riskItems(List<View> requirements, ReleaseEntity release) {
        LocalDate today = LocalDate.now();
        return requirements.stream()
                .filter(view -> view.planEndDate() != null && view.planEndDate().isBefore(today))
                .filter(view -> !stateMachine.isTerminal(view.type(), view.status()))
                .toList();
    }

    private ReleaseView toView(ReleaseEntity entity) {
        return toView(entity, flowState(entity.getTenantId(), entity.getId()));
    }

    private ReleaseView toView(ReleaseEntity entity, FlowState state) {
        return new ReleaseView(entity.getId(), entity.getCode(), entity.getName(), entity.getDomainId(),
                entity.getStatus(), entity.getStartDate(), entity.getEndDate(),
                entity.getPlannedReleaseDate(), entity.getGateMode(), entity.getLockedAt(),
                entity.getReleasedAt(), actionsOf(entity.getStatus()),
                state.releaseLevel(), ReleaseLevel.label(state.releaseLevel()),
                state.emergencyReason(), state.previousDeployOrderCode());
    }

    private List<String> actionsOf(String status) {
        return switch (status == null ? "" : status) {
            case STATUS_PLAN -> List.of("lock", "add_requirements", "approval", "milestone");
            case STATUS_LOCKED -> List.of("publish", "milestone");
            default -> List.of();
        };
    }

    private ReleaseView toView(Map<String, Object> row) {
        String status = com.example.req.requirement.Rows.str(row, "status");
        List<String> actions = switch (status == null ? "" : status) {
            case STATUS_PLAN -> List.of("lock", "add_requirements", "approval", "milestone");
            case STATUS_LOCKED -> List.of("publish", "milestone");
            default -> List.of();
        };
        return new ReleaseView(
                com.example.req.requirement.Rows.toLong(com.example.req.requirement.Rows.object(row, "id")),
                com.example.req.requirement.Rows.str(row, "code"),
                com.example.req.requirement.Rows.str(row, "name"),
                com.example.req.requirement.Rows.toLong(com.example.req.requirement.Rows.object(row, "domain_id")),
                status,
                com.example.req.requirement.Rows.toDate(com.example.req.requirement.Rows.object(row, "start_date")),
                com.example.req.requirement.Rows.toDate(com.example.req.requirement.Rows.object(row, "end_date")),
                com.example.req.requirement.Rows.toDate(
                        com.example.req.requirement.Rows.object(row, "planned_release_date")),
                com.example.req.requirement.Rows.str(row, "gate_mode"),
                com.example.req.requirement.Rows.toDateTime(
                        com.example.req.requirement.Rows.object(row, "locked_at")),
                com.example.req.requirement.Rows.toDateTime(
                        com.example.req.requirement.Rows.object(row, "released_at")),
                actions,
                levelOf(row),
                ReleaseLevel.label(levelOf(row)),
                com.example.req.requirement.Rows.str(row, "emergency_reason"),
                com.example.req.requirement.Rows.str(row, "previous_deploy_order_code"));
    }

    private static String levelOf(Map<String, Object> row) {
        return ReleaseLevel.normalise(com.example.req.requirement.Rows.str(row, "release_level"));
    }

    // ------------------------------------------------------------ r30 flow state

    /**
     * release 行的 r30 新列。{@link com.example.req.release.ReleaseEntity} 不承载这些列
     * （既有实体与迁移不改），因此用原生 SQL 一读一写；读写都显式带 tenant_id，
     * 与 {@link ScopedJdbc} 的租户隔离同口径。
     */
    private FlowState flowState(long tenantId, long releaseId) {
        List<?> rows = entityManager.createNativeQuery("""
                        SELECT release_level, emergency_reason, previous_deploy_order_code,
                               rejected_node_key, rejected_node_name, rejected_by, rejected_at, reject_reason
                        FROM `release` WHERE tenant_id = :tenantId AND id = :id
                        """)
                .setParameter("tenantId", tenantId)
                .setParameter("id", releaseId)
                .getResultList();
        if (rows.isEmpty()) {
            return new FlowState(ReleaseLevel.NORMAL, null, null, null, null, null, null, null);
        }
        Object[] row = (Object[]) rows.getFirst();
        return new FlowState(ReleaseLevel.normalise(str(row[0])), str(row[1]), str(row[2]),
                str(row[3]), str(row[4]), num(row[5]), time(row[6]), str(row[7]));
    }

    private void writeLevel(long releaseId, long tenantId, ReleaseLevelValidator.Validated level) {
        entityManager.createNativeQuery("""
                        UPDATE `release`
                        SET release_level = :level, emergency_reason = :reason,
                            previous_deploy_order_code = :previous
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .setParameter("level", level.level())
                .setParameter("reason", level.emergencyReason())
                .setParameter("previous", level.previousDeployOrderCode())
                .setParameter("tenantId", tenantId)
                .setParameter("id", releaseId)
                .executeUpdate();
    }

    private void clearRejection(long releaseId, long tenantId) {
        entityManager.createNativeQuery("""
                        UPDATE `release`
                        SET rejected_node_key = NULL, rejected_node_name = NULL, rejected_by = NULL,
                            rejected_at = NULL, reject_reason = NULL
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .setParameter("tenantId", tenantId)
                .setParameter("id", releaseId)
                .executeUpdate();
    }

    /**
     * 驳回痕迹回填：卡在哪一级从投影里取（与详情同一份推导），谁/何时/为什么取最后一条 REJECT 留痕。
     * 没有实例或没有 REJECT 历史时保持 NULL——撤回也要能解释，所以 reason 优先用回调带来的文本。
     */
    private void writeRejection(long releaseId, long tenantId, String reason) {
        ApprovalInstance instance = approvalInstances
                .findFirstByTenantIdAndBizTypeAndBizIdOrderByIdDesc(tenantId, BIZ_TYPE, releaseId)
                .orElse(null);
        if (instance == null) {
            return;
        }
        ApprovalHistory rejected = null;
        for (ApprovalHistory history : approvalHistories.findByInstanceIdOrderByIdAsc(instance.getId())) {
            if (ApprovalHistory.REJECT.equals(history.getAction())) {
                rejected = history;
            }
        }
        String nodeKey = null;
        String nodeName = null;
        for (FlowChainViews.ApprovalLevel level : approvalLevels.project(instance.getId())) {
            if (FlowChainViews.LEVEL_REJECTED.equals(level.status())) {
                nodeKey = level.nodeKey();
                nodeName = level.nodeName();
            }
        }
        String comment = reason == null || reason.isBlank()
                ? (rejected == null ? null : rejected.getComment())
                : reason;
        entityManager.createNativeQuery("""
                        UPDATE `release`
                        SET rejected_node_key = :nodeKey, rejected_node_name = :nodeName,
                            rejected_by = :by, rejected_at = :at, reject_reason = :reason
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .setParameter("nodeKey", nodeKey)
                .setParameter("nodeName", nodeName)
                .setParameter("by", rejected == null ? null : rejected.getOperatorId())
                .setParameter("at", rejected == null ? null : rejected.getCreatedAt())
                .setParameter("reason", comment)
                .setParameter("tenantId", tenantId)
                .setParameter("id", releaseId)
                .executeUpdate();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long num(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    /**
     * 原生查询的时间列：MySQL DATETIME 在 Hibernate 6 的 {@code Object[]} 结果里是
     * {@link LocalDateTime}（不是旧版的 {@code java.sql.Timestamp}）。只判 Timestamp 会让
     * {@code rejectedAt} 恒为 null——库里明明有值，详情却说没驳回时间（D-7）。
     */
    private static LocalDateTime time(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof java.time.OffsetDateTime offset) {
            return offset.toLocalDateTime();
        }
        return null;
    }

    /** release 行的 r30 状态列（等级/必填项/驳回五列）。 */
    private record FlowState(String releaseLevel, String emergencyReason, String previousDeployOrderCode,
                             String rejectedNodeKey, String rejectedNodeName, Long rejectedBy,
                             LocalDateTime rejectedAt, String rejectReason) {

        Map<String, Object> levelMap() {
            return Map.of("releaseLevel", releaseLevel);
        }
    }

    /** 发起审批的回执：谁被解析成审批链，前端才能直接跳进去（AC-1 的可核对口径）。 */
    public record StartResult(long instanceId, long releaseId, long definitionId,
                              Integer definitionVersion, List<String> nodeKeys) {
    }

    /** 改等级入参（三字段与创建同源，NORMAL 时后两个忽略）。 */
    public record UpdateLevel(String releaseLevel, String emergencyReason, String previousDeployOrderCode) {
    }

    /** 创建版本入参。 */
    public record CreateRelease(String name, Long domainId, LocalDate startDate, LocalDate endDate,
                                LocalDate plannedReleaseDate, String gateMode,
                                String releaseLevel, String emergencyReason, String previousDeployOrderCode) {
    }
}
