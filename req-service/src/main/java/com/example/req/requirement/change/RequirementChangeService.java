package com.example.req.requirement.change;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.security.PermissionService;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.PermissionMatrix;
import com.example.req.approval.ApprovalEngine;
import com.example.req.approval.PageSlice;
import com.example.req.approval.StartApproval;
import com.example.req.approval.WorkflowJson;
import com.example.req.approval.WorkflowVersion;
import com.example.req.requirement.RequirementEntity;
import com.example.req.requirement.RequirementErrors;
import com.example.req.requirement.RequirementService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 变更单的<b>创建与读取</b>（apply 在 {@link RequirementChangeApplyService}）。
 *
 * <p>创建 = 校验 + 冻结 before/after + 冻结影响 + 起第 6 类审批 + 审计，同一个事务：审批起不来就整单回滚，
 * 不会留下「有单没有审批」的孤儿。审批通过<b>不等于</b>生效 —— 回调只把 {@code PENDING} 改成
 * {@code APPROVED}，字段要等显式 apply 才写（AC-5）。
 */
@Service
public class RequirementChangeService {

    /** apply 动作码：与 {@code ActionRegistry} / {@code PermissionRouteTable} 同一口径。 */
    static final String ACTION_CHANGE_APPLY = "req.action.change_apply";
    /** 变更单挂在需求池菜单下，不新开菜单码。 */
    static final String MENU_REQ_POOL = "req.pool";
    /** 与其它列表页一致的页大小上限。 */
    static final int MAX_PAGE_SIZE = 100;

    private static final Set<String> STATUSES = Set.of(
            RequirementChangeFields.STATUS_PENDING,
            RequirementChangeFields.STATUS_APPROVED,
            RequirementChangeFields.STATUS_REJECTED,
            RequirementChangeFields.STATUS_WITHDRAWN,
            RequirementChangeFields.STATUS_APPLIED);

    private final RequirementService requirements;
    private final RequirementChangeRepository changes;
    private final RequirementChangeItemRepository items;
    private final RequirementChangeValidator validator;
    private final RequirementChangeImpactAssessor assessor;
    private final ObjectProvider<ApprovalEngine> engineProvider;
    private final AuditService auditService;
    private final WorkflowJson json;
    private final PermissionService permissions;

    public RequirementChangeService(RequirementService requirements,
                                    RequirementChangeRepository changes,
                                    RequirementChangeItemRepository items,
                                    RequirementChangeValidator validator,
                                    RequirementChangeImpactAssessor assessor,
                                    ObjectProvider<ApprovalEngine> engineProvider,
                                    AuditService auditService,
                                    WorkflowJson json,
                                    PermissionService permissions) {
        this.requirements = requirements;
        this.changes = changes;
        this.items = items;
        this.validator = validator;
        this.assessor = assessor;
        this.engineProvider = engineProvider;
        this.auditService = auditService;
        this.json = json;
        this.permissions = permissions;
    }

    // ------------------------------------------------------------------ create

    /**
     * 发起变更：先插单再起审批（审批要 bizId），两者同事务。
     *
     * <p>before/after 在这里<b>一次冻结</b>：之后需求被别人改了，apply 会逐字段比对并 409，
     * 而不是把别人的改动悄悄覆盖掉。
     */
    @Transactional
    public RequirementChangeViews.Created create(long requirementId,
                                                 RequirementChangeDtos.CreateRequest request) {
        RequirementEntity entity = requirements.requireWritable(requirementId);
        DataScope scope = TenantContext.require();
        String changeType = request.changeType();
        Map<String, Object> before = RequirementChangeFields.snapshot(entity);
        Map<String, Object> after = validator.validateCreate(entity, changeType, request.reason(), request.patch());
        List<String> fields = RequirementChangeFields.changedFields(before, after);
        Map<String, Object> impact = assessor.assess(entity, changeType, after);

        RequirementChangeEntity row = new RequirementChangeEntity();
        row.setTenantId(scope.tenantId());
        row.setRequirementId(requirementId);
        row.setChangeType(changeType);
        row.setStatus(RequirementChangeFields.STATUS_PENDING);
        row.setReason(reasonText(request.reason()));
        row.setBeforeJson(json.writeStable(before));
        row.setAfterJson(json.writeStable(after));
        row.setImpactJson(json.writeStable(impact));
        row.setCreatedBy(scope.userId());
        row = changes.save(row);

        ApprovalEngine engine = engineProvider.getIfAvailable();
        if (engine == null) {
            // 与工时评估同口径：引擎不在（未启用审批）就是 503，不留半张单。
            throw RequirementErrors.approvalUnavailable();
        }
        long instanceId = engine.start(new StartApproval(
                WorkflowVersion.BIZ_REQ_CHANGE,
                row.getId(),
                entity.getType(),
                entity.getDomainId(),
                approvalAttributes(requirementId, changeType, fields),
                null,
                null)).id();
        row.setInstanceId(instanceId);
        changes.save(row);

        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("changeId", row.getId());
        audit.put("requirementId", requirementId);
        audit.put("changeType", changeType);
        audit.put("fields", fields);
        audit.put("instanceId", instanceId);
        // 审计按变更单收口（biz_type=REQ_CHANGE, biz_id=changeId）：审批中心与深链都要能按单号直查；
        // requirementId 放 detail，既不丢需求维度，也不再把一张变更单的审计挂到需求 id 上。
        auditService.record("CHANGE_REQUESTED", WorkflowVersion.BIZ_REQ_CHANGE, row.getId(), audit);

        return new RequirementChangeViews.Created(row.getId(), row.getStatus(), instanceId, impact);
    }

