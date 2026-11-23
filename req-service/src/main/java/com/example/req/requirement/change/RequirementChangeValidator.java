package com.example.req.requirement.change;

import com.example.req.release.ReleaseEntity;
import com.example.req.release.ReleaseService;
import com.example.req.requirement.RequirementEntity;
import com.example.req.requirement.RequirementErrors;
import com.example.req.requirement.RequirementRepository;
import com.example.req.requirement.RequirementRules;
import com.example.req.requirement.RequirementStateMachine;
import com.example.platform.common.tenant.TenantContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 变更单的创建/应用前置校验。
 *
 * <p>创建即拦（AC-11）：类型、原因、白名单、无变化、父链、祖先环、人天、日期、目标版本，
 * 全部在落库前按固定次序判完，错误码与需求 PUT 同口径；apply 时再跑一遍树/版本部分
 * （创建到应用之间，父需求或目标版本可能已被别人改动）。</p>
 */
@Component
public class RequirementChangeValidator {

    private static final int REASON_MAX = 500;

    private final RequirementRepository requirements;
    private final ReleaseService releases;
    private final RequirementStateMachine stateMachine;

    public RequirementChangeValidator(RequirementRepository requirements,
                                      ReleaseService releases,
                                      RequirementStateMachine stateMachine) {
        this.requirements = requirements;
        this.releases = releases;
        this.stateMachine = stateMachine;
    }

    /**
     * @return 合并并归一化后的 after 快照（7 字段全量），供创建落库与 impact 评估共用
     */
    public Map<String, Object> validateCreate(RequirementEntity entity, String changeType,
                                              String reason, Map<String, Object> patch) {
        // 1) 变更类型：未知类型没有白名单，detail 的 allowedFields 给空数组
        if (!RequirementChangeFields.knownType(changeType)) {
            throw RequirementErrors.changeFieldNotAllowed(changeType, null, List.of());
        }
        // 2) 原因：必填、≤500；创建时就是审批人看到的唯一说明
        if (reason == null || reason.strip().isEmpty()) {
            throw RequirementErrors.reasonRequired();
        }
        if (reason.strip().length() > REASON_MAX) {
            throw RequirementErrors.validation("原因最多 " + REASON_MAX + " 字");
        }
        // 3) 白名单：先判全局字段名，再判是否属于该变更类型
        List<String> allowed = RequirementChangeFields.allowedFields(changeType);
        if (patch == null || patch.isEmpty()) {
            throw RequirementErrors.changeFieldNotAllowed(changeType, null, allowed);
        }
        for (String field : patch.keySet()) {
            if (!RequirementChangeFields.FIELDS.contains(field) || !allowed.contains(field)) {
                throw RequirementErrors.changeFieldNotAllowed(changeType, field, allowed);
            }
        }
        // 4) 归一化 + 差异：一个字段都没变就不该产生单据
        Map<String, Object> before = RequirementChangeFields.snapshot(entity);
        Map<String, Object> after = RequirementChangeFields.merge(before, patch);
        if (RequirementChangeFields.changedFields(before, after).isEmpty()) {
            throw RequirementErrors.validation("没有任何字段变化");
        }
        // 5) 业务前置（apply 时复用同一段）
        validateBusiness(entity, after);
        return after;
    }

    /** apply 复验：创建到应用之间父需求/目标版本可能已变，必须按当前事实重判。 */
    public void validateApply(RequirementEntity entity, Map<String, Object> after) {
        if (stateMachine.isTerminal(entity.getType(), entity.getStatus())
                && !stateMachine.allowedActions(entity.getType(), entity.getStatus()).contains("reopen")) {
            throw RequirementErrors.statusNotWritable();
        }
        validateBusiness(entity, after);
    }

    // ------------------------------------------------------------------ 业务前置

    private void validateBusiness(RequirementEntity entity, Map<String, Object> after) {
        String type = entity.getType();
        // 5a) 内容字段：归一化已保证格式，这里只防空值（父需求标题/优先级不允许被清空）
        String title = (String) after.get(RequirementChangeFields.F_TITLE);
        if (title == null || title.isBlank()) {
            throw RequirementErrors.validation("title 必填");
        }
        Object priority = after.get(RequirementChangeFields.F_PRIORITY);
        if (priority == null || String.valueOf(priority).isBlank()) {
            throw RequirementErrors.validation("priority 只能是 P0/P1/P2/P3");
        }
        // 5b) 人天：父需求只读汇总，RD 0.5 天精度——与 PUT 同一份规则
        BigDecimal afterDays = asDecimal(after.get(RequirementChangeFields.F_ESTIMATE_DAYS));
        throwIfViolation(RequirementRules.checkDays(type, afterDays, entity.getActualDays()));
        // 5c) 父链：类型组合 → 祖先环 → 父状态门（创建时父可能还没入池/排期）
        Long parentId = asLong(after.get(RequirementChangeFields.F_PARENT_ID));
        RequirementEntity parent = parentId == null ? null : requireParent(parentId);
        throwIfViolation(RequirementRules.checkParentCombination(type,
                parent == null ? null : parent.getType()));
        throwIfViolation(RequirementRules.checkAncestorCycle(entity.getId(), parentId,
                id -> requirements.findByIdAndTenantId(id, TenantContext.require().tenantId())
                        .map(RequirementEntity::getParentId)
                        .orElse(null)));
        if (parent != null) {
            RequirementRules.ParentGate gate =
                    RequirementRules.parentGate(parent.getType(), parent.getStatus());
            if (!gate.allowed()) {
                throw RequirementErrors.rule(RequirementRules.parentGateViolation(
                        parent.getId(), parent.getStatus(), gate));
            }
        }
        // 5d) 目标版本：可见、存在、非 CANCELLED；null 表示移出全部版本
        Object rawReleaseId = after.get(RequirementChangeFields.F_RELEASE_ID);
        if (rawReleaseId != null) {
            ReleaseEntity target = releases.requireVisible(asLong(rawReleaseId));
            if (ReleaseService.STATUS_CANCELLED.equals(target.getStatus())) {
                throw RequirementErrors.validation("目标版本已取消");
            }
        }
    }

    private RequirementEntity requireParent(long parentId) {
        return requirements.findByIdAndTenantId(parentId, TenantContext.require().tenantId())
                .orElseThrow(() -> RequirementErrors.rule(new RequirementRules.Violation(
                        400, RequirementRules.INVALID_PARENT,
                        "父需求不存在或不可见", Map.of("parentId", parentId))));
    }

    private static void throwIfViolation(RequirementRules.Violation violation) {
        if (violation != null) {
            throw RequirementErrors.rule(violation);
        }
    }

    private static BigDecimal asDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(String.valueOf(value).trim());
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : Long.parseLong(text);
    }
}
