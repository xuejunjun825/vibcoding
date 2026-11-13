package com.example.req.requirement;

import com.example.platform.common.audit.ActualPeriodRecorder;
import com.example.platform.common.audit.AuditService;
import com.example.platform.common.code.CodeGenerator;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.notify.NotificationWriter;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.GateDecision;
import com.example.platform.contract.GatePort;
import com.example.platform.contract.RequirementActionMatrix;
import com.example.req.integration.sonar.SonarProjectKeys;
import com.example.req.requirement.RequirementDtos.AssignVendorRequest;
import com.example.req.requirement.RequirementDtos.CreateCommand;
import com.example.req.requirement.RequirementDtos.DeliverRequest;
import com.example.req.requirement.RequirementDtos.QuickTestRequest;
import com.example.req.requirement.RequirementViews.Child;
import com.example.req.requirement.RequirementViews.Created;
import com.example.req.requirement.RequirementViews.Detail;
import com.example.req.requirement.RequirementViews.Page;
import com.example.req.requirement.RequirementViews.QuickTest;
import com.example.req.requirement.RequirementViews.Rollup;
import com.example.req.requirement.RequirementViews.TransitionResult;
import com.example.req.requirement.RequirementViews.View;
import com.example.req.sys.SysDb;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 需求主服务：三类需求的创建/更新/拆解/流转/转派/交付/快速测试。
 *
 * <p>状态目标只来自 {@link RequirementStateMachine}（其数据源是 contract 的 StateTables），
 * 父子类型、环、人天精度来自 {@link RequirementRules}，两者都是纯函数，可脱离数据库单测。</p>
 */
@Service
public class RequirementService {

    public static final String BIZ_TYPE = "REQUIREMENT";

    private final RequirementRepository repository;
    private final RequirementDomainRelRepository domainRelRepository;
    private final RequirementStateMachine stateMachine;
    private final CodeGenerator codeGenerator;
    private final AuditService auditService;
    private final NotificationWriter notificationWriter;
    private final ObjectProvider<GatePort> gatePort;
    private final ObjectProvider<RequirementApprovalStarter> approvalStarter;
    private final JdbcTemplate jdbcTemplate;