    /**
     * 预览影响：同一条校验链 + 同一个 assessor，<b>不写库、不起审批</b>。
     *
     * <p>抽屉第 ④ 步必须打这个端点：受影响发布单只有服务端算得出来，前端不许自己编。
     */
    @Transactional(readOnly = true)
    public RequirementChangeViews.Preview preview(long requirementId,
                                                  RequirementChangeDtos.CreateRequest request) {
        RequirementEntity entity = requirements.requireWritable(requirementId);
        String changeType = request.changeType();
        Map<String, Object> before = RequirementChangeFields.snapshot(entity);
        Map<String, Object> after = validator.validateCreate(entity, changeType, request.reason(), request.patch());
        return new RequirementChangeViews.Preview(changeType, before, after,
                RequirementChangeFields.changedFields(before, after),
                assessor.assess(entity, changeType, after));
    }

    // ------------------------------------------------------------------ queries

    /** 需求详情第 10 个 Tab 的列表（按创建时间倒序）。 */
    @Transactional(readOnly = true)
    public PageSlice<RequirementChangeViews.Row> list(long requirementId, String status, int page, int size) {
        requirements.requireVisible(requirementId);
        DataScope scope = TenantContext.require();
        String normalized = normalizeStatus(status);
        requireValidPage(page, size);
        Page<RequirementChangeEntity> found = changes.page(scope.tenantId(), requirementId, normalized,
                PageRequest.of(page - 1, size));
        List<RequirementChangeViews.Row> rows = new ArrayList<>(found.getContent().size());
        for (RequirementChangeEntity row : found.getContent()) {
            rows.add(toRow(row));
        }
        return new PageSlice<>(rows, found.getTotalElements(), page, size);
    }

    /** 变更详情（深链页）；{@code canApply} 由服务端算，前端不再复刻双依赖。 */
    @Transactional(readOnly = true)
    public RequirementChangeViews.Detail detail(long changeId) {
        DataScope scope = TenantContext.require();
        RequirementChangeEntity row = changes.findByIdAndTenantId(changeId, scope.tenantId())
                .orElseThrow(() -> RequirementErrors.notFound("变更单", changeId));
        RequirementEntity entity = requirements.requireVisible(row.getRequirementId());
        return toDetail(row, entity);
    }

    // ------------------------------------------------------------------ mapping

    /**
     * 行上的 before/after 已是冻结快照，{@code fields} 就地算，零额外查询。
     *
     * <p>空快照（历史行或未携带快照）返回空列表而不是 null：列表接口必须保持 200，
     * 前端把空列表显示成「—」，不区分「没有字段」与「字段未知」。</p>
     */
    RequirementChangeViews.Row toRow(RequirementChangeEntity row) {
        Map<String, Object> before = json.readMap(row.getBeforeJson());
        Map<String, Object> after = json.readMap(row.getAfterJson());
        List<String> fields = (before == null || before.isEmpty()
                || after == null || after.isEmpty())
                ? List.of()
                : RequirementChangeFields.changedFields(before, after);
        return new RequirementChangeViews.Row(row.getId(), row.getChangeType(), row.getStatus(),
                row.getReason(), row.getCreatedBy(), row.getCreatedAt(), row.getAppliedAt(),
                fields, assessor.summary(json.readMap(row.getImpactJson())));
    }

    RequirementChangeViews.Detail toDetail(RequirementChangeEntity row, RequirementEntity entity) {
        List<RequirementChangeViews.Item> applied = new ArrayList<>();
        for (RequirementChangeItemEntity item : items.findByChangeIdOrderByFieldAsc(row.getId())) {
            applied.add(new RequirementChangeViews.Item(item.getChangeId(), item.getField(),
                    item.getBeforeValue(), item.getAfterValue()));
        }
        RequirementChangeViews.RequirementRef ref = new RequirementChangeViews.RequirementRef(
                entity.getId(), entity.getCode(), entity.getTitle(), entity.getType(), entity.getStatus());
        return new RequirementChangeViews.Detail(row.getId(), ref, row.getChangeType(), row.getStatus(),
                row.getReason(), json.readMap(row.getBeforeJson()), json.readMap(row.getAfterJson()),
                applied, json.readMap(row.getImpactJson()), row.getInstanceId(), row.getAppliedAt(),
                row.getAppliedBy(), row.getCreatedBy(), row.getCreatedAt(), canApply(row));
    }

    /** 只有「已通过 + 有 change_apply」才给应用：审批通过但没权限的人看到的是不可点的原因，不是按钮。 */
    boolean canApply(RequirementChangeEntity row) {
        if (!RequirementChangeFields.STATUS_APPROVED.equals(row.getStatus())) {
            return false;
        }
        return permissions.decide(MENU_REQ_POOL, ACTION_CHANGE_APPLY) != PermissionMatrix.Access.DENY;
    }

    private Map<String, Object> approvalAttributes(long requirementId, String changeType, List<String> fields) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("requirementId", requirementId);
        attributes.put("changeType", changeType);
        attributes.put("fields", fields);
        attributes.put("fieldCount", fields.size());
        return attributes;
    }

    private static String reasonText(String reason) {
        return reason == null ? "" : reason.strip();
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.strip().toUpperCase();
        if (!STATUSES.contains(normalized)) {
            throw RequirementErrors.validation("未知的变更状态", Map.of("field", "status", "value", status));
        }
        return normalized;
    }

    private static void requireValidPage(int page, int size) {
        if (page < 1) {
            throw RequirementErrors.validation("page 必须 >= 1", Map.of("field", "page", "value", page));
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw RequirementErrors.validation("size 必须在 1.." + MAX_PAGE_SIZE + " 之间",
                    Map.of("field", "size", "value", size));
        }
    }
}
