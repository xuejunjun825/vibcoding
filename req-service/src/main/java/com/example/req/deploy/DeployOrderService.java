package com.example.req.deploy;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.code.CodeGenerator;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.notify.NotificationWriter;
import com.example.platform.contract.quality.GateSnapshot;
import com.example.req.approval.ApprovalEngine;
import com.example.req.approval.ApprovalException;
import com.example.req.approval.ApprovalInstance;
import com.example.req.approval.ApprovalInstanceRepository;
import com.example.req.approval.ApproverDirectory;
import com.example.req.approval.StartApproval;
import com.example.req.requirement.RequirementEntity;
import com.example.req.requirement.RequirementRepository;
import com.example.req.requirement.RequirementViews;
import com.example.req.release.ReleaseEntity;
import com.example.req.release.ReleaseRepository;
import com.example.req.release.ReleaseService;
import com.example.req.review.ReleaseReviewService;
import com.example.platform.contract.DataScope;
import com.example.platform.common.tenant.TenantContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 发布单（TECHNICAL §5.7）。
 *
 * <p>三条不可越过的规则：
 * <ol>
 *   <li>准入只由服务端算：{@code submit} 内部重新取数判定，客户端传来的 PASS/FAIL 一律不使用；</li>
 *   <li>失败也要留痕：准入不通过时抛 {@link DeployErrors.AdmissionFailedException}，
 *       事务仍然提交已写入的准入快照（{@code noRollbackFor}），详情页能看到"上次校验的逐项结论"；</li>
 *   <li>强制放行是独立受控入口：非空原因 + 角色校验 + 审计 + 通知域负责人，四个条件缺一不可。</li>
 * </ol>
 */
@Service
public class DeployOrderService {

    /** 审计/通知的单据类型：验证脚本按 {@code biz_type='deploy_order'} 断言，不能改。 */
    public static final String BIZ_TYPE = "deploy_order";

    /** 审批流单据类型（§4.8 需求发布）。 */
    public static final String APPROVAL_BIZ_TYPE = "REQ_DEPLOY";

    public static final List<String> ENVS = List.of("TEST", "UAT", "PROD");

    /** 强制放行：M6 起已退役（AC-6.6），路由与签名保留但恒 409，机器码固定便于脚本断言。 */
    public static final String FORCE_RELEASE_RETIRED = "FORCE_RELEASE_RETIRED";

    /** 退役提示：前端"强制放行"入口已移除，历史脚本/老页面拿到 409 时至少知道该走哪条路。 */
    public static final String FORCE_RELEASE_HINT = "强制放行已退役，请走质量门或规则级豁免";

    private static final String ACTION_CREATE = "CREATE";
    private static final String ACTION_SUBMIT = "SUBMIT";
    private static final String ACTION_EXECUTION = "EXECUTION";
    private static final String ACTION_NO_GO_CONFIRM = "NO_GO_CONFIRM";

    private final DeployOrderRepository repository;
    private final DeployOrderRequirementRepository orderRequirementRepository;
    private final RequirementRepository requirementRepository;
    private final ReleaseRepository releaseRepository;
    private final ReleaseService releaseService;
    private final ReleaseReviewService releaseReviewService;
    private final AdmissionEvaluator admissionEvaluator;
    private final DeployPlanGuard planGuard;
    private final CodeGenerator codeGenerator;
    private final AuditService auditService;
    private final NotificationWriter notificationWriter;
    private final ApproverDirectory approverDirectory;
    private final ApprovalInstanceRepository instanceRepository;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ApprovalEngine> approvalEngine;

