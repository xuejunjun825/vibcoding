package com.example.req.deploy;

import java.util.List;

/**
 * 发布单状态机（纯函数，不碰数据库，也不依赖 Spring）。
 *
 * <pre>
 * DRAFT ─submit(准入全过)→ PENDING_APPROVAL ─审批通过→ APPROVED ─登记执行→ EXECUTING
 *   │                            │                                  │
 *   │                            └审批驳回→ REJECTED ─submit→ …      └→ SUCCESS / FAILED / ROLLED_BACK
 *   └forceRelease(PMO)→ APPROVED           └forceRelease→ APPROVED
 * </pre>
 *
 * <p>终态是三种执行结果：单据不再可变，统计只读它。允许的动作集中在这里，
 * 控制器/前端/脚本都从同一处得到结论（不允许各处手写 if）。</p>
 */
public final class DeployOrderStates {

    public static final String DRAFT = "DRAFT";
    public static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String EXECUTING = "EXECUTING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String ROLLED_BACK = "ROLLED_BACK";

    public static final String ACTION_SUBMIT = "submit";
    public static final String ACTION_FORCE_RELEASE = "forceRelease";
    public static final String ACTION_EXECUTION = "execution";

    public static final List<String> STATES = List.of(DRAFT, PENDING_APPROVAL, APPROVED, REJECTED,
            EXECUTING, SUCCESS, FAILED, ROLLED_BACK);

    private DeployOrderStates() {
    }

    public static boolean canSubmit(String status) {
        return DRAFT.equals(status) || REJECTED.equals(status);
    }

    public static boolean canForceRelease(String status) {
        return DRAFT.equals(status) || REJECTED.equals(status);
    }

    public static boolean canRecordExecution(String status) {
        return APPROVED.equals(status) || EXECUTING.equals(status);
    }

    public static boolean isTerminal(String status) {
        return SUCCESS.equals(status) || FAILED.equals(status) || ROLLED_BACK.equals(status);
    }

    public static boolean isExecutionResult(String result) {
        return SUCCESS.equals(result) || FAILED.equals(result) || ROLLED_BACK.equals(result);
    }

    public static List<String> allowedActions(String status) {
        if (canSubmit(status)) {
            return List.of(ACTION_SUBMIT, ACTION_FORCE_RELEASE);
        }
        if (canRecordExecution(status)) {
            return List.of(ACTION_EXECUTION);
        }
        return List.of();
    }
}