    public RequirementService(RequirementRepository repository,
                              RequirementDomainRelRepository domainRelRepository,
                              RequirementStateMachine stateMachine,
                              CodeGenerator codeGenerator,
                              AuditService auditService,
                              NotificationWriter notificationWriter,
                              ObjectProvider<GatePort> gatePort,
                              ObjectProvider<RequirementApprovalStarter> approvalStarter,
                              JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.domainRelRepository = domainRelRepository;
        this.stateMachine = stateMachine;
        this.codeGenerator = codeGenerator;
        this.auditService = auditService;
        this.notificationWriter = notificationWriter;
        this.gatePort = gatePort;
        this.approvalStarter = approvalStarter;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---------------------------------------------------------------- create

    @Transactional
    public Created create(CreateCommand cmd) {
        DataScope scope = TenantContext.require();
        String type = normalizeType(cmd.type());
        String title = requireTitle(cmd.title());
        String priority = normalizePriority(cmd.priority());
        applyRule(RequirementRules.checkDays(type, cmd.estimateDays(), cmd.actualDays()));

        RequirementEntity parent = null;
        if (cmd.parentId() != null) {
            parent = requireVisible(cmd.parentId());
            applyRule(RequirementRules.checkParentCombination(type, parent.getType()));
            applyRule(RequirementRules.checkAncestorCycle(null, parent.getId(), this::parentOf));
        } else {
            applyRule(RequirementRules.checkParentCombination(type, null));
        }

        RequirementEntity entity = new RequirementEntity();
        entity.setTenantId(scope.tenantId());
        entity.setType(type);
        entity.setCode(codeGenerator.next(type, scope.tenantId()));
        entity.setTitle(title);
        entity.setDescription(cmd.description());
        entity.setPriority(priority);
        entity.setStatus(stateMachine.initialStatus(type));
        entity.setParentId(parent == null ? null : parent.getId());
        entity.setDomainId(firstDomain(cmd.domainIds()));
        entity.setOriginOrgId(originOrgId(scope));
        entity.setReporterId(scope.userId());
        entity.setOwnerUserId(cmd.ownerUserId() == null ? scope.userId() : cmd.ownerUserId());
        entity.setExpectedDeliveryDate(cmd.expectedDeliveryDate());
        entity.setEstimateDays(RequirementRollup.scale(cmd.estimateDays()));
        entity.setActualDays(RequirementRollup.scale(cmd.actualDays()));
        entity.setCreatedBy(scope.userId());
        entity.setUpdatedBy(scope.userId());
        RequirementEntity saved = repository.save(entity);
        replaceDomains(saved.getId(), scope.tenantId(), cmd.domainIds(), saved.getDomainId());

        if (parent != null) {
            refreshParentRollup(parent);
        }

        auditService.record("CREATE", BIZ_TYPE, saved.getId(), Map.of(
                "type", type, "code", saved.getCode(), "status", saved.getStatus(),
                "parentId", Objects.requireNonNullElse(saved.getParentId(), 0L)));
        return new Created(saved.getId(), saved.getCode(), saved.getType(), saved.getStatus(),
                allowedActions(saved.getType(), saved.getStatus(), saved.getReporterId()));
    }

    // ---------------------------------------------------------------- update

    @Transactional
    public View update(long id, RequirementDtos.UpdateRequest req) {
        DataScope scope = TenantContext.require();
        rejectStatusInBody(req.extra());
        RequirementEntity entity = requireWritable(id);
        Long oldParentId = entity.getParentId();
        applyParentChange(entity, req.extra());
        applyDaysChange(entity, req.extra());
        applySonarKeyChange(entity, req.extra());
        Map<String, Object> before = Map.of("title", entity.getTitle(),
                "priority", entity.getPriority(),
                "expectedDeliveryDate", String.valueOf(entity.getExpectedDeliveryDate()));

        if (req.title != null) {
            entity.setTitle(requireTitle(req.title));
        }
        if (req.description != null) {
            entity.setDescription(req.description);
        }
        if (req.priority != null) {
            entity.setPriority(normalizePriority(req.priority));
        }
        if (req.expectedDeliveryDate != null) {
            entity.setExpectedDeliveryDate(req.expectedDeliveryDate);
        }
        entity.setUpdatedBy(scope.userId());
        repository.save(entity);
        if (req.domainIds != null) {
            replaceDomains(entity.getId(), scope.tenantId(), req.domainIds, entity.getDomainId());
        }
        // 换父或改人天都要重算受影响父需求的汇总（AC-2：父需求人天由子需求求和）。
        if (!Objects.equals(oldParentId, entity.getParentId())) {
            if (oldParentId != null) {
                refreshParentRollup(requireVisible(oldParentId));
            }
        }
        if (entity.getParentId() != null) {
            refreshParentRollup(requireVisible(entity.getParentId()));
        }
        auditService.record("UPDATE", BIZ_TYPE, entity.getId(), Map.of("before", before,
                "after", Map.of("title", entity.getTitle(), "priority", entity.getPriority())));
        return toView(entity, null);
    }

    /**
     * AC-2：改父需求时重做「类型分层 + 祖先环」两道校验。
     * 只改父不重算会让 BUS→PDR→RD 之外的组合或环从 update 口子漏进来。
     */
    private void applyParentChange(RequirementEntity entity, Map<String, Object> extra) {
        if (extra == null || !extra.containsKey("parentId")) {
            return;
        }
        Long parentId = SysDb.asNullableLong(extra.get("parentId"));
        if (Objects.equals(parentId, entity.getParentId())) {
            return;
        }
        RequirementEntity parent = parentId == null ? null : requireVisible(parentId);
        applyRule(RequirementRules.checkParentCombination(entity.getType(),
                parent == null ? null : parent.getType()));
        applyRule(RequirementRules.checkAncestorCycle(entity.getId(), parentId, this::parentOf));
        entity.setParentId(parentId);
    }

    /**
     * AC-6.1：需求级 Sonar 项目键（优先于版本默认值）。
     *
     * <p>这条写入路径此前缺失——{@code requirement.sonar_project_key} 有列、快照服务也读它，
     * 但没有任何接口能写，于是「需求 &gt; 版本」的优先级只能靠改库，界面上不可达。字段放在
     * update 的 {@code extra} 里（与 parentId/estimateDays 同一手法），因此不需要新端点、
     * 不改变既有 PUT/PATCH 契约；`null` 或空格串表示「清掉自有键、回落到版本默认」。</p>
     */
    private void applySonarKeyChange(RequirementEntity entity, Map<String, Object> extra) {
        if (extra == null || !extra.containsKey("sonarProjectKey")) {
            return;
        }
        Object raw = extra.get("sonarProjectKey");
        String next = SonarProjectKeys.normalize(raw == null ? null : raw.toString(), "sonarProjectKey");
        entity.setSonarProjectKey(next);
    }

    /** AC-2：父需求人天只读；RD 走 0.5 天精度校验后才落库。 */
    private void applyDaysChange(RequirementEntity entity, Map<String, Object> extra) {
        if (extra == null || (!extra.containsKey("estimateDays") && !extra.containsKey("actualDays"))) {
            return;
        }
        BigDecimal estimate = daysValue(extra.get("estimateDays"));
        BigDecimal actual = daysValue(extra.get("actualDays"));
        applyRule(RequirementRules.checkDays(entity.getType(), estimate, actual));
        if (estimate != null) {
            entity.setEstimateDays(RequirementRollup.scale(estimate));
        }
        if (actual != null) {
            entity.setActualDays(RequirementRollup.scale(actual));
        }
    }

    private static BigDecimal daysValue(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof BigDecimal decimal) {
            return decimal;
        }
        if (raw instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        String text = raw.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw RequirementErrors.validation("人天必须是数字：" + raw);
        }
    }

