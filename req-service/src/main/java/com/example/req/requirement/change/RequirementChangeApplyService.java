package com.example.req.requirement.change;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.req.approval.WorkflowJson;
import com.example.req.approval.WorkflowVersion;
import com.example.req.release.ReleaseEntity;
import com.example.req.release.ReleaseRepository;
import com.example.req.release.ReleaseRequirementEntity;
import com.example.req.release.ReleaseRequirementRepository;
import com.example.req.release.ReleaseService;
import com.example.req.requirement.RequirementEntity;
import com.example.req.requirement.RequirementErrors;
import com.example.req.requirement.RequirementRepository;
import com.example.req.requirement.RequirementService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 变更单的<b>应用</b>（AC-6/AC-7/AC-8/AC-12）：一次事务把「通过了的单据」变成需求字段。
 *
 * <p>十二步顺序是有意的，任何一步调换都会破坏一条不变量：</p>
 * <ol>
 *   <li>状态闸：非 APPROVED 一律 409（APPLIED 单独给「已生效」错误码，不能混成「未通过」）；</li>
 *   <li>需求行锁：{@code SELECT … FOR UPDATE}，让并发 apply/双写串行化；</li>
 *   <li>逐字段比对 before：期间被别人改过就 409 且<b>零写</b>（绝不覆盖别人的改动）；</li>
 *   <li>复验：父链/环/人天/目标版本可能在审批期间变过，apply 必须重跑；</li>
 *   <li>条件状态更新 {@code APPROVED → APPLIED}：0 行说明同单已被应用/被撤销，409；</li>
 *   <li>RELEASE 双写：先移关联行再写字段（绕过版本锁定闸，由 4 的前置校验兜底）；</li>
 *   <li>写需求字段（只写真实变化过的字段）；</li>
 *   <li>父需求人天汇总重算；</li>
 *   <li>req_change_item 每字段一行——items 只在 apply 成功后存在，它就是「基线」；</li>
 *   <li>一条 {@code REQUIREMENT_CHANGE_APPLIED} 审计（自动化按字段派生事件）；</li>
 *   <li>锁定/已发布版本上应用时补 {@code CHANGE_AFTER_RELEASE_LOCK}；</li>
 *   <li>返回详情（canApply 此时已为 false）。</li>
 * </ol>
 */
@Service
public class RequirementChangeApplyService {

    /** 审计动作：字段事件的唯一来源（AutomationEventMapper 按 detail 的 before/after 派生）。 */
    static final String ACTION_APPLIED = "REQUIREMENT_CHANGE_APPLIED";
    /** 留痕动作：在 LOCKED/PUBLISHED 版本上应用（不是内容变化，刻意不映射字段事件）。 */
    static final String ACTION_AFTER_LOCK = "CHANGE_AFTER_RELEASE_LOCK";
    /** 审计口径与审批实例一致：按变更单号收口（需求 id 放 detail），深链/门禁可按单号直查。 */
    static final String BIZ_TYPE = WorkflowVersion.BIZ_REQ_CHANGE;

    private final RequirementChangeRepository changes;
    private final RequirementChangeItemRepository items;
    private final RequirementRepository requirements;
    private final RequirementService requirementService;
    private final RequirementChangeValidator validator;
    private final RequirementChangeService changeService;
    private final RequirementReleaseMembership membership;
    private final ReleaseRepository releases;
    private final ReleaseRequirementRepository releaseMembers;
    private final AuditService auditService;
    private final WorkflowJson json;

    public RequirementChangeApplyService(RequirementChangeRepository changes,
                                         RequirementChangeItemRepository items,
                                         RequirementRepository requirements,
                                         RequirementService requirementService,
                                         RequirementChangeValidator validator,
                                         RequirementChangeService changeService,
                                         RequirementReleaseMembership membership,
                                         ReleaseRepository releases,
                                         ReleaseRequirementRepository releaseMembers,
                                         AuditService auditService,
                                         WorkflowJson json) {
        this.changes = changes;
        this.items = items;
        this.requirements = requirements;
        this.requirementService = requirementService;
        this.validator = validator;
        this.changeService = changeService;
        this.membership = membership;
        this.releases = releases;
        this.releaseMembers = releaseMembers;
        this.auditService = auditService;
        this.json = json;
    }

