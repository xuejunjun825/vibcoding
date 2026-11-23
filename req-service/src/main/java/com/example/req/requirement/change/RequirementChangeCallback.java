package com.example.req.requirement.change;

import com.example.platform.contract.ApprovalCallback;
import com.example.req.approval.WorkflowVersion;
import org.springframework.stereotype.Component;

/**
 * 变更单的审批回调：<b>只推进单据状态，不碰需求字段</b>。
 *
 * <p>「审批通过」与「变更生效」是两件事（AC-5）：这里最多把 {@code PENDING} 改成 {@code APPROVED}，
 * 需求字段由 {@link RequirementChangeApplyService} 的显式 apply 写。否则审批一通过，需求就被悄悄改了，
 * 既没有冲突校验也没有字段级留痕。
 *
 * <p>回调跑在引擎事务里，所以条件更新命中 0 行（重复回调、已被 apply 抢先、已驳回）时<b>不抛异常</b>：
 * 抛了会把引擎已经写完的审批结果一起回滚。
 */
@Component
public class RequirementChangeCallback implements ApprovalCallback {

    private final RequirementChangeRepository changes;

    public RequirementChangeCallback(RequirementChangeRepository changes) {
        this.changes = changes;
    }

    @Override
    public String bizType() {
        return WorkflowVersion.BIZ_REQ_CHANGE;
    }

    @Override
    public void onApproved(long bizId, long instanceId) {
        transition(bizId, RequirementChangeFields.STATUS_APPROVED);
    }

    @Override
    public void onRejected(long bizId, long instanceId, String reason) {
        transition(bizId, RequirementChangeFields.STATUS_REJECTED);
    }

    @Override
    public void onWithdrawn(long bizId, long instanceId) {
        transition(bizId, RequirementChangeFields.STATUS_WITHDRAWN);
    }

    /**
     * {@code PENDING → next} 的条件更新就是并发闸：同一张单被重复回调时第二次命中 0 行，
     * 不会把 {@code APPLIED} 退回 {@code APPROVED}。
     */
    private void transition(long changeId, String next) {
        RequirementChangeEntity row = changes.findById(changeId).orElse(null);
        if (row == null || row.getTenantId() == null) {
            return;
        }
        changes.markStatus(changeId, row.getTenantId(), RequirementChangeFields.STATUS_PENDING, next);
    }
}