    public DeployOrderService(DeployOrderRepository repository,
                              DeployOrderRequirementRepository orderRequirementRepository,
                              RequirementRepository requirementRepository,
                              ReleaseRepository releaseRepository,
                              ReleaseService releaseService,
                              ReleaseReviewService releaseReviewService,
                              AdmissionEvaluator admissionEvaluator,
                              DeployPlanGuard planGuard,
                              CodeGenerator codeGenerator,
                              AuditService auditService,
                              NotificationWriter notificationWriter,
                              ApproverDirectory approverDirectory,
                              ApprovalInstanceRepository instanceRepository,
                              ObjectMapper objectMapper,
                              ObjectProvider<ApprovalEngine> approvalEngine) {
        this.repository = repository;
        this.orderRequirementRepository = orderRequirementRepository;
        this.requirementRepository = requirementRepository;
        this.releaseRepository = releaseRepository;
        this.releaseService = releaseService;
        this.releaseReviewService = releaseReviewService;
        this.admissionEvaluator = admissionEvaluator;
        this.planGuard = planGuard;
        this.codeGenerator = codeGenerator;
        this.auditService = auditService;
        this.notificationWriter = notificationWriter;
        this.approverDirectory = approverDirectory;
        this.instanceRepository = instanceRepository;
        this.objectMapper = objectMapper;
        this.approvalEngine = approvalEngine;
    }

    // ------------------------------------------------------------------ 查询