    @Transactional
    public RequirementChangeViews.Detail apply(long changeId) {
        DataScope scope = TenantContext.require();
        RequirementChangeEntity change = changes.findByIdAndTenantId(changeId, scope.tenantId())
                .orElseThrow(() -> RequirementErrors.notFound("变更单", changeId));

        // 1. 状态闸。APPLIED 必须单独判：用 CHANGE_NOT_APPROVED 说「已应用」是错的提示。
        //    状态闸只读单据、不加载需求实体，放在锁前可以省一次无谓的行锁。
        if (RequirementChangeFields.STATUS_APPLIED.equals(change.getStatus())) {
            throw RequirementErrors.changeAlreadyApplied(change.getAppliedAt());
        }
        if (!RequirementChangeFields.STATUS_APPROVED.equals(change.getStatus())) {
            throw RequirementErrors.changeNotApproved(change.getStatus());
        }

        // 2. 需求行锁必须先于任何「加载需求实体」的调用：requireVisible 走 findById，会把
        //    RequirementEntity 装进持久化上下文；随后 FOR UPDATE 查询拿到并发事务提交的新版本行，
        //    Hibernate 会抛 StaleObjectStateException（两个并发 apply 一个 500）。
        //    先锁再 requireWritable：后者复用同一持久化上下文里的实例，不触发版本冲突。
        //    行锁同时是并发 apply / apply 与 PUT 双写的唯一串行点。
        RequirementEntity entity = requirements
                .findWithLockByIdAndTenantId(change.getRequirementId(), scope.tenantId())
                .orElseThrow(() -> RequirementErrors.notFound("需求", change.getRequirementId()));
        requirementService.requireWritable(entity.getId());

        Map<String, Object> before = readSnapshot(change, change.getBeforeJson());
        Map<String, Object> after = readSnapshot(change, change.getAfterJson());
        List<String> fields = RequirementChangeFields.changedFields(before, after);
        Map<String, Object> current = RequirementChangeFields.snapshot(entity);

        // 3. 逐字段比对（不是整单比对）：报错要指出到底哪个字段被谁改过，UI 才能说人话。
        for (String field : fields) {
            if (!RequirementChangeFields.sameValue(before.get(field), current.get(field))) {
                throw RequirementErrors.changeConflict(field, before.get(field), current.get(field));
            }
        }

        // 4. 复验：审批期间树/版本可能已经变了。
        validator.validateApply(entity, after);

        // 5. 条件状态更新：并发下只有一条线程能把 APPROVED 推成 APPLIED。
        int updated = changes.markApplied(changeId, scope.tenantId(),
                RequirementChangeFields.STATUS_APPROVED, LocalDateTime.now(), scope.userId());
        if (updated != 1) {
            throw RequirementErrors.changeConflict("status", RequirementChangeFields.STATUS_APPROVED,
                    change.getStatus());
        }

        Long oldParentId = entity.getParentId();

        // 6. RELEASE 双写：目标版本校验已由 4 完成，这里绕过 ReleaseService 的「只有 PLAN 可改」闸。
        if (fields.contains(RequirementChangeFields.F_RELEASE_ID)) {
            membership.move(entity, asLong(after.get(RequirementChangeFields.F_RELEASE_ID)),
                    scope.userId());
        }

        // 7/8. 写字段 + 父需求汇总。只写变化字段，未变字段保持数据库里的现有值。
        writeFields(entity, fields, after);
        entity.setUpdatedBy(scope.userId());
        requirements.save(entity);
        refreshRollup(oldParentId, entity.getParentId());

        // 9. items：apply 之后才有行，因此「有没有 items」就是「生没生效」的物理证据。
        writeItems(changeId, fields, before, after);

        // 10. 一条审计；字段事件由 AutomationEventMapper 从 before/after 派生。
        auditService.record(ACTION_APPLIED, BIZ_TYPE, change.getId(),
                auditDetail(change, entity, fields, before, after));

        // 11. 版本锁定留痕（PLAN 不写；锁定/已发布才写）。
        auditAfterReleaseLock(change, entity, scope.tenantId());

        // 12. 详情：此时 status=APPLIED、items 已存在、canApply=false。
        return changeService.detail(changeId);
    }

    // ------------------------------------------------------------------ 写字段

    private void writeFields(RequirementEntity entity, List<String> fields, Map<String, Object> after) {
        for (String field : fields) {
            Object value = RequirementChangeFields.normalize(field, after.get(field));
            switch (field) {
                case RequirementChangeFields.F_TITLE -> entity.setTitle((String) value);
                case RequirementChangeFields.F_DESCRIPTION -> entity.setDescription((String) value);
                case RequirementChangeFields.F_PRIORITY -> entity.setPriority((String) value);
                case RequirementChangeFields.F_EXPECTED_DELIVERY_DATE ->
                        entity.setExpectedDeliveryDate((LocalDate) value);
                case RequirementChangeFields.F_ESTIMATE_DAYS ->
                        entity.setEstimateDays((BigDecimal) value);
                case RequirementChangeFields.F_PARENT_ID -> entity.setParentId((Long) value);
                case RequirementChangeFields.F_RELEASE_ID -> entity.setReleaseId((Long) value);
                default -> throw new IllegalStateException("未知的变更字段: " + field);
            }
        }
    }

