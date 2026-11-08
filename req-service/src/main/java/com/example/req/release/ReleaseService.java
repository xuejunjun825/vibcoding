package com.example.req.release;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.code.CodeGenerator;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.req.approval.ApprovalEngine;
import com.example.req.approval.StartApproval;
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

    private static final String COLUMNS = """
            r.id AS id, r.code AS code, r.name AS name, r.domain_id AS domain_id, r.status AS status,
            r.start_date AS start_date, r.end_date AS end_date,
            r.planned_release_date AS planned_release_date, r.gate_mode AS gate_mode,
            r.locked_at AS locked_at, r.released_at AS released_at
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

    public ReleaseService(ReleaseRepository releaseRepository,
                          ReleaseRequirementRepository releaseRequirementRepository,
                          MilestoneRepository milestoneRepository,
                          RequirementRepository requirementRepository,
                          RequirementStateMachine stateMachine,
                          ScopedJdbc jdbc,
                          CodeGenerator codeGenerator,
                          AuditService auditService,
                          ObjectProvider<ApprovalEngine> approvalEngine,
                          ReleasePublishCascade publishCascade) {
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
        auditService.record("CREATE", BIZ_TYPE, saved.getId(), Map.of(
                "code", saved.getCode(), "domainId", cmd.domainId(), "gateMode", gateMode));
        return toView(saved);
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
        return new ReleaseDetail(toView(release), requirements, groups(requirements),
                stats(requirements, release), riskItems(requirements, release), milestones);
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
            requirement.setReleaseId(releaseId);
            requirement.setUpdatedBy(scope.userId());
            requirementRepository.save(requirement);
        }
        auditService.record("RELEASE_MEMBER_ADD", BIZ_TYPE, releaseId, Map.of(
                "requirementIds", requirementIds, "count", requirementIds.size()));
        return detail(releaseId);
    }

    @Transactional
    public void removeRequirement(long releaseId, long requirementId) {
        DataScope scope = TenantContext.require();
        ReleaseEntity release = requireVisible(releaseId);
        requireEditable(release);
        releaseRequirementRepository.deleteByTenantIdAndReleaseIdAndRequirementId(
                scope.tenantId(), releaseId, requirementId);
        requirementRepository.findByIdAndTenantId(requirementId, scope.tenantId())
                .filter(requirement -> Objects.equals(requirement.getReleaseId(), releaseId))
                .ifPresent(requirement -> {
                    requirement.setReleaseId(null);
                    requirement.setUpdatedBy(scope.userId());
                    requirementRepository.save(requirement);
                });
        auditService.record("RELEASE_MEMBER_REMOVE", BIZ_TYPE, releaseId, Map.of(
                "requirementId", requirementId));
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

    @Transactional
    public long startApproval(long id) {
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
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("domainId", release.getDomainId());
        attributes.put("total", requirementsOf(release).size());
        attributes.put("gateMode", release.getGateMode());
        long instanceId = engine.start(new StartApproval(BIZ_TYPE, id, null, release.getDomainId(),
                attributes, null, null)).id();
        release.setStatus(STATUS_LOCKED);
        release.setLockedAt(LocalDateTime.now());
        release.setUpdatedBy(scope.userId());
        releaseRepository.save(release);
        auditService.record("RELEASE_APPROVAL_START", BIZ_TYPE, id, Map.of(
                "instanceId", instanceId, "status", STATUS_LOCKED));
        return instanceId;
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

        ReleasePublishCascade.Result result = publishCascade.cascade(release);
        auditService.record("RELEASE_PUBLISH", BIZ_TYPE, id, Map.of(
                "status", STATUS_PUBLISHED,
                "rdClosed", result.rdClosed(),
                "pdrDelivered", result.pdrDelivered(),
                "releasedAt", String.valueOf(release.getReleasedAt())));
        return toView(release);
    }

    /** 审批驳回/撤回：单据回到 PLAN，保留成员与里程碑（D3：不回退需求状态）。 */
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
        return release;
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
                null, actions, Map.of());
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
        return new ReleaseView(entity.getId(), entity.getCode(), entity.getName(), entity.getDomainId(),
                entity.getStatus(), entity.getStartDate(), entity.getEndDate(),
                entity.getPlannedReleaseDate(), entity.getGateMode(), entity.getLockedAt(),
                entity.getReleasedAt(), actionsOf(entity.getStatus()));
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
                actions);
    }

    /** 创建版本入参。 */
    public record CreateRelease(String name, Long domainId, LocalDate startDate, LocalDate endDate,
                                LocalDate plannedReleaseDate, String gateMode) {
    }
}