    // ---------------------------------------------------------------- detail

    @Transactional(readOnly = true)
    public Detail detail(long id) {
        RequirementEntity entity = requireVisible(id);
        List<RequirementEntity> children =
                repository.findByTenantIdAndParentIdOrderByCreatedAtAsc(entity.getTenantId(), id);
        Rollup rollup = RequirementRollup.of(children,
                stateMachine::isTerminalAcrossTypes);
        List<Child> childViews = children.stream().map(child -> new Child(child.getId(), child.getCode(),
                child.getType(), child.getStatus(), child.getTitle(), child.getOwnerUserId(),
                child.getEstimateDays(), child.getActualDays())).toList();
        return new Detail(toView(entity, rollup), childViews,
                allowedActions(entity.getType(), entity.getStatus(), entity.getReporterId()), Map.of());
    }

    public List<String> allowedActions(long id) {
        RequirementEntity entity = requireVisible(id);
        return allowedActions(entity.getType(), entity.getStatus(), entity.getReporterId());
    }

    // -------------------------------------------------------------- transition

    @Transactional
    public TransitionResult transition(long id, String action, String comment) {
        return transition(id, action, comment, "UI");
    }

    @Transactional
    public TransitionResult transition(long id, String action, String comment, String source) {
        return transition(id, action, comment, source, null);
    }