    private void refreshRollup(Long oldParentId, Long newParentId) {
        if (!Objects.equals(oldParentId, newParentId) && oldParentId != null) {
            requirementService.refreshParentRollup(requirementService.requireVisible(oldParentId));
        }
        if (newParentId != null) {
            requirementService.refreshParentRollup(requirementService.requireVisible(newParentId));
        }
    }

    private void writeItems(long changeId, List<String> fields, Map<String, Object> before,
                            Map<String, Object> after) {
        List<RequirementChangeItemEntity> rows = new ArrayList<>(fields.size());
        for (String field : fields) {
            RequirementChangeItemEntity row = new RequirementChangeItemEntity();
            row.setChangeId(changeId);
            row.setField(field);
            row.setBeforeValue(RequirementChangeFields.asText(before.get(field)));
            row.setAfterValue(RequirementChangeFields.asText(after.get(field)));
            rows.add(row);
        }
        if (!rows.isEmpty()) {
            items.saveAll(rows);
        }
    }

    // ------------------------------------------------------------------ 审计

    /**
     * 审计 detail：除技术方案要求的 {@code changeId/fields/before/after} 外，补齐 §4.2 快照键。
     * 缺这些键，自动化规则里「type = RD」「status = …」的条件在变更事件上永远不命中。
     *
     * <p>before/after 只带**变化过**的字段：字段事件按这两张表逐键比对，带上未变字段会在
     * 语义等价但表示不同（null vs ""）时多派一条事件。</p>
     */
    private Map<String, Object> auditDetail(RequirementChangeEntity change, RequirementEntity entity,
                                            List<String> fields, Map<String, Object> before,
                                            Map<String, Object> after) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("changeId", change.getId());
        detail.put("requirementId", entity.getId());
        detail.put("changeType", change.getChangeType());
        detail.put("fields", fields);
        detail.put("type", entity.getType());
        detail.put("status", entity.getStatus());
        detail.put("priority", entity.getPriority());
        detail.put("estimateDays", entity.getEstimateDays());
        detail.put("releaseId", entity.getReleaseId());
        detail.put("ownerUserId", entity.getOwnerUserId());
        detail.put("domainId", entity.getDomainId());
        detail.put("ownerVendorId", entity.getOwnerVendorId());
        detail.put("assigneeVendorId", entity.getAssigneeVendorId());
        detail.put("before", subset(before, fields));
        detail.put("after", subset(after, fields));
        return detail;
    }

    private static Map<String, Object> subset(Map<String, Object> source, List<String> fields) {
        Map<String, Object> picked = new LinkedHashMap<>();
        for (String field : fields) {
            picked.put(field, source.get(field));
        }
        return picked;
    }

    /**
     * 11. 锁定/已发布版本上的应用必须留痕（AC-10）。
     *
     * <p>查「需求所属的全部版本」而不是只看 {@code releaseId}：需求可能同时挂在多个版本上
     * （历史关联），只要其中一个锁了，这次应用就发生在锁之后。</p>
     */
    private void auditAfterReleaseLock(RequirementChangeEntity change, RequirementEntity entity,
                                       long tenantId) {
        List<ReleaseRequirementEntity> memberships =
                releaseMembers.findByTenantIdAndRequirementId(tenantId, entity.getId());
        Map<String, Object> statuses = new LinkedHashMap<>();
        List<Long> locked = new ArrayList<>();
        for (ReleaseRequirementEntity row : memberships) {
            ReleaseEntity release = releases.findByIdAndTenantId(row.getReleaseId(), tenantId)
                    .orElse(null);
            if (release == null) {
                continue;
            }
            statuses.put(String.valueOf(release.getId()), release.getStatus());
            if (ReleaseService.STATUS_LOCKED.equals(release.getStatus())
                    || ReleaseService.STATUS_PUBLISHED.equals(release.getStatus())) {
                locked.add(release.getId());
            }
        }
        if (locked.isEmpty()) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("changeId", change.getId());
        detail.put("requirementId", entity.getId());
        detail.put("releaseId", entity.getReleaseId());
        detail.put("lockedReleaseIds", locked);
        detail.put("statuses", statuses);
        auditService.record(ACTION_AFTER_LOCK, BIZ_TYPE, change.getId(), detail);
    }

    // ------------------------------------------------------------------ 读取辅助

    /**
     * 快照是 apply 的输入，损坏必须硬失败（500）而不是降级成空表：空 before 会让「比对」
     * 变成「没有期望值」，进而把冲突误判成通过。展示用的 {@link WorkflowJson#readMap} 才降级。
     */
    private Map<String, Object> readSnapshot(RequirementChangeEntity change, String raw) {
        Map<String, Object> parsed = json.readMap(raw);
        if (parsed.isEmpty()) {
            throw new IllegalStateException(
                    "变更单快照缺失或损坏: changeId=" + change.getId());
        }
        return parsed;
    }

    private static Long asLong(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        String text = raw.toString().strip();
        if (text.isEmpty()) {
            return null;
        }
        return Long.valueOf(text);
    }
}
