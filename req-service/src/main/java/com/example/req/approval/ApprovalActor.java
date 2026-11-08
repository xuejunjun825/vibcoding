package com.example.req.approval;

/** 当前审批动作的发起人（租户 + 用户）。审批域内部只认这个值对象，不直接读线程上下文。 */
public record ApprovalActor(long tenantId, long userId) {

    public boolean is(long candidateUserId) {
        return candidateUserId == userId;
    }
}