    @Transactional
    public TransitionResult transition(long id, String action, String comment, String source,
                                       Long definitionId) {
        DataScope scope = TenantContext.require();
        RequirementEntity entity = requireWritable(id);
        // r17 顺序即契约：先鉴权（403，不泄露 from/allowedTargets）后状态（409）。
        // 只有 UI 来源进矩阵：APPROVAL 审批回调与 GITLAB（MrAdvanceService）不带用户上下文，
        // 若也走矩阵会把需求永久卡在 APPROVING（AC-9）。
        if ("UI".equals(source)) {
            authorize(entity, action, scope);
        }
        String from = entity.getStatus();
        String target = stateMachine.target(entity.getType(), from, action);

        // AC-10：review_start 只发起 REQ_REVIEW 审批，状态不动；评审通过由回调里的
        // review_pass 落地。因此这里必须先于常规流转返回，不能把状态写成 PENDING_REVIEW 了事。
        if ("review_start".equals(action)) {
            RequirementApprovalStarter starter = approvalStarter.getIfAvailable();
            if (starter == null) {
                throw RequirementErrors.approvalUnavailable();
            }
            long instanceId = starter.startReview(entity.getId(), source, definitionId);
            auditService.record("REVIEW_START", BIZ_TYPE, entity.getId(), Map.of(
                    "status", from, "instanceId", instanceId, "source", source,
                    "definitionId", Objects.requireNonNullElse(definitionId, 0L),
                    "comment", Objects.requireNonNullElse(comment, ""), "operator", scope.userId()));
            return new TransitionResult(entity.getId(), from,
                    allowedActions(entity.getType(), from, entity.getReporterId()));
        }

        // AC-10 反向守卫：没有 APPROVED 的 REQ_REVIEW 实例时，review_pass 一律 409，
        // 防止「评审入池」被直连动作绕过（审批驳回后也走同一条守卫）。
        if ("review_pass".equals(action)
                && RequirementRules.TYPE_BUS.equals(entity.getType())) {
            RequirementApprovalStarter starter = approvalStarter.getIfAvailable();
            if (starter == null
                    || !starter.isApproved(entity.getId(), RequirementReviewCallback.BIZ_TYPE)) {
                throw RequirementErrors.reviewApprovalRequired(entity.getId());
            }
        }

        if (RequirementRules.TYPE_RD.equals(entity.getType()) && "TEST_PASSED".equals(target)) {
            GatePort port = gatePort.getIfAvailable();
            if (port != null) {
                GateDecision result = port.evaluate(entity.getId());
                if (result != null && !result.passed()) {
                    throw RequirementErrors.gateBlocked(result);
                }
            }
        }
        if ("APPROVING".equals(target)) {
            RequirementApprovalStarter starter = approvalStarter.getIfAvailable();
            if (starter != null) {
                starter.start(entity.getId(), RequirementApprovalCallback.BIZ_TYPE, source);
            }
        }

        entity.setStatus(target);
        if (stateMachine.isTerminal(entity.getType(), target)) {
            entity.setClosedAt(java.time.LocalDateTime.now());
        }
        entity.setUpdatedBy(scope.userId());
        repository.save(entity);

        // 实际期与状态流转同源：进入 IN_DEV 开实际起点、交付类终态收实际终点（V9 回填同口径）。
        ActualPeriodRecorder.record(BIZ_TYPE, entity.getId(), from, target);

        auditService.record("STATE_CHANGE", BIZ_TYPE, entity.getId(), Map.of(
                "from", from, "to", target, "action", action, "source", source,
                "comment", Objects.requireNonNullElse(comment, ""), "operator", scope.userId()));
        notifyTransition(entity, from, target, comment);
        return new TransitionResult(entity.getId(), target,
                allowedActions(entity.getType(), target, entity.getReporterId()));
    }

    // -------------------------------------------------------------- decompose

    @Transactional
    public Created decompose(long id, RequirementDtos.DecomposeRequest req) {
        RequirementEntity parent = requireWritable(id);
        String childType = RequirementRules.childTypeOf(parent.getType());
        if (childType == null) {
            throw RequirementErrors.validation("该类型不能再拆解：" + parent.getType());
        }
        CreateCommand cmd = new CreateCommand(childType, req.title, req.description,
                req.domainIds == null || req.domainIds.isEmpty()
                        ? domainsOf(parent.getId()) : req.domainIds,
                req.priority, req.expectedDeliveryDate, parent.getId(),
                req.ownerUserId, req.estimateDays, req.actualDays);
        Created created = create(cmd);
        auditService.record("DECOMPOSE", BIZ_TYPE, parent.getId(), Map.of(
                "childId", created.id(), "childType", childType));
        refreshParentRollup(requireVisible(parent.getId()));
        return created;
    }

    // ------------------------------------------------------------ assignVendor

    @Transactional
    public View assignVendor(long id, AssignVendorRequest req) {
        DataScope scope = TenantContext.require();
        if (req.reason() == null || req.reason().isBlank()) {
            throw RequirementErrors.reasonRequired();
        }
        if (req.vendorId() == null || req.vendorId() <= 0) {
            throw RequirementErrors.validation("vendorId 必填");
        }
        RequirementEntity entity = requireWritable(id);
        Map<String, Object> before = Map.of(
                "assigneeVendorId", Objects.requireNonNullElse(entity.getAssigneeVendorId(), 0L),
                "ownerVendorId", Objects.requireNonNullElse(entity.getOwnerVendorId(), 0L));

        entity.setAssigneeVendorId(req.vendorId());
        entity.setOwnerVendorId(req.vendorId());
        if (req.ownerUserId() != null) {
            entity.setOwnerUserId(req.ownerUserId());
        }
        entity.setUpdatedBy(scope.userId());
        repository.save(entity);
        auditService.record("VENDOR_TRANSFER", BIZ_TYPE, entity.getId(), Map.of(
                "before", before, "after", Map.of("assigneeVendorId", req.vendorId()),
                "reason", req.reason()));
        if (entity.getOwnerUserId() != null) {
            notificationWriter.send(entity.getOwnerUserId(), "VENDOR_TRANSFER", "需求转派",
                    "需求 " + entity.getCode() + " 已转派：" + req.reason(), BIZ_TYPE, entity.getId());
        }
        return toView(entity, null);
    }

