package com.example.req.deploy;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.code.CodeGenerator;
import com.example.platform.common.notify.NotificationWriter;
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

    /** 强制放行：PMO/管理层/平台管理员（§5.3 deploy:force）。 */
    private static final List<String> FORCE_ROLES = List.of("PLATFORM_ADMIN", "MGMT", "PMO");

    private static final String ACTION_CREATE = "CREATE";
    private static final String ACTION_SUBMIT = "SUBMIT";
    private static final String ACTION_FORCE = "ADMISSION_FORCE";
    private static final String ACTION_EXECUTION = "EXECUTION";

    private final DeployOrderRepository repository;
    private final DeployOrderRequirementRepository orderRequirementRepository;
    private final RequirementRepository requirementRepository;
    private final ReleaseRepository releaseRepository;
    private final ReleaseService releaseService;
    private final AdmissionEvaluator admissionEvaluator;
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
                              AdmissionEvaluator admissionEvaluator,
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
        this.admissionEvaluator = admissionEvaluator;
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
        return new DeployViews.AdmissionSnapshot(
                AdmissionPolicy.sort(AdmissionPolicy.markForced(result.items(), order.isForcedRelease())),
                result.checkedAt(), false);
    }

    /**
     * 提交：重新判定准入 → 全过（或已强制放行）才发起需求发布审批。
     *
     * <p>{@code noRollbackFor} 是刻意的：准入不通过时快照必须留下（详情页/审计要能复看失败项），
     * 只有审批流未配置这种"需要人去配置"的情况也同样保留快照。</p>
     */
    @Transactional(noRollbackFor = {DeployErrors.AdmissionFailedException.class,
            DeployErrors.ApprovalNotConfiguredException.class})
    public DeployViews.SubmitResult submit(long id) {
        DataScope scope = TenantContext.require();
        DeployOrderEntity order = requireVisible(id);
        if (!DeployOrderStates.canSubmit(order.getStatus())) {
            throw DeployErrors.illegalTransition("submit", order.getStatus());
        }
        DeployViews.AdmissionResult result = admissionEvaluator.evaluateAndStore(order);
        if (!admissionEvaluator.allPassed(result) && !order.isForcedRelease()) {
            throw DeployErrors.admissionFailed(AdmissionPolicy.sort(result.items()));
        }
        ReleaseEntity release = releaseService.requireVisible(order.getReleaseId());
        startApproval(order, release, scope);
        order.setStatus(DeployOrderStates.PENDING_APPROVAL);
        order.setUpdatedBy(scope.userId());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("status", DeployOrderStates.PENDING_APPROVAL);
        detail.put("forcedRelease", order.isForcedRelease());
        detail.put("admissionCheckedAt", String.valueOf(result.checkedAt()));
        detail.put("admissionFailed", result.failedCodes());
        auditService.record("STATE_CHANGE", BIZ_TYPE, order.getId(), detail);
        return new DeployViews.SubmitResult(order.getStatus());
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

    // ------------------------------------------------------------------ 强制放行

    /**
     * 强制放行：原因非空 → 角色校验（PMO/管理层/平台管理员）→ 状态可放行 → 落留痕 + 审计 + 通知域负责人。
     *
     * <p>顺序是刻意的：空原因先给 400（任何角色都一样），再判权限 403，最后才是状态 409；
     * 页面拿到 400 就知道是"没填原因"，不用先怀疑权限。</p>
     */
    @Transactional
    public DeployViews.OrderDetail forceRelease(long id, String reason) {
        DataScope scope = TenantContext.require();
        DeployOrderEntity order = requireVisible(id);
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty()) {
            throw DeployErrors.reasonRequired();
        }
        requireForcePermission(order, scope);
        if (!DeployOrderStates.canForceRelease(order.getStatus())) {
            throw DeployErrors.illegalTransition("forceRelease", order.getStatus());
        }
        DeployViews.AdmissionResult snapshot = admissionEvaluator.parse(order.getAdmissionResultJson());
        List<String> overridden = snapshot == null ? List.of() : snapshot.failedCodes();
        order.setForcedRelease(true);
        order.setForceReleaseReason(trimmed);
        order.setForceBy(scope.userId());
        order.setForceAt(LocalDateTime.now());
        order.setStatus(DeployOrderStates.APPROVED);
        order.setUpdatedBy(scope.userId());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", trimmed);
        detail.put("status", DeployOrderStates.APPROVED);
        detail.put("overriddenItems", overridden);
        auditService.record(ACTION_FORCE, BIZ_TYPE, order.getId(), detail);
        notifyDomainOwners(order, trimmed, scope);
        return toDetail(order);
    }

    private void requireForcePermission(DeployOrderEntity order, DataScope scope) {
        if (approverDirectory.hasAnyRole(scope.tenantId(), scope.userId(), FORCE_ROLES)) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("action", ACTION_FORCE);
        detail.put("userId", scope.userId());
        auditService.recordInNewTx("PERMISSION_DENY", BIZ_TYPE, order.getId(), detail);
        throw DeployErrors.forbidden("只有 PMO / 管理层 / 平台管理员可以强制放行");
    }

    /** 通知失败不阻断放行（放行本身已审计）：通知是告知，不是审批的一部分。 */
    private void notifyDomainOwners(DeployOrderEntity order, String reason, DataScope scope) {
        try {
            ReleaseEntity release = releaseRepository.findByIdAndTenantId(order.getReleaseId(), scope.tenantId())
                    .orElse(null);
            if (release == null || release.getDomainId() == null) {
                return;
            }
            List<Long> owners = approverDirectory.domainOwners(scope.tenantId(), List.of(release.getDomainId()));
            String title = "发布单被强制放行：" + order.getCode();
            String content = "环境 " + order.getEnv() + "，原因：" + reason;
            for (Long ownerId : new LinkedHashSet<>(owners)) {
                if (ownerId == null) {
                    continue;
                }
                notificationWriter.send(ownerId, "DEPLOY_FORCE_RELEASE", title, content, BIZ_TYPE, order.getId());
            }
        } catch (RuntimeException ex) {
            // 通知/查域负责人失败只影响"有没有人被告知"，不影响放行结果；审计已落库。
        }
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
        return new DeployViews.OrderDetail(order.getId(), order.getCode(), order.getReleaseId(), releaseName,
                order.getEnv(), order.getStatus(), order.getWindowStart(), order.getWindowEnd(),
                order.getArtifactVersion(), steps, order.getRollbackPlan(), order.getImpact(),
                order.getOwnerId(), null, order.isForcedRelease(),
                DeployOrderStates.allowedActions(order.getStatus()),
                order.getCreatedAt(), order.getUpdatedAt(), requirements,
                admissionEvaluator.snapshot(order), forced, execution);
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
