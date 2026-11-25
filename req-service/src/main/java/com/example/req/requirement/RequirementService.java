package com.example.req.requirement;

import com.example.platform.common.audit.ActualPeriodRecorder;
import com.example.platform.common.audit.AuditService;
import com.example.platform.common.code.CodeGenerator;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.notify.NotificationWriter;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.ActionPermissionResolver;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.GateDecision;
import com.example.platform.contract.GatePort;
import com.example.platform.contract.RequirementActionMatrix;
import com.example.platform.contract.RequirementEditability;
import com.example.req.integration.sonar.SonarProjectKeys;
import com.example.req.pdr.PdrReviewService;
import com.example.req.pdr.PdrViews;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
    /**
     * 动作档位的运行期查询端口（r22）：唯一实现是 {@code PermissionService}。
     *
     * <p>一次注入、两处复用（{@code authorize} 与 {@code allowedActions}）：实现内部按请求做
     * ThreadLocal memo，每次 new 或每个动作新建实例都会退化成多次快照查询，还会让「按钮列表」
     * 与「执行判定」读到不同版本——那正是 r17 之前「按钮能点、接口 403」的来源。</p>
     */
    private final ActionPermissionResolver actionPermissions;
    /**
     * 工时评估门（r26）。用 Provider 注入：{@link WbsEstimateService} 反过来依赖本服务
     * （提交单据要读需求），直接构造会成环。
     */
    private final ObjectProvider<WbsEstimateService> estimateGate;
    /**
     * 需求标签读路径（r39 Q-1）：详情与写回响应必须和列表走同一个查询，否则同一需求
     * 「列表有 chip、详情没有」。产品代码一律经 {@link #tagsOf(long)} 读，List 页面走
     * {@code RequirementPoolQuery} 的批量版（一次一页，避免 N+1）。
     */
    private final RequirementTagQuery tagQuery;
    /**
     * 需求↔版本关联的移动端口（r40）：早期态 PUT 改动 releaseId 时要真正双写关联行，
     * 晚期态由变更单 apply 调用。Provider 注入是为了让十参单测构造继续可用。
     */
    private final ObjectProvider<com.example.req.requirement.change.RequirementReleaseMembership> releaseMembership;
    /**
     * 项目组（r41）：创建/更新时校验 {@code projectId} 存在、同租户、可写。十参单测构造为 null
     * （那些测试不挂项目，见 {@link #requireProjectWritable}）。
     */
    private final com.example.req.project.ProjectService projectService;
    /**
     * PDR 评审门（r41）。setter 注入而不是构造器参数：本类有十参「单测构造」与十三参
     * 「容器构造」两个入口，再抬一个参数会让既有十几个测试同时编不过；未注入时门整体放行
     * （那些单测只验需求语义，不验评审门）。
     */
    private PdrReviewService pdrReview;



    /** 十参构造：保留给既有单测（不装配评估门与标签端口时门/estimate/tags 均不生效）。 */
    public RequirementService(RequirementRepository repository,
                              RequirementDomainRelRepository domainRelRepository,
                              RequirementStateMachine stateMachine,
                              CodeGenerator codeGenerator,
                              AuditService auditService,
                              NotificationWriter notificationWriter,
                              ObjectProvider<GatePort> gatePort,
                              ObjectProvider<RequirementApprovalStarter> approvalStarter,
                              JdbcTemplate jdbcTemplate,
                              ActionPermissionResolver actionPermissions) {
        this(repository, domainRelRepository, stateMachine, codeGenerator, auditService,
                notificationWriter, gatePort, approvalStarter, jdbcTemplate, actionPermissions,
                null, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RequirementService(RequirementRepository repository,
                              RequirementDomainRelRepository domainRelRepository,
                              RequirementStateMachine stateMachine,
                              CodeGenerator codeGenerator,
                              AuditService auditService,
                              NotificationWriter notificationWriter,
                              ObjectProvider<GatePort> gatePort,
                              ObjectProvider<RequirementApprovalStarter> approvalStarter,
                              JdbcTemplate jdbcTemplate,
                              ActionPermissionResolver actionPermissions,
                              ObjectProvider<WbsEstimateService> estimateGate,
                              RequirementTagQuery tagQuery,
                              ObjectProvider<com.example.req.requirement.change.RequirementReleaseMembership>
                                      releaseMembership,
                              com.example.req.project.ProjectService projectService) {
        this.repository = repository;
        this.domainRelRepository = domainRelRepository;
        this.stateMachine = stateMachine;
        this.codeGenerator = codeGenerator;
        this.auditService = auditService;
        this.notificationWriter = notificationWriter;
        this.gatePort = gatePort;
        this.approvalStarter = approvalStarter;
        this.jdbcTemplate = jdbcTemplate;
        this.actionPermissions = actionPermissions;
        this.estimateGate = estimateGate;
        this.tagQuery = tagQuery;
        this.releaseMembership = releaseMembership;
        this.projectService = projectService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPdrReview(PdrReviewService pdrReview) {
        this.pdrReview = pdrReview;
    }

    private PdrReviewService pdrGate() {
        return pdrReview;
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
            requireParentGate(parent);                       // ← 唯一状态门调用点
        } else {
            applyRule(RequirementRules.checkParentCombination(type, null));
        }
        requireProjectWritable(cmd.projectId());

        RequirementEntity entity = new RequirementEntity();
        entity.setTenantId(scope.tenantId());
        entity.setType(type);
        entity.setCode(codeGenerator.next(type, scope.tenantId()));
        entity.setTitle(title);
        entity.setDescription(cmd.description());
        entity.setPriority(priority);
        entity.setStatus(stateMachine.initialStatus(type));
        entity.setParentId(parent == null ? null : parent.getId());
        entity.setProjectId(cmd.projectId());
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

        // 审计同时是自动化事件的唯一来源（r39）：这里除既有键外补齐 §4.2 的整份快照键，
        // 缺的写 null 占位 —— 条件求值只认快照，缺键就是不命中，绝不回读实时行。
        Map<String, Object> createDetail = new LinkedHashMap<>();
        createDetail.put("type", type);
        createDetail.put("code", saved.getCode());
        createDetail.put("status", saved.getStatus());
        createDetail.put("parentId", Objects.requireNonNullElse(saved.getParentId(), 0L));
        createDetail.put("priority", saved.getPriority());
        createDetail.put("estimateDays", saved.getEstimateDays());
        createDetail.put("domainId", saved.getDomainId());
        createDetail.put("ownerUserId", saved.getOwnerUserId());
        createDetail.put("releaseId", saved.getReleaseId());
        createDetail.put("ownerVendorId", saved.getOwnerVendorId());
        createDetail.put("assigneeVendorId", saved.getAssigneeVendorId());
        auditService.record("CREATE", BIZ_TYPE, saved.getId(), createDetail);
        return new Created(saved.getId(), saved.getCode(), saved.getType(), saved.getStatus(),
                allowedActions(saved.getType(), saved.getStatus(), saved.getReporterId()));
    }

    // ---------------------------------------------------------------- update

    @Transactional
    public View update(long id, RequirementDtos.UpdateRequest req) {
        DataScope scope = TenantContext.require();
        rejectStatusInBody(req.extra());
        // ① 先可见性、后矩阵：终态需求的可写判定由矩阵的 LOCKED 接管（409 FIELD_LOCKED），
        //    不再在入口先抛 400「需求已关闭」——否则 AC-7 的错误码永远走不到。
        RequirementEntity entity = requireVisible(id);
        Long oldParentId = entity.getParentId();
        // projectId 不在 RequirementEditability 的 11 字段矩阵里（V17 新列、无变更单语义）：
        // 只作为「挂项目」的独立补丁，不改变既有 409 判定顺序。
        Long projectToAttach = req.projectId != null
                && !Objects.equals(req.projectId, entity.getProjectId())
                ? req.projectId : null;
        // 真正的 before 必须在 applyXxx 之前取：applyDaysChange 会直接写 entity.estimateDays，
        // 放在后面取快照会把「新值」当成旧值，FIELD_CHANGE 就永远不产生。
        Map<String, Object> before = auditSnapshot(entity,
                String.valueOf(entity.getExpectedDeliveryDate()));
        // ② 真实变化判定：11 字段快照 vs 请求 patch，两边都做「空域→主域」归一后比较。
        //    入参校验会就地写 entity，所以判定必须在 applyXxx 之前完成。
        List<Long> currentDomains = domainsOf(id);
        Map<String, Object> guardBefore = RequirementEditGuard.snapshot(entity, currentDomains);
        List<String> changed = RequirementEditGuard.changed(
                guardBefore, RequirementEditGuard.patch(req, currentDomains));

        if (changed.isEmpty()) {
            if (!RequirementEditGuard.carriesActualDays(req) && projectToAttach == null) {
                // 全部同值：200 / 零写库 / 零审计。「保存」按钮重复提交不该刷新 updatedAt。
                return toView(entity, null);
            }
            // actualDays 不是矩阵字段（只是父需求只读），保持 r40 的终态 400 并照旧落库。
            requireWritable(id);
        } else {
            // ③ 两类 409：LOCKED 优先，一次只报一类；必须在任何写库/关联行移动之前。
            RequirementEditGuard.reject(id, entity.getType(), entity.getStatus(), changed);
        }

        // ④ 以下与 r40 一致：矩阵通过后才是既有业务校验（非法父链/人天精度仍是 400）。
        applyParentChange(entity, req.extra());
        applyDaysChange(entity, req.extra());
        applySonarKeyChange(entity, req.extra());

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

        // F2：releaseId 以前在 PUT 里被静默忽略。早期态允许直接改，但必须真正移动关联行，
        // 否则 requirement.release_id 变了、release_requirement 没变，版本侧看不到它。
        applyReleaseChange(entity, req.extra(), scope.userId());

        if (projectToAttach != null) {
            requireProjectWritable(projectToAttach);
            entity.setProjectId(projectToAttach);
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
        auditService.record("UPDATE", BIZ_TYPE, entity.getId(), Map.of(
                "before", before, "after", auditSnapshot(entity, null)));
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
        if (parent != null) {
            requireParentGate(parent);                       // 父未变时上面已早退，不会走到这里
        }
        entity.setParentId(parentId);
    }

    /**
     * 父状态门的唯一服务端调用点：decompose 经 create 自动同源，PUT 改父复用同一判定。
     * applyRule 对 null（门通过）无操作。
     */
    private void requireParentGate(RequirementEntity parent) {
        RequirementRules.ParentGate gate =
                RequirementRules.parentGate(parent.getType(), parent.getStatus());
        applyRule(RequirementRules.parentGateViolation(parent.getId(), parent.getStatus(), gate));
    }

    /**
     * 提报校验用：当前租户下真实存在的业务域 id。
     *
     * <p>必须按 tenant 全量读、不能按调用者的域过滤：越权域要落到 403，被过滤掉就变成
     * 「不存在」400 了。放在这里而不是校验器里，是因为 L9（读路径单点）只给本类开了
     * JdbcTemplate 白名单。</p>
     */
    public List<Long> knownDomainIds(long tenantId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        Object[] args = new Object[ids.size() + 1];
        args[0] = tenantId;
        for (int i = 0; i < ids.size(); i++) {
            args[i + 1] = ids.get(i);
        }
        return jdbcTemplate.queryForList(
                "SELECT id FROM biz_domain WHERE tenant_id = ? AND id IN (" + placeholders + ")",
                Long.class, args);
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

    /**
     * 早期态 PUT 改 releaseId：真正移动关联行（r40 F2）。
     *
     * <p>晚期态的该字段已被 {@code RequirementEditGuard} 按矩阵拦成 409 FIELD_LOCKED，所以执行到这里
     * 一定是早期态或值未变。值未变时不移动，避免「提交整张表单」也 delete+insert 一遍。
     * 十参单测没有装关联端口，此时跳过（那类单测本来就不覆盖 release 双写）。</p>
     */
    private void applyReleaseChange(RequirementEntity entity, Map<String, Object> extra, long actorId) {
        if (extra == null || !extra.containsKey("releaseId")) {
            return;
        }
        Long target = SysDb.asNullableLong(extra.get("releaseId"));
        if (Objects.equals(target, entity.getReleaseId())) {
            return;
        }
        com.example.req.requirement.change.RequirementReleaseMembership membership =
                releaseMembership == null ? null : releaseMembership.getIfAvailable();
        if (membership == null) {
            return;
        }
        membership.move(entity, target, actorId);
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
        // 可编辑性：一次 rules() 得到 11 字段的完整决策，再按三态投影成三个数组。
        // 三个数组与 permissions.editableFields 必须同源于这一次调用，否则两套来源必然漂移。
        List<RequirementEditability.Rule> rules =
                RequirementEditability.rules(entity.getType(), entity.getStatus());
        List<Long> domainIds = domainsOf(id);
        return new Detail(toView(entity, rollup), childViews,
                pdrFiltered(entity, gateFiltered(entity,
                        allowedActions(entity.getType(), entity.getStatus(), entity.getReporterId()))),
                changePermissions(entity),
                project(rules, RequirementEditability.Decision.EDIT),
                project(rules, RequirementEditability.Decision.CHANGE_REQUIRED),
                project(rules, RequirementEditability.Decision.LOCKED),
                domainIds, entity.getSonarProjectKey(), estimateOf(entity), pdrDetailOf(entity));
    }

    /** 详情三数组的唯一投影点：过滤后只留 {@code field + reasonCode}，顺序天然是 contract 的 FIELDS 序。 */
    private static List<RequirementViews.FieldRule> project(
            List<RequirementEditability.Rule> rules, RequirementEditability.Decision decision) {
        return rules.stream()
                .filter(rule -> rule.decision() == decision)
                .map(rule -> new RequirementViews.FieldRule(rule.field(), rule.reasonCode()))
                .toList();
    }

    /** 工时评估块的唯一入口（BUS 为 null，未评估为完整 NONE 对象）。 */
    private WbsEstimateViews.EstimateView estimateOf(RequirementEntity entity) {
        WbsEstimateService gate = estimateGate();
        return gate == null ? null : gate.view(entity);
    }

    /** allowedActions 的受门动作过滤（r26）：与接口 409 判定同源。 */
    private List<String> gateFiltered(RequirementEntity entity, List<String> actions) {
        WbsEstimateService gate = estimateGate();
        return gate == null ? actions : gate.applyGate(entity, actions);
    }

    private WbsEstimateService estimateGate() {
        return estimateGate == null ? null : estimateGate.getIfAvailable();
    }

    // ------------------------------------------------------------ PDR 评审门（r41）

    /**
     * 顺序门（设计 §3.2）：PDR 自己必须已有通过结论；RD 只在父 PDR 已经进入过评审时才要求通过。
     *
     * <p>父 PDR 用「宽松门」：从没发起过评审的存量 PDR 下挂着 RD，不该在 r41 上线当天被追溯卡死；
     * 但一旦父 PDR 开始评审，就必须以通过结论为准。返回空 = 放行。</p>
     */
    private Optional<PdrReviewService.Gate> pdrGateFor(RequirementEntity entity) {
        PdrReviewService pdr = pdrGate();
        if (pdr == null || entity == null) {
            return Optional.empty();
        }
        if (RequirementRules.TYPE_PDR.equals(entity.getType())) {
            return pdr.gate(entity.getId());
        }
        if (entity.getParentId() == null) {
            return Optional.empty();
        }
        RequirementEntity parent = requireVisible(entity.getParentId());
        if (!RequirementRules.TYPE_PDR.equals(parent.getType())) {
            return Optional.empty();
        }
        return pdr.gateIfStarted(parent.getId());
    }

    /** 被评审门拦住就抛 409 PDR_REVIEW_REQUIRED（detail 带 reviewId 与 required）。 */
    private void requirePdrPassed(RequirementEntity entity) {
        pdrGateFor(entity).ifPresent(gate -> {
            throw RequirementErrors.pdrReviewRequired(entity.getId(), gate.reviewId(), gate.status());
        });
    }

    /** 列表/详情下发的动作里剔除被评审门拦住的那些（当前只有 {@code schedule}）。 */
    private List<String> pdrFiltered(RequirementEntity entity, List<String> actions) {
        if (actions == null || actions.isEmpty() || !actions.contains("schedule")) {
            return actions;
        }
        return pdrGateFor(entity).isPresent() ? withoutSchedule(actions) : actions;
    }

    private static List<String> withoutSchedule(List<String> actions) {
        if (!actions.contains("schedule")) {
            return actions;
        }
        List<String> next = new ArrayList<>(actions.size());
        for (String action : actions) {
            if (!"schedule".equals(action)) {
                next.add(action);
            }
        }
        return next;
    }

    /** 详情里的评审块：没有评审单时也返回一个完整对象（review 为 null），前端不必判空。 */
    private PdrViews.ReviewDetail pdrDetailOf(RequirementEntity entity) {
        PdrReviewService pdr = pdrGate();
        return pdr == null ? null : pdr.detailForRequirement(entity.getId());
    }

    /**
     * 换厂商窗口（R3）：RD 只在 TODO（尚未开工）可换；PDR 在 PENDING_ANALYSIS/SCHEDULED 可换。
     * 其它类型沿用「终态以外都可换」的旧行为，避免 r41 顺手改掉别人的语义。
     */
    private static boolean vendorChangeWindowOpen(RequirementEntity entity) {
        String status = entity.getStatus();
        if (RequirementRules.TYPE_RD.equals(entity.getType())) {
            return "TODO".equals(status);
        }
        if (RequirementRules.TYPE_PDR.equals(entity.getType())) {
            return "PENDING_ANALYSIS".equals(status) || "SCHEDULED".equals(status);
        }
        return true;
    }

    /**
     * R3 第二半：PDR 的换厂商窗口还要看它的子 RD——任一子 RD 离开 {@code TODO} 就 409。
     *
     * <p>为什么非查子行不可：{@link #vendorChangeWindowOpen} 只读需求自己的状态，而 PDR 的
     * {@code SCHEDULED} 恰好是允许换厂商的窗口；于是「RD 已经在开发中、PDR 还停在 SCHEDULED」
     * 这条最常见的真实路径会静默把厂商换掉——旧厂商已经写下去的代码白写，新厂商接手的是一段
     * 不属于它的进度。子 RD 才是承诺本身：它一开工，换厂商就不再是改一个字段。</p>
     *
     * <p>detail 必须点名挡路的是哪一条（{@code blockingChildId} + {@code status}）：只说
     * 「已锁定」的话，产品经理在 20 条子需求里找不到该先协调谁。</p>
     */
    private void requireNoStartedChild(RequirementEntity entity) {
        if (!RequirementRules.TYPE_PDR.equals(entity.getType())) {
            return;
        }
        List<Map<String, Object>> started = jdbcTemplate.queryForList("""
                SELECT id, status FROM requirement
                 WHERE tenant_id = ? AND parent_id = ? AND type = ? AND status <> 'TODO'
                 ORDER BY id
                """, TenantContext.require().tenantId(), entity.getId(), RequirementRules.TYPE_RD);
        if (started.isEmpty()) {
            return;
        }
        Map<String, Object> blocking = started.get(0);
        throw RequirementErrors.vendorLockedByChild(entity.getId(),
                String.valueOf(blocking.get("status")), Rows.toLong(blocking.get("id"), 0L));
    }

    /** 换厂商通知：厂商侧没有「厂商负责人」字段，最小改动就是给该厂商的账号各发一遍。 */
    private void notifyVendorChanged(RequirementEntity entity, Long fromVendor, Long toVendor,
                                     String reason) {
        notifyVendor(entity, fromVendor, "需求已改派其他厂商",
                "需求 " + entity.getCode() + " 已从贵司改派：" + reason);
        notifyVendor(entity, toVendor, "需求已改派贵司",
                "需求 " + entity.getCode() + " 已改派贵司：" + reason);
    }

    private void notifyVendor(RequirementEntity entity, Long vendorId, String title, String content) {
        if (vendorId == null || vendorId <= 0) {
            return;
        }
        List<Long> users = jdbcTemplate.queryForList(
                "SELECT id FROM sys_user WHERE tenant_id = ? AND vendor_id = ? ORDER BY id LIMIT 20",
                Long.class, TenantContext.require().tenantId(), vendorId);
        for (Long userId : users) {
            notificationWriter.send(userId, "VENDOR_CHANGED", title, content, BIZ_TYPE, entity.getId());
        }
    }

    /** 厂商名（r41 评估卡展示）：查不到返回 null，前端回退显示 id 或「内部」。 */
    String vendorNameOf(Long vendorId) {
        if (vendorId == null || vendorId <= 0) {
            return null;
        }
        List<String> names = jdbcTemplate.queryForList(
                "SELECT name FROM sys_vendor WHERE tenant_id = ? AND id = ?",
                String.class, TenantContext.require().tenantId(), vendorId);
        return names.isEmpty() ? null : names.get(0);
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
        // 显式转型：r39 新增了同样四参 + Map 的重载，裸 null 会在这里产生歧义
        return transition(id, action, comment, source, (Long) null);
    }

    @Transactional
    public TransitionResult transition(long id, String action, String comment, String source,
                                       Long definitionId) {
        return transition(id, action, comment, source, definitionId, null);
    }

    /**
     * 审批路径用（r39）：把 {@code approvalInstanceId / approvalResult / automationOrigin} 这类
     * 事件需要的键随 STATE_CHANGE 一起写进审计 detail（只加键，不改既有键）。
     */
    @Transactional
    public TransitionResult transition(long id, String action, String comment, String source,
                                       Map<String, Object> extra) {
        return transition(id, action, comment, source, null, extra);
    }

    @Transactional
    public TransitionResult transition(long id, String action, String comment, String source,
                                       Long definitionId, Map<String, Object> extra) {
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
                    gateFiltered(entity,
                            allowedActions(entity.getType(), from, entity.getReporterId())));
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
        // r41 顺序门：PDR 未过评审不能排期（与 assign-vendor/提交评估同一条门，§3.2）。
        if (entity != null && "schedule".equals(action)) {
            requirePdrPassed(entity);
        }
        // r26 工时评估门：唯一插点。位置固定——403（requireWritable/authorize）→ 409
        // ILLEGAL_TRANSITION（stateMachine.target）→ r23 既有门 → 本门；被拦时不落库、不写
        // STATE_CHANGE、不发通知，因此这里只抛异常，不做任何写入。
        if (entity != null) {
            WbsEstimateService gate = estimateGate();
            if (gate != null && gate.gated(entity.getType(), action)) {
                gate.ensureGate(entity, action);
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

        // 审计是自动化事件的唯一来源：既有的 from/to/action/source/comment/operator 全部保留，
        // 另补 §4.2 快照键（type/priority/estimateDays/...），否则「按类型 + 按优先级」的条件
        // 在状态变更事件上永远拿不到值。
        Map<String, Object> stateDetail = auditSnapshot(entity, null);
        stateDetail.put("from", Objects.requireNonNullElse(from, ""));
        stateDetail.put("to", target);
        stateDetail.put("action", action);
        stateDetail.put("source", source);
        stateDetail.put("comment", Objects.requireNonNullElse(comment, ""));
        stateDetail.put("operator", scope.userId());
        if (extra != null) {
            stateDetail.putAll(extra);
        }
        auditService.record("STATE_CHANGE", BIZ_TYPE, entity.getId(), stateDetail);
        notifyTransition(entity, from, target, comment);
        return new TransitionResult(entity.getId(), target,
                gateFiltered(entity,
                        allowedActions(entity.getType(), target, entity.getReporterId())));
    }

    // -------------------------------------------------------- estimate callback

    /**
     * 需求当前值的审计快照（r39 §4.2）。审计 detail 里的这些键会被
     * {@code AutomationEventMapper} 原样搬进事件 payload：条件求值只读快照，所以「缺键」等价
     * 「不命中」，绝不能靠运行时回读行来补。
     *
     * @param expectedDeliveryDate 为 null 时不带该键（update 的 after 只关心可比字段）
     */
    private static Map<String, Object> auditSnapshot(RequirementEntity entity,
                                                     String expectedDeliveryDate) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", entity.getTitle());
        snapshot.put("type", entity.getType());
        snapshot.put("priority", entity.getPriority());
        snapshot.put("status", entity.getStatus());
        snapshot.put("estimateDays", entity.getEstimateDays());
        snapshot.put("domainId", entity.getDomainId());
        snapshot.put("ownerUserId", entity.getOwnerUserId());
        snapshot.put("releaseId", entity.getReleaseId());
        snapshot.put("ownerVendorId", entity.getOwnerVendorId());
        snapshot.put("assigneeVendorId", entity.getAssigneeVendorId());
        if (expectedDeliveryDate != null) {
            snapshot.put("expectedDeliveryDate", expectedDeliveryDate);
        }
        return snapshot;
    }

    /**
     * 评估通过后落人天（r26，由 {@code WbsEstimateCallback} 在引擎事务内调用）。
     * 需求状态不变——评估单只管人天。
     */
    @Transactional
    public void applyApprovedEstimate(long requirementId, BigDecimal approvedDays, long instanceId) {
        if (approvedDays == null) {
            return;
        }
        DataScope scope = TenantContext.require();
        RequirementEntity entity = repository.findByIdAndTenantId(requirementId, scope.tenantId())
                .orElseThrow(() -> RequirementErrors.notFound("需求", requirementId));
        BigDecimal previousDays = entity.getEstimateDays();
        entity.setEstimateDays(RequirementRollup.scale(approvedDays));
        entity.setUpdatedBy(scope.userId());
        repository.save(entity);
        if (entity.getParentId() != null) {
            refreshParentRollup(requireVisible(entity.getParentId()));
        }
        // before.estimateDays 是 FIELD_CHANGE 的「旧值」来源：没有它，人天变化事件只有新值，
        // 规则无法区分「涨了」和「降了」。
        Map<String, Object> estimateBefore = new LinkedHashMap<>();
        estimateBefore.put("estimateDays", previousDays);
        Map<String, Object> estimateDetail = auditSnapshot(entity, null);
        estimateDetail.put("instanceId", instanceId);
        estimateDetail.put("estimateDays", entity.getEstimateDays());
        estimateDetail.put("before", estimateBefore);
        auditService.record("ESTIMATE_APPLIED", BIZ_TYPE, requirementId, estimateDetail);
    }

    /** 写一条需求动态（评估提交/通过/驳回/撤回），审计表即动态数据源。 */
    @Transactional
    public void recordEstimateEvent(long requirementId, String action,
                                    Map<String, Object> detail) {
        auditService.record(action, BIZ_TYPE, requirementId,
                detail == null ? Map.of() : detail);
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
        // r41 顺序门（§3.2）：PDR 未过评审不能指派厂商；换厂商另有窗口限制（R3）。
        requirePdrPassed(entity);
        Long previousVendorId = entity.getAssigneeVendorId();
        boolean changed = previousVendorId != null && !previousVendorId.equals(req.vendorId());
        if (changed && !vendorChangeWindowOpen(entity)) {
            throw RequirementErrors.vendorLocked(entity.getId(), entity.getStatus());
        }
        if (changed) {
            // R3：PDR 自己停在可换窗口 ≠ 可以换——子 RD 已经开工的，先撞这条（门禁 B4-2）。
            requireNoStartedChild(entity);
        }
        // 用可空 Map（不是 Map.of + 0L 占位）：0 会被事件层当成真实旧值，"0 -> 新厂商" 会凭空
        // 产生一条 owner_vendor_id 变更事件。
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("assigneeVendorId", entity.getAssigneeVendorId());
        before.put("ownerVendorId", entity.getOwnerVendorId());
        before.put("ownerUserId", entity.getOwnerUserId());

        entity.setAssigneeVendorId(req.vendorId());
        entity.setOwnerVendorId(req.vendorId());
        if (req.ownerUserId() != null) {
            entity.setOwnerUserId(req.ownerUserId());
        }
        entity.setUpdatedBy(scope.userId());
        repository.save(entity);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("assigneeVendorId", entity.getAssigneeVendorId());
        after.put("ownerVendorId", entity.getOwnerVendorId());
        after.put("ownerUserId", entity.getOwnerUserId());
        auditService.record("VENDOR_TRANSFER", BIZ_TYPE, entity.getId(),
                Map.of("before", before, "after", after, "reason", req.reason()));
        if (entity.getOwnerUserId() != null) {
            notificationWriter.send(entity.getOwnerUserId(), "VENDOR_TRANSFER", "需求转派",
                    "需求 " + entity.getCode() + " 已转派：" + req.reason(), BIZ_TYPE, entity.getId());
        }
        if (changed) {
            // R3：换厂商的专属审计 + 通知新旧厂商 + 原 APPROVED 评估单派生 STALE。
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("requirementId", entity.getId());
            detail.put("fromVendorId", previousVendorId);
            detail.put("toVendorId", req.vendorId());
            detail.put("reason", req.reason());
            auditService.record("VENDOR_CHANGED", BIZ_TYPE, entity.getId(), detail);
            notifyVendorChanged(entity, previousVendorId, req.vendorId(), req.reason());
            WbsEstimateService gate = estimateGate();
            if (gate != null) {
                gate.markStaleByVendor(entity, previousVendorId, req.vendorId());
            }
        }
        return toView(entity, null);
    }

    // -------------------------------------------------------- assignOwner (r39)

    /**
     * 自动化动作 ASSIGN_OWNER 的唯一入口（r39）。
     *
     * <p>为什么需要独立入口：借 {@code transition} 改负责人会把「状态机 + 审批 + 门禁」全部卷进来，
     * 语义直接错位；而 {@code assignVendor} 是转厂商、不是转人。改负责人不动状态，所以它必须是一条
     * 不走状态机的窄路径，lint L21 只在这里放行自动化包写入。</p>
     */
    @Transactional
    public void assignOwner(long requirementId, long ownerUserId, String reason) {
        assignOwner(requirementId, ownerUserId, reason, 0L, 0L, 0);
    }

    /**
     * @param ruleId      触发本次指派的规则（0 = 非规则来源）
     * @param runId       本次执行 id（0 = 非规则来源）
     * @param chainDepth  规则链深度；动作执行器传入「父事件 + 1」，0 表示不设置（默认 1）
     */
    @Transactional
    public void assignOwner(long requirementId, long ownerUserId, String reason,
                            long ruleId, long runId, int chainDepth) {
        DataScope scope = TenantContext.require();
        RequirementEntity entity = requireWritable(requirementId);
        Long previousOwner = entity.getOwnerUserId();
        if (previousOwner != null && previousOwner == ownerUserId) {
            // 值没变就不写事件：否则「指向自己的规则」会靠审计自己喂自己，防循环只能靠深度兜底。
            return;
        }
        entity.setOwnerUserId(ownerUserId);
        entity.setUpdatedBy(scope.userId());
        repository.save(entity);

        Map<String, Object> change = new LinkedHashMap<>();
        change.put("before", previousOwner);
        change.put("after", ownerUserId);
        Map<String, Object> detail = auditSnapshot(entity, null);
        detail.put("ownerUserId", change);
        detail.put("source", "RULE");
        detail.put("reason", reason == null ? "" : reason);
        detail.put("ruleId", ruleId);
        detail.put("runId", runId);
        detail.put("automationOrigin", "RULE");
        if (chainDepth > 0) {
            detail.put("automationChainDepth", chainDepth);
        }
        auditService.record("OWNER_ASSIGN", BIZ_TYPE, requirementId, detail);
        notificationWriter.send(ownerUserId, "ASSIGN_OWNER", "需求指派",
                "需求 " + entity.getCode() + " 已指派给你", BIZ_TYPE, requirementId);
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
        // 数值不要写成字符串：事件层按数值比较/比较符求值，字符串会退化成字典序。
        Map<String, Object> deliverBefore = new LinkedHashMap<>();
        deliverBefore.put("estimateDays", beforeEstimate);
        deliverBefore.put("actualDays", beforeActual);
        Map<String, Object> deliverAfter = new LinkedHashMap<>();
        deliverAfter.put("estimateDays", entity.getEstimateDays());
        deliverAfter.put("actualDays", entity.getActualDays());
        deliverAfter.put("mrUrl", Objects.requireNonNullElse(entity.getMrUrl(), ""));
        auditService.record("DELIVER", BIZ_TYPE, entity.getId(),
                Map.of("before", deliverBefore, "after", deliverAfter));
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

    /**
     * 项目组可写校验（V17 {@code requirement.project_id}）：项目必须存在、同租户且调用者可见
     * （{@link com.example.req.project.ProjectService#get} 已按租户 + 域收口 → 404
     * {@code PROJECT_NOT_FOUND}），调用者还要有「组织 PDR」权限。
     *
     * <p>本轮没有独立的「项目写」动作码；项目组存在的唯一用途就是选 PDR 评审人，因此复用
     * {@code req.action.pdr_review_start}（页面 {@code pdr.review}）作为最小可行判据。
     * 十参单测构造未注入 ProjectService 时跳过（那些测试不传 projectId，行为与 r40 一致）。</p>
     */
    private void requireProjectWritable(Long projectId) {
        if (projectId == null) {
            return;
        }
        if (projectId <= 0) {
            throw RequirementErrors.validation("projectId 必须是正整数");
        }
        if (projectService == null) {
            return;
        }
        projectService.get(projectId);
        if (actionPermissions != null
                && actionPermissions.decide("pdr.review", PdrReviewService.startAction())
                        == com.example.platform.contract.PermissionMatrix.Access.DENY) {
            throw new ApiException(403, RequirementErrors.FORBIDDEN,
                    "无权把需求挂到该项目（需要 PDR 发起权限）", Map.of("projectId", projectId));
        }
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
    public void refreshParentRollup(RequirementEntity parent) {
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
        if (RequirementActionMatrix.can(action, entity.getReporterId(), caller,
                actionPermissions)) {
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

    /** 池/导出批量判定的行快照（r27）：动作判定只读这五个字段。 */
    record ActionRow(long id, String type, String status, Long reporterId, BigDecimal estimateDays,
                     Long assigneeVendorId) {
    }

    /**
     * 一页需求的动作判定（r27）：状态机 ∩ 权限矩阵逐行算完，再叠 r26 评估门。
     *
     * <p>池/搜索/导出都只调这里，与 {@link #detail} / {@link #toView} 共用同一条判定链；
     * 池查询里不再出现 {@code stateMachine.allowedActions}（那正是列表恒给
     * {@code schedule} 的根因）。</p>
     */
    Map<Long, List<String>> allowedActionsByRow(List<ActionRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        List<WbsEstimateService.GateSubject> subjects = new ArrayList<>(rows.size());
        for (ActionRow row : rows) {
            subjects.add(new WbsEstimateService.GateSubject(row.id(), row.type(), row.estimateDays(),
                    row.assigneeVendorId()));
        }
        WbsEstimateService gate = estimateGate();
        Map<Long, String> estimateStatuses = gate == null ? Map.of() : gate.statusesOf(subjects);
        // r41：PDR 行未过评审时不下发 schedule，否则列表给了按钮、点下去 409。
        PdrReviewService pdr = pdrGate();
        List<Long> pdrIds = pdr == null ? List.of() : rows.stream()
                .filter(row -> RequirementRules.TYPE_PDR.equals(row.type()))
                .map(ActionRow::id).toList();
        Map<Long, PdrReviewService.Gate> pdrGates =
                pdrIds.isEmpty() ? Map.of() : pdr.gatesOf(pdrIds, true);
        Map<Long, List<String>> result = new LinkedHashMap<>();
        for (ActionRow row : rows) {
            List<String> actions = allowedActions(row.type(), row.status(), row.reporterId());
            actions = gateFiltered(row.type(), estimateStatuses.get(row.id()), actions);
            if (pdrGates.containsKey(row.id())) {
                actions = withoutSchedule(actions);
            }
            result.put(row.id(), actions);
        }
        return result;
    }

    /** 批量路径的门过滤：评估态已由 {@link #allowedActionsByRow} 一次算出。 */
    List<String> gateFiltered(String type, String estimateStatus, List<String> actions) {
        WbsEstimateService gate = estimateGate();
        if (gate == null || estimateStatus == null) {
            return actions;
        }
        return gate.applyGate(type, estimateStatus, actions);
    }

    /** allowedActions 的唯一来源：状态机的合法集合 ∩ 调用者被授权的动作（与执行共用同一谓词）。 */
    List<String> allowedActions(String type, String status, Long reporterId) {
        return RequirementActionMatrix.filter(type, status, reporterId,
                callerOf(TenantContext.require()), actionPermissions);
    }

    /**
     * 变更单可见性（r40 §9.3）：三个键只从这里下发，前端不再复刻早期态表或双依赖判定。
     *
     * <ul>
     *   <li>{@code changeRequest}：有「发起变更」动作档且当前状态可写（终态需求不能发起）；</li>
     *   <li>{@code changeApply}：有「应用变更」动作档（是否真能应用还要看单据状态 {@code APPROVED}）；</li>
     *   <li>{@code editableFields}：<b>deprecated</b>（r43）——矩阵 EDIT ∩ r40 七字段的兼容投影，
     *       前端改读详情顶层的三数组；这里保留只为旧客户端与旧断言，来源仍是同一次矩阵判定。</li>
     * </ul>
     *
     * <p>权限只读 {@link ActionPermissionResolver}：动作是否被授权由平台统一判定，这里只做
     * 「档位 ∩ 页面」的一次查询，绝不自己拼角色/功能码。</p>
     */
    public Map<String, Object> changePermissions(RequirementEntity entity) {
        boolean request = hasAction("req.action.change_request") && isWritableNow(entity);
        boolean apply = hasAction("req.action.change_apply");
        Map<String, Object> permissions = new java.util.LinkedHashMap<>();
        permissions.put("changeRequest", request);
        permissions.put("changeApply", apply);
        permissions.put("editableFields",
                com.example.req.requirement.change.RequirementChangeFields.editableFields(
                        entity.getType(), entity.getStatus()));
        return permissions;
    }

    private boolean hasAction(String actionCode) {
        if (actionPermissions == null) {
            return false;
        }
        // 与 RequirementChangeService.canApply 同一判据：DENY 才是没有；RESTRICTED 是「有但受限」，
        // 由执行侧的数据范围判定兜底，不在可见性上提前判死（否则受限角色看不到按钮也点不了）。
        return actionPermissions.decide("req.pool", actionCode)
                != com.example.platform.contract.PermissionMatrix.Access.DENY;
    }

    private boolean isWritableNow(RequirementEntity entity) {
        return !stateMachine.isTerminalAcrossTypes(entity.getStatus());
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
                gateFiltered(entity,
                        allowedActions(entity.getType(), entity.getStatus(), entity.getReporterId())),
                changePermissions(entity),
                entity.getProjectId(),
                RequirementViews.DecomposeGate.from(
                        RequirementRules.decomposeGate(entity.getType(), entity.getStatus()),
                        entity.getStatus()),
                tagsOf(entity.getId()));
    }

    /**
     * 单条需求的标签（r39 Q-1）。
     *
     * <p>详情、create/update 写回、转派与交付响应都经 {@link #toView} 出视图，所以标签只能在这一个
     * 地方补齐：任何一处另写「空标签」都会让同一个需求在列表与详情里长得不一样（AC-14 §9.2）。</p>
     *
     * <p>十参构造（单测）不装配标签端口，此时返回空表；产品代码由 Spring 注入十一参构造。</p>
     */
    private List<RequirementViews.TagView> tagsOf(long requirementId) {
        if (tagQuery == null) {
            return List.of();
        }
        return tagQuery.byRequirementIds(List.of(requirementId))
                .getOrDefault(requirementId, List.of());
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