    // ---------------------------------------------------------------- deliver

    @Transactional
    public View deliver(long id, DeliverRequest req) {
        DataScope scope = TenantContext.require();
        RequirementEntity entity = requireWritable(id);
        if (!RequirementRules.TYPE_RD.equals(entity.getType())) {
            throw RequirementErrors.validation("只有研发需求（RD）可以登记交付");
        }
        applyRule(RequirementRules.checkHalfDay(req.estimateDays(), "estimateDays"));
        applyRule(RequirementRules.checkHalfDay(req.actualDays(), "actualDays"));

        BigDecimal beforeEstimate = entity.getEstimateDays();
        BigDecimal beforeActual = entity.getActualDays();
        if (req.estimateDays() != null) {
            entity.setEstimateDays(RequirementRollup.scale(req.estimateDays()));
        }
        if (req.actualDays() != null) {
            entity.setActualDays(RequirementRollup.scale(req.actualDays()));
        }
        if (req.mrUrl() != null) {
            entity.setMrUrl(req.mrUrl());
        }
        if (req.ownerUserId() != null) {
            entity.setOwnerUserId(req.ownerUserId());
        }
        entity.setUpdatedBy(scope.userId());
        repository.save(entity);
        if (entity.getParentId() != null) {
            refreshParentRollup(requireVisible(entity.getParentId()));
        }
        auditService.record("DELIVER", BIZ_TYPE, entity.getId(), Map.of(
                "before", Map.of("estimateDays", String.valueOf(beforeEstimate),
                        "actualDays", String.valueOf(beforeActual)),
                "after", Map.of("estimateDays", String.valueOf(entity.getEstimateDays()),
                        "mrUrl", Objects.requireNonNullElse(entity.getMrUrl(), ""))));
        return toView(entity, null);
    }

    // --------------------------------------------------------------- quickTest