    @Transactional(readOnly = true)
    public RequirementViews.Page<DeployViews.OrderRow> page(Long releaseId, String env, String status,
                                                            int page, int size) {
        DataScope scope = TenantContext.require();
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        boolean domainFilter = scope.domainIds() != null && !scope.domainIds().isEmpty();
        List<Long> domainIds = domainFilter ? new ArrayList<>(scope.domainIds()) : List.of(-1L);
        var result = repository.search(scope.tenantId(), releaseId == null ? 0L : releaseId,
                normalizeEnv(env), normalizeStatus(status), domainFilter, domainIds,
                PageRequest.of(safePage - 1, safeSize));
        Map<Long, String> releaseNames = releaseNames(result.getContent().stream()
                .map(DeployOrderEntity::getReleaseId).toList(), scope.tenantId());
        List<DeployViews.OrderRow> rows = result.getContent().stream()
                .map(order -> toRow(order, releaseNames.get(order.getReleaseId())))
                .toList();
        return new RequirementViews.Page<>(rows, result.getTotalElements(), safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public DeployViews.OrderDetail detail(long id) {
        DeployOrderEntity order = requireVisible(id);
        return toDetail(order);
    }

    // ------------------------------------------------------------------ 创建

    @Transactional
    public DeployViews.OrderDetail create(CreateOrder cmd) {
        DataScope scope = TenantContext.require();
        if (cmd.releaseId() == null) {
            throw DeployErrors.validation("releaseId 必填");
        }
        ReleaseEntity release = releaseService.requireVisible(cmd.releaseId());
        String env = normalizeEnv(cmd.env());
        if (env.isEmpty() || !ENVS.contains(env)) {
            throw DeployErrors.validation("env 只能是 " + String.join("/", ENVS));
        }
        LocalDateTime windowStart = parseTime(cmd.windowStart());
        LocalDateTime windowEnd = parseTime(cmd.windowEnd());
        if (windowStart == null) {
            throw DeployErrors.validation("windowStart 必填，格式 ISO-8601");
        }
        if (windowEnd == null) {
            throw DeployErrors.validation("windowEnd 必填，格式 ISO-8601");
        }
        if (windowEnd.isBefore(windowStart)) {
            throw DeployErrors.validation("windowEnd 不能早于 windowStart");
        }
        List<DeployViews.Step> steps = normalizeSteps(cmd.steps());
        if (steps.isEmpty()) {
            throw DeployErrors.validation("steps 至少要有一步发布操作");
        }

        List<Long> requirementIds = resolveRequirementIds(cmd.requirementIds(), release, scope.tenantId());
        // R1 / AC-1：写库前的唯一闸门。需求集到此已定型（上面的 resolve 不会再有补集），
        // 远程校验失败（409/503）发生在任何 INSERT 之前 —— 不落单、不写审计，行数不变。
        planGuard.requireApproved(release.getId(), requirementIds);

        DeployOrderEntity entity = new DeployOrderEntity();
        entity.setTenantId(scope.tenantId());
        entity.setCode(codeGenerator.next("DEP", scope.tenantId()));
        entity.setReleaseId(release.getId());
        entity.setEnv(env);
        entity.setWindowStart(windowStart);
        entity.setWindowEnd(windowEnd);
        entity.setArtifactVersion(trimToNull(cmd.artifactVersion()));
        entity.setSteps(objectMapper.writeValueAsString(steps));
        entity.setRollbackPlan(trimToNull(cmd.rollbackPlan()));
        entity.setImpact(trimToNull(cmd.impact()));
        entity.setOwnerId(cmd.ownerId() != null ? cmd.ownerId() : scope.userId());
        entity.setStatus(DeployOrderStates.DRAFT);
        entity.setForcedRelease(false);
        entity.setCreatedBy(scope.userId());
        entity.setUpdatedBy(scope.userId());
        DeployOrderEntity saved = repository.save(entity);
        for (Long requirementId : requirementIds) {
            orderRequirementRepository.save(
                    new DeployOrderRequirementEntity(scope.tenantId(), saved.getId(), requirementId));
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", saved.getCode());
        detail.put("releaseId", saved.getReleaseId());
        detail.put("env", env);
        detail.put("requirementCount", requirementIds.size());
        auditService.record(ACTION_CREATE, BIZ_TYPE, saved.getId(), detail);
        return toDetail(saved);
    }

    /** 显式指定优先；否则按版本带出（只带研发需求 RD——PDR/BUS 没有 MR 与人天，代入会让准入永远过不了）。 */
    private List<Long> resolveRequirementIds(List<Long> explicit, ReleaseEntity release, long tenantId) {
        if (explicit != null && !explicit.isEmpty()) {
            List<Long> ids = new ArrayList<>(new LinkedHashSet<>(explicit));
            Map<Long, RequirementEntity> found = new HashMap<>();
            for (RequirementEntity entity : requirementRepository.findAllById(ids)) {
                if (tenantId == (entity.getTenantId() == null ? -1L : entity.getTenantId())) {
                    found.put(entity.getId(), entity);
                }
            }
            List<Long> missing = ids.stream().filter(id -> !found.containsKey(id)).toList();
            if (!missing.isEmpty()) {
                throw DeployErrors.validation("需求不存在或不可见：" + missing,
                        Map.of("requirementIds", missing));
            }
            return ids;
        }
        return requirementRepository.findByTenantIdAndReleaseId(tenantId, release.getId()).stream()
                .filter(entity -> "RD".equals(entity.getType()))
                .map(RequirementEntity::getId)
                .toList();
    }

    // ------------------------------------------------------------------ 准入

    @Transactional
    public DeployViews.AdmissionSnapshot evaluate(long id) {
        DeployOrderEntity order = requireVisible(id);
        DeployViews.AdmissionResult result = admissionEvaluator.evaluateAndStore(order);
        order.setUpdatedBy(TenantContext.require().userId());
        List<AdmissionItem> items = AdmissionPolicy.sort(
                AdmissionPolicy.markForced(result.items(), order.isForcedRelease()));
        return new DeployViews.AdmissionSnapshot(items, result.checkedAt(), false, result.signature(),
                DeployViews.AdmissionSnapshot.legacyOf(items), result.gate(),
                DeployViews.AdmissionCounts.of(items), result.skipCount(),
                items.size() < AdmissionItem.ORDER.size());
    }

    /**
     * 提交：重新判定九项准入 → 跳过项须等值确认 → 质量门通过且没有 FAIL（或已强制放行的历史单据）
     * 才发起需求发布审批。
     *
     * <p>跳过确认（{@code SKIP_NOT_ACKNOWLEDGED}）刻意排在质量门/全通过闸门之前：只要清单里有 SKIP，
     * 用户第一眼该看到的是"你有 1 项被跳过、请确认"，而不是被质量门的 409 挡在另一条错误上
     * （PRD AC-8.3，QA 用例 s5 的第 6 步按此顺序断言）。</p>
     *
     * <p>{@code noRollbackFor} 是刻意的：准入不通过时快照必须留下（详情页/审计要能复看失败项），
     * 只有审批流未配置这种"需要人去配置"的情况也同样保留快照。</p>
     */
    public DeployViews.SubmitResult submit(long id) {
        return submit(id, null);
    }

    @Transactional(noRollbackFor = {DeployErrors.AdmissionFailedException.class,
            DeployErrors.ApprovalNotConfiguredException.class,
            DeployErrors.SkipNotAcknowledgedException.class,
            DeployOrderService.QualityGateFailedException.class})
    public DeployViews.SubmitResult submit(long id, DeployViews.SubmitCmd cmd) {
        DataScope scope = TenantContext.require();
        DeployOrderEntity order = requireVisible(id);
        if (!DeployOrderStates.canSubmit(order.getStatus())) {
            throw DeployErrors.illegalTransition("submit", order.getStatus());
        }
        requireNoGoConfirmed(order, cmd);
        DeployViews.AdmissionResult result = admissionEvaluator.evaluateAndStore(order);
        DeployViews.GateEvidence gate = result.gate();
        requireSkippedAcknowledged(result, cmd);
        // 第 6 项先判：门禁没过时前端要拿到 failedRules（哪条红线/阈值），而不是笼统的"准入未通过"。
        if (gate != null && !gate.passed()) {
            throw new QualityGateFailedException(gate, AdmissionPolicy.sort(result.items()));
        }
        if (!admissionEvaluator.allPassed(result) && !order.isForcedRelease()) {
            throw DeployErrors.admissionFailed(AdmissionPolicy.sort(result.items()));
        }
        // AC-5.6：通过时把质量门定位信息固化到单据（快照 id 必填；gateId/reportId 取决于 deliver 是否回传）。
        order.setQualityGateSnapshotId(gate == null ? null : gate.snapshotId());
        order.setQualityGateId(gate == null ? null : gate.gateId());
        order.setReportId(gate == null ? null : gate.reportId());
        ReleaseEntity release = releaseService.requireVisible(order.getReleaseId());
        startApproval(order, release, scope);
        order.setStatus(DeployOrderStates.PENDING_APPROVAL);
        order.setUpdatedBy(scope.userId());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("status", DeployOrderStates.PENDING_APPROVAL);
        detail.put("forcedRelease", order.isForcedRelease());
        detail.put("admissionCheckedAt", String.valueOf(result.checkedAt()));
        detail.put("admissionFailed", result.failedCodes());
        detail.put("admissionSkipped", result.skipCount());
        detail.put("admissionCounts", result.counts() == null ? Map.of() : Map.of(
                "passed", result.counts().passed(), "failed", result.counts().failed(),
                "skipped", result.counts().skipped(), "closed", result.counts().closed(),
                "active", result.counts().active(), "total", result.counts().total()));
        detail.put("qualityGateSnapshotId", order.getQualityGateSnapshotId());
        detail.put("gateResult", gate == null ? null : gate.result());
        auditService.record("STATE_CHANGE", BIZ_TYPE, order.getId(), detail);
        return new DeployViews.SubmitResult(order.getStatus());
    }

    /**
     * AC-8.3：跳过 ≠ 通过。清单里有 {@code skipCount} 个 SKIP 时，提交必须回传<b>同一个数字</b>
     * （等值确认：多传/少传都不算，避免页面少一个勾选就把"未验证"当"已验证"）。
     * 没有跳过项时该字段被忽略——既有调用方（前端旧版本、脚本）不传也能通过。
     */
    private void requireSkippedAcknowledged(DeployViews.AdmissionResult result, DeployViews.SubmitCmd cmd) {
        int skipCount = result.skipCount();
        if (skipCount <= 0) {
            return;
        }
        Integer acknowledged = cmd == null ? null : cmd.acknowledgedSkipCount();
        if (acknowledged != null && acknowledged == skipCount) {
            return;
        }
        throw DeployErrors.skipNotAcknowledged(skipCount, acknowledged, AdmissionPolicy.sort(result.items()));
    }

    /**
     * AC-10.5：最新发布评审为 RECORDED 的 NO-GO 时，提交必须带 {@code confirmNoGo=true}，
     * 且确认人只能是 PMO/平台管理员；确认动作单独写审计（谁、何时、哪条评审），
     * 之后仍要继续过第 6 项质量门——二次确认不是绕过门禁的开关。
     */
    private void requireNoGoConfirmed(DeployOrderEntity order, DeployViews.SubmitCmd cmd) {
        releaseService.requireVisible(order.getReleaseId());
        var review = releaseReviewService.latest(order.getReleaseId());
        if (review == null || !"NO_GO".equals(review.decision()) || !"RECORDED".equals(review.status())) {
            return;
        }
        boolean confirmed = cmd != null && Boolean.TRUE.equals(cmd.confirmNoGo());
        if (!confirmed) {
            throw DeployErrors.noGoConfirmRequired(review.id());
        }
        boolean pmo = TenantContext.roles().stream()
                .anyMatch(role -> "PMO".equals(role) || "PLATFORM_ADMIN".equals(role));
        if (!pmo) {
            throw DeployErrors.forbidden("仅 PMO/平台管理员可对 NO-GO 评审做二次确认");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reviewId", review.id());
        detail.put("decision", review.decision());
        detail.put("confirmedBy", TenantContext.username());
        detail.put("confirmedAt", OffsetDateTime.now().toString());
        auditService.record(ACTION_NO_GO_CONFIRM, BIZ_TYPE, order.getId(), detail);
    }

    /**
     * 质量门未通过（AC-5.4）：409 + {@code QUALITY_GATE_FAILED}，detail 里带快照与逐条规则，
     * 前端据此就地展开"哪条规则拦了、怎么修"，不用再打一次门禁查询。
     */
    public static class QualityGateFailedException extends ApiException {

        private final DeployViews.GateEvidence gate;

        public QualityGateFailedException(DeployViews.GateEvidence gate, List<AdmissionItem> items) {
            super(409, "QUALITY_GATE_FAILED", message(gate), detail(gate, items));
            this.gate = gate;
        }

        public DeployViews.GateEvidence gate() {
            return gate;
        }

        private static String message(DeployViews.GateEvidence gate) {
            List<String> codes = gate.failedRules().stream().map(rule -> rule.code()).toList();
            String tail = codes.isEmpty() ? gate.result() : String.join("、", codes);
            return "发布质量门未通过（快照 #" + gate.snapshotId() + "）：" + tail;
        }

        private static Map<String, Object> detail(DeployViews.GateEvidence gate, List<AdmissionItem> items) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("snapshotId", gate.snapshotId());
            // ApiException 用 Map.copyOf 收口（null 值直接 NPE → 500），可选出参必须按存在性放入：
            // 没有已会签报告时 reportId 本就为空，不能为了字段齐全塞 null 进去。
            if (gate.gateId() != null) {
                detail.put("gateId", gate.gateId());
            }
            if (gate.reportId() != null) {
                detail.put("reportId", gate.reportId());
            }
            detail.put("result", gate.result());
            detail.put("hash", gate.hash());
            detail.put("failedRules", gate.failedRules());
            detail.put("openP0P1", gate.rules() == null ? List.of() : gate.rules().stream()
                    .filter(rule -> !rule.passed())
                    .map(GateSnapshot.GateRuleResult::code)
                    .toList());
            detail.put("items", items);
            detail.put("hint", "按规则修复后重新生成报告并会签；P0/P1 与 QG-04 不可豁免，可对其它规则申请限时豁免");
            return detail;
        }
    }

    private void startApproval(DeployOrderEntity order, ReleaseEntity release, DataScope scope) {
        ApprovalEngine engine = approvalEngine.getIfAvailable();
        if (engine == null) {
            throw DeployErrors.approvalNotConfigured();
        }
        // 已在本单上跑过（挂起/失败重试）就不重复发起：引擎会以 ILLEGAL_TRANSITION 拒绝。
        if (instanceRepository.findFirstByTenantIdAndBizTypeAndBizIdAndActiveFlag(
                scope.tenantId(), APPROVAL_BIZ_TYPE, order.getId(), ApprovalInstance.ACTIVE).isPresent()) {
            return;
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("releaseId", order.getReleaseId());
        attributes.put("env", order.getEnv());
        attributes.put("forcedRelease", order.isForcedRelease());
        attributes.put("requirementCount", admissionEvaluator.requirementIds(order).size());
        try {
            engine.start(new StartApproval(APPROVAL_BIZ_TYPE, order.getId(), null, release.getDomainId(),
                    attributes, null, null));
        } catch (ApprovalException ex) {
            if (ApprovalException.NO_WORKFLOW.equals(ex.code())
                    || ApprovalException.APPROVER_UNRESOLVED.equals(ex.code())) {
                throw DeployErrors.approvalNotConfigured();
            }
            throw ex;
        }
    }

    // ------------------------------------------------------------------ 强制放行（已退役）

    /**
     * 强制放行：M6 起<b>恒 409</b> {@code FORCE_RELEASE_RETIRED}（AC-6.6 / AC-12）。
     *
     * <p>路由与方法签名保留，只是不再具备任何写入能力——理由有三：①旧前端/脚本/自动化打过来必须拿到
     * 可断言的机器码而不是 404，否则调用方会以为"偶发失败"反复重试；②强制放行会绕过 P0/P1 红线，
     * 与"P0/P1 不可豁免"直接冲突，只保留规则级豁免这一条受控通道；③历史已放行的单据
     * （{@code forced_release=true}）仍按只读痕迹展示，不做数据回填。</p>
     *
     * <p>审计走独立事务：这里紧接着抛异常，同事务的审计会被回滚掉。</p>
     */
    @Transactional
    public DeployViews.OrderDetail forceRelease(long id, String reason) {
        DeployOrderEntity order = requireVisible(id);
        String trimmed = reason == null ? "" : reason.trim();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", trimmed);
        detail.put("status", order.getStatus());
        detail.put("retired", true);
        auditService.recordInNewTx("deploy.force-release.retired", BIZ_TYPE, order.getId(), detail);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("hint", FORCE_RELEASE_HINT);
        payload.put("status", order.getStatus());
        throw new ApiException(409, FORCE_RELEASE_RETIRED,
                "强制放行已退役：请通过质量门，或对可豁免规则申请限时豁免", payload);
    }

    // ------------------------------------------------------------------ 执行登记

    @Transactional
    public DeployViews.OrderDetail recordExecution(long id, ExecutionCmd cmd) {
        DataScope scope = TenantContext.require();
        DeployOrderEntity order = requireVisible(id);
        if (!DeployOrderStates.canRecordExecution(order.getStatus())) {
            throw DeployErrors.illegalTransition("execution", order.getStatus());
        }
        String result = cmd.result() == null ? "" : cmd.result().trim().toUpperCase();
        if (!DeployOrderStates.isExecutionResult(result)) {
            throw DeployErrors.validation("result 只能是 SUCCESS/FAILED/ROLLED_BACK");
        }
        LocalDateTime startedAt = parseTime(cmd.startedAt());
        LocalDateTime endedAt = parseTime(cmd.endedAt());
        if (startedAt == null || endedAt == null) {
            throw DeployErrors.validation("startedAt / endedAt 必填，格式 ISO-8601");
        }
        if (endedAt.isBefore(startedAt)) {
            throw DeployErrors.validation("endedAt 不能早于 startedAt");
        }
        order.setStartedAt(startedAt);
        order.setEndedAt(endedAt);
        order.setDurationMinutes(minutesBetween(startedAt, endedAt));
        order.setExecutionResult(result);
        order.setExecutionNote(trimToNull(cmd.note()));
        order.setDefectId(cmd.defectId());
        order.setStatus(result);
        order.setUpdatedBy(scope.userId());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("result", result);
        detail.put("durationMinutes", order.getDurationMinutes());
        detail.put("defectId", order.getDefectId());
        auditService.record(ACTION_EXECUTION, BIZ_TYPE, order.getId(), detail);
        return toDetail(order);
    }

    // ------------------------------------------------------------------ 统计

    /** 发布统计：成功率/平均耗时/失败原因分布，口径全部来自单据明细（{@link DeployStatsCalculator}）。 */
    @Transactional(readOnly = true)
    public DeployViews.DeployStats stats(Long releaseId, String env, LocalDate from, LocalDate to) {
        DataScope scope = TenantContext.require();
        boolean domainFilter = scope.domainIds() != null && !scope.domainIds().isEmpty();
        List<Long> domainIds = domainFilter ? new ArrayList<>(scope.domainIds()) : List.of(-1L);
        LocalDateTime lower = from == null ? LocalDateTime.of(1970, 1, 1, 0, 0) : from.atStartOfDay();
        LocalDateTime upper = to == null ? LocalDateTime.of(2999, 12, 31, 0, 0) : to.plusDays(1).atStartOfDay();
        List<DeployOrderEntity> rows = repository.searchForStats(scope.tenantId(),
                releaseId == null ? 0L : releaseId, normalizeEnv(env), lower, upper, domainFilter, domainIds);
        return DeployStatsCalculator.calculate(rows.stream()
                .map(row -> new DeployStatsCalculator.Sample(row.getStatus(), row.getDurationMinutes(),
                        row.getExecutionNote()))
                .toList());
    }

    // ------------------------------------------------------------------ 审批回调

    @Transactional
    public void onApprovalApproved(long id) {
        DeployOrderEntity order = requireVisible(id);
        if (DeployOrderStates.APPROVED.equals(order.getStatus())
                || DeployOrderStates.isTerminal(order.getStatus())) {
            return;
        }
        order.setStatus(DeployOrderStates.APPROVED);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("status", DeployOrderStates.APPROVED);
        detail.put("via", APPROVAL_BIZ_TYPE);
        auditService.record("STATE_CHANGE", BIZ_TYPE, order.getId(), detail);
    }

    @Transactional
    public void onApprovalRejected(long id, String reason) {
        DeployOrderEntity order = requireVisible(id);
        // 幂等：驳回/撤回回调可能重复到达，重复回调不再写第二条状态变更审计。
        if (DeployOrderStates.isTerminal(order.getStatus())
                || DeployOrderStates.APPROVED.equals(order.getStatus())
                || DeployOrderStates.REJECTED.equals(order.getStatus())) {
            return;
        }
        // 驳回回 DRAFT：单据可以改窗口/制品后重新提交；不回退需求状态（执行记录与门禁结论仍然有效）。
        order.setStatus(DeployOrderStates.REJECTED);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("status", DeployOrderStates.REJECTED);
        detail.put("reason", reason == null ? "" : reason);
        auditService.record("STATE_CHANGE", BIZ_TYPE, order.getId(), detail);
    }

    // ------------------------------------------------------------------ 可见性

    /** 越权即 403（由 ReleaseService 统一做域可见性判断与 PERMISSION_DENY 审计）。 */
    public DeployOrderEntity requireVisible(long id) {
        DataScope scope = TenantContext.require();
        DeployOrderEntity order = repository.findByIdAndTenantId(id, scope.tenantId())
                .orElseThrow(() -> DeployErrors.notFound(id));
        releaseService.requireVisible(order.getReleaseId());
        return order;
    }

    // ------------------------------------------------------------------ 视图

    private DeployViews.OrderDetail toDetail(DeployOrderEntity order) {
        String releaseName = releaseRepository.findByIdAndTenantId(order.getReleaseId(),
                        order.getTenantId())
                .map(ReleaseEntity::getName)
                .orElse(null);
        List<DeployViews.Step> steps = readSteps(order.getSteps());
        List<DeployViews.RequirementRow> requirements = admissionEvaluator.facts(order).stream()
                .map(fact -> new DeployViews.RequirementRow(fact.id(), fact.code(), fact.type(), fact.status(),
                        fact.mrUrl(), fact.estimateDays(), fact.actualDays()))
                .toList();
        DeployViews.ForcedRelease forced = order.isForcedRelease()
                ? new DeployViews.ForcedRelease(order.getForceReleaseReason(),
                        order.getForceBy() == null ? null : "用户#" + order.getForceBy(), order.getForceAt())
                : null;
        DeployViews.Execution execution = order.getExecutionResult() == null && order.getStartedAt() == null
                ? null
                : new DeployViews.Execution(order.getExecutionResult(), order.getStartedAt(), order.getEndedAt(),
                        order.getExecutionNote(), order.getDefectId(), order.getDurationMinutes());
        DeployViews.AdmissionSnapshot admission = admissionEvaluator.snapshot(order);
        // 强制放行已退役：动作不再出现在 allowedActions 里（历史单据的 forced 痕迹仍只读展示）。
        // 状态机是唯一来源，这里不再做第二道过滤（DeployOrderStates 已不产出该动作）。
        List<String> allowedActions = DeployOrderStates.allowedActions(order.getStatus());
        return new DeployViews.OrderDetail(order.getId(), order.getCode(), order.getReleaseId(), releaseName,
                order.getEnv(), order.getStatus(), order.getWindowStart(), order.getWindowEnd(),
                order.getArtifactVersion(), steps, order.getRollbackPlan(), order.getImpact(),
                order.getOwnerId(), null, order.isForcedRelease(),
                allowedActions,
                order.getCreatedAt(), order.getUpdatedAt(), requirements,
                admission, forced, execution,
                order.getQualityGateSnapshotId(),
                admission == null || admission.gate() == null ? null : admission.gate().result());
    }

    private DeployViews.OrderRow toRow(DeployOrderEntity order, String releaseName) {
        return new DeployViews.OrderRow(order.getId(), order.getCode(), order.getReleaseId(), releaseName,
                order.getEnv(), order.getStatus(), order.getWindowStart(), order.getWindowEnd(),
                order.getArtifactVersion(), order.getOwnerId(), null, order.isForcedRelease(),
                order.getCreatedAt());
    }

    private Map<Long, String> releaseNames(Collection<Long> releaseIds, long tenantId) {
        if (releaseIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (ReleaseEntity release : releaseRepository.findAllById(new LinkedHashSet<>(releaseIds))) {
            if (release.getTenantId() != null && release.getTenantId() == tenantId) {
                names.put(release.getId(), release.getName());
            }
        }
        return names;
    }

    private List<DeployViews.Step> readSteps(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            DeployViews.Step[] steps = objectMapper.readValue(json, DeployViews.Step[].class);
            return steps == null ? List.of() : List.of(steps);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    /** 步骤归一化：容忍前端传 {@code {name,order}} 或只传 {@code {name}}，顺序缺失时按数组下标补。 */
    private List<DeployViews.Step> normalizeSteps(List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<DeployViews.Step> steps = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> item : raw) {
            index++;
            if (item == null) {
                continue;
            }
            Object name = item.containsKey("name") ? item.get("name") : item.get("step");
            if (name == null || String.valueOf(name).isBlank()) {
                continue;
            }
            Object order = item.get("order");
            Integer orderValue = order instanceof Number number ? Integer.valueOf(number.intValue())
                    : order instanceof String text && !text.isBlank() ? parseInt(text) : null;
            steps.add(new DeployViews.Step(String.valueOf(name).trim(), orderValue == null ? index : orderValue));
        }
        steps.sort((a, b) -> Integer.compare(a.order() == null ? 0 : a.order(), b.order() == null ? 0 : b.order()));
        return steps;
    }

    private static Integer parseInt(String text) {
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 服务端算耗时（分钟，向上取整）：客户端传的 duration 一律不采信。 */
    static int minutesBetween(LocalDateTime startedAt, LocalDateTime endedAt) {
        long seconds = Duration.between(startedAt, endedAt).getSeconds();
        if (seconds <= 0) {
            return 0;
        }
        return (int) ((seconds + 59) / 60);
    }

    /**
     * 容忍三种入参形态：带偏移量（{@code 2026-12-02T10:00:00+08:00}）、带 Z、无时区。
     * 带偏移量的按系统时区折算成本地时间入库（脚本与前端都直接发 ISO-8601）。
     */
    static LocalDateTime parseTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim().replace(' ', 'T');
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // 继续尝试无时区
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static String normalizeEnv(String env) {
        return env == null || env.isBlank() ? "" : env.trim().toUpperCase();
    }

    private static String normalizeStatus(String status) {
        return status == null || status.isBlank() ? "" : status.trim().toUpperCase();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // ------------------------------------------------------------------ 请求体

    /**
     * 创建发布单。{@code requirementIds} 可选：不传时按版本自动带出研发需求（PRD §4.9），
     * 传了就只发布这些需求（脚本用它把一单钉在指定的 RD 上）。
     */
    public record CreateOrder(Long releaseId, String env, String windowStart, String windowEnd,
                              String artifactVersion, List<Map<String, Object>> steps, String rollbackPlan,
                              String impact, Long ownerId, List<Long> requirementIds) {
    }

    public record ExecutionCmd(String result, String startedAt, String endedAt, String note, Long defectId) {
    }

    public record ForceReleaseCmd(String reason) {
    }

    /** 供审批摘要/回调复用的只读集合视图。 */
    public Set<Long> requirementIdSet(DeployOrderEntity order) {
        return new LinkedHashSet<>(admissionEvaluator.requirementIds(order));
    }
}