    @Transactional
    public QuickTest quickTest(long id, QuickTestRequest req) {
        DataScope scope = TenantContext.require();
        RequirementEntity entity = requireWritable(id);
        String result = req.result() == null ? "" : req.result().trim().toUpperCase();
        if (!"PASS".equals(result) && !"FAIL".equals(result)) {
            throw RequirementErrors.validation("result 只能是 PASS 或 FAIL");
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO test_execution
                      (tenant_id, requirement_id, source, result, log_url, executor_id, executed_at)
                    VALUES (?, ?, 'MANUAL', ?, ?, ?, NOW(3))
                    """, new String[]{"id"});
            ps.setLong(1, scope.tenantId());
            ps.setLong(2, entity.getId());
            ps.setString(3, result);
            ps.setString(4, req.note());
            ps.setLong(5, scope.userId());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        long executionId = key == null ? 0L : key.longValue();
        if ("PASS".equals(result)) {
            entity.setTestPassedExecutionId(executionId);
            repository.save(entity);
        }
        auditService.record("EXECUTION_REPORT", BIZ_TYPE, entity.getId(), Map.of(
                "executionId", executionId, "result", result, "source", "MANUAL",
                "note", Objects.requireNonNullElse(req.note(), "")));
        return new QuickTest(executionId, result);
    }

    // ----------------------------------------------------------------- queries

    public RequirementEntity requireVisible(long id) {
        DataScope scope = TenantContext.require();
        RequirementEntity entity = repository.findByIdAndTenantId(id, scope.tenantId())
                .orElseThrow(() -> RequirementErrors.notFound("需求", id));
        if (!vendorVisible(entity, scope)) {
            auditService.recordInNewTx("PERMISSION_DENY", BIZ_TYPE, id, Map.of(
                    "userId", scope.userId(), "assigneeVendorId",
                    Objects.requireNonNullElse(entity.getAssigneeVendorId(), 0L)));
            throw RequirementErrors.outOfScope("需求", id);
        }
        return entity;
    }

    public RequirementEntity requireWritable(long id) {
        RequirementEntity entity = requireVisible(id);
        if (stateMachine.isTerminal(entity.getType(), entity.getStatus())
                && !stateMachine.allowedActions(entity.getType(), entity.getStatus()).contains("reopen")) {
            throw RequirementErrors.validation("需求已关闭，不可修改");
        }
        return entity;
    }

    private boolean vendorVisible(RequirementEntity entity, DataScope scope) {
        Long vendor = entity.getAssigneeVendorId() != null
                ? entity.getAssigneeVendorId() : entity.getOwnerVendorId();
        return vendor == null || scope.canSeeVendor(vendor);
    }

    private Long parentOf(Long id) {
        DataScope scope = TenantContext.require();
        return repository.findByIdAndTenantId(id, scope.tenantId())
                .map(RequirementEntity::getParentId).orElse(null);
    }

    /**
     * 父需求人天 = 子需求之和；改动会一路向上重算（RD → PDR → BUS），
     * 否则 BUS 上显示的人天会停在 PDR 的旧值。最多 8 层防御异常数据成环。
     */
    private void refreshParentRollup(RequirementEntity parent) {
        RequirementEntity cursor = parent;
        int guard = 0;
        while (cursor != null && guard++ < 8) {
            RequirementEntity current = cursor;
            List<RequirementEntity> children = repository
                    .findByTenantIdAndParentIdOrderByCreatedAtAsc(current.getTenantId(), current.getId());
            Rollup rollup = RequirementRollup.of(children, stateMachine::isTerminalAcrossTypes);
            current.setEstimateDays(rollup.estimateDays());
            current.setActualDays(rollup.actualDays());
            repository.save(current);
            cursor = current.getParentId() == null ? null
                    : repository.findByIdAndTenantId(current.getParentId(), current.getTenantId())
                            .orElse(null);
        }
    }

    private void replaceDomains(long requirementId, long tenantId, List<Long> domainIds, Long fallback) {
        domainRelRepository.deleteByRequirementId(requirementId);
        List<Long> ids = domainIds == null || domainIds.isEmpty()
                ? (fallback == null ? List.of() : List.of(fallback)) : domainIds.stream().distinct().toList();
        for (Long domainId : ids) {
            RequirementDomainRelEntity rel = new RequirementDomainRelEntity();
            rel.setRequirementId(requirementId);
            rel.setDomainId(domainId);
            rel.setTenantId(tenantId);
            domainRelRepository.save(rel);
        }
    }

    private List<Long> domainsOf(long requirementId) {
        return domainRelRepository.findByRequirementId(requirementId).stream()
                .map(RequirementDomainRelEntity::getDomainId).toList();
    }

    private Long firstDomain(List<Long> domainIds) {
        return domainIds == null || domainIds.isEmpty() ? null : domainIds.get(0);
    }

    /**
     * 需求来源组织：用户组织子树里最靠近根的那个。平台管理员这类没有组织归属的账号子树为空，
     * 这里退到租户根组织，而不是写 null —— {@code requirement.origin_org_id} 是 NOT NULL，
     * 2026-11-09 T11 实测：t1/admin（早期创建、org_id 为空）建 BUS 直接
     * {@code Column 'origin_org_id' cannot be null} 500。
     */
    private Long originOrgId(DataScope scope) {
        if (scope.orgSubtreeIds() != null && !scope.orgSubtreeIds().isEmpty()) {
            return scope.orgSubtreeIds().iterator().next();
        }
        List<Long> roots = jdbcTemplate.queryForList(
                "SELECT id FROM sys_org WHERE tenant_id = ? AND parent_id IS NULL ORDER BY id LIMIT 1",
                Long.class, scope.tenantId());
        if (roots.isEmpty()) {
            throw new ApiException(409, "ORG_NOT_CONFIGURED",
                    "租户缺少根组织，无法提交需求：请先为该租户建立组织树",
                    Map.of("tenantId", scope.tenantId()));
        }
        return roots.get(0);
    }

    private void notifyTransition(RequirementEntity entity, String from, String to, String comment) {
        Long userId = entity.getOwnerUserId();
        if (userId == null) {
            return;
        }
        notificationWriter.send(userId, "REQUIREMENT_STATE", "需求状态变更",
                entity.getCode() + "：" + from + " → " + to
                        + (comment == null || comment.isBlank() ? "" : "（" + comment + "）"),
                BIZ_TYPE, entity.getId());
    }

    /**
     * 调用者快照：矩阵在 platform-contract（零依赖）里，只认 JDK 类型，因此由服务层从
     * TenantContext 组装。没有 Principal 时（内部调用线程）退回 DataScope 身份 + 空角色集。
     */
    private RequirementActionMatrix.Caller callerOf(DataScope scope) {
        TenantContext.Principal p = TenantContext.principal();
        return p != null
                ? new RequirementActionMatrix.Caller(p.userId(), p.roles(), p.vendorId())
                : new RequirementActionMatrix.Caller(scope.userId(), TenantContext.roles(), null);
    }

    /**
     * 动作级授权（r17）：矩阵未登记的动作<b>不</b>报 403 —— 那是状态机自己的 409，
     * 「鉴权不认识的动作」不等于「无权执行」。
     */
    private void authorize(RequirementEntity entity, String action, DataScope scope) {
        if (!RequirementActionMatrix.known(action)) {
            return;
        }
        RequirementActionMatrix.Caller caller = callerOf(scope);
        if (RequirementActionMatrix.can(action, entity.getReporterId(), caller)) {
            return;
        }
        List<String> allowed = allowedActions(entity.getType(), entity.getStatus(),
                entity.getReporterId());
        // 不用 Map.of：vendorId 可为 null，Map.of 会 NPE 把 403 变成 500。
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("action", action);
        detail.put("userId", caller.userId());
        detail.put("roles", caller.roles());
        detail.put("vendorId", caller.vendorId());
        detail.put("allowedActions", allowed);
        // 必须新事务：外层 @Transactional 随后因 403 回滚，同事务的审计会一起消失。
        auditService.recordInNewTx("PERMISSION_DENIED", BIZ_TYPE, entity.getId(), detail);
        throw RequirementErrors.actionForbidden(action, allowed);
    }

    /** allowedActions 的唯一来源：状态机的合法集合 ∩ 调用者被授权的动作（与执行共用同一谓词）。 */
    List<String> allowedActions(String type, String status, Long reporterId) {
        return RequirementActionMatrix.filter(type, status, reporterId,
                callerOf(TenantContext.require()));
    }

    View toView(RequirementEntity entity, Rollup rollup) {
        Long parentId = entity.getParentId();
        String parentCode = null;
        if (parentId != null) {
            parentCode = repository.findByIdAndTenantId(parentId, entity.getTenantId())
                    .map(RequirementEntity::getCode).orElse(null);
        }
        return new View(entity.getId(), entity.getCode(), entity.getType(), entity.getStatus(),
                entity.getPriority(), entity.getTitle(), entity.getDescription(), parentId, parentCode,
                entity.getDomainId(), entity.getOriginOrgId(), entity.getOwnerUserId(),
                entity.getOwnerVendorId(), entity.getAssigneeVendorId(), entity.getReleaseId(),
                entity.getEstimateDays(), entity.getActualDays(), entity.getMrUrl(),
                entity.getExpectedDeliveryDate(), entity.getPlanStartDate(), entity.getPlanEndDate(),
                entity.getClosedAt(), entity.getUpdatedAt(), rollup,
                allowedActions(entity.getType(), entity.getStatus(), entity.getReporterId()), Map.of());
    }

    private void rejectStatusInBody(Map<String, Object> extra) {
        if (extra != null && extra.containsKey("status")) {
            throw RequirementErrors.statusNotWritable();
        }
    }

    private void applyRule(RequirementRules.Violation violation) {
        if (violation != null) {
            throw RequirementErrors.rule(violation);
        }
    }

    private static String normalizeType(String type) {
        String normalized = type == null ? "" : type.trim().toUpperCase();
        if (!RequirementRules.isKnownType(normalized)) {
            throw RequirementErrors.validation("type 只能是 BUS/PDR/RD");
        }
        return normalized;
    }

    private static String normalizePriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return "P2";
        }
        String normalized = priority.trim().toUpperCase();
        if (!List.of("P0", "P1", "P2", "P3").contains(normalized)) {
            throw RequirementErrors.validation("priority 只能是 P0/P1/P2/P3");
        }
        return normalized;
    }

    private static String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw RequirementErrors.validation("title 必填");
        }
        String trimmed = title.trim();
        if (trimmed.length() > 200) {
            throw RequirementErrors.validation("title 不能超过 200 字");
        }
        return trimmed;
    }
}
