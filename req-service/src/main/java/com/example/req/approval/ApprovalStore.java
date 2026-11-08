package com.example.req.approval;

import java.util.List;
import java.util.Optional;

/**
 * 审批引擎的持久化端口。
 *
 * <p>存在的理由有两个：一是引擎的状态推进逻辑必须能在<b>没有 MySQL</b> 的情况下被逐条断言（AC-6/7/8/9），
 * 二是加锁顺序（先实例、后任务）只能在一个地方表达，否则两个入口加锁顺序相反就会死锁。
 * 生产实现是 {@link JpaApprovalStore}。
 */
public interface ApprovalStore {

    // ---- 实例 ----

    /** 悲观写锁读取实例；不存在 → 404。 */
    ApprovalInstance lockInstance(long instanceId);

    Optional<ApprovalInstance> instance(long instanceId);

    Optional<ApprovalInstance> activeInstance(long tenantId, String bizType, long bizId);

    ApprovalInstance saveInstance(ApprovalInstance instance);

    // ---- 任务 ----

    ApprovalTask lockTask(long taskId);

    Optional<ApprovalTask> task(long taskId);

    ApprovalTask saveTask(ApprovalTask task);

    List<ApprovalTask> tasks(long instanceId);

    List<ApprovalTask> pendingAt(long instanceId, String nodeKey);

    List<ApprovalTask> tasksOf(long instanceId, String nodeKey, long approverId);

    long pendingCount(long instanceId);

    // ---- 流水 / 抄送 ----

    void appendHistory(ApprovalHistory history);

    List<ApprovalHistory> history(long instanceId);

    Optional<ApprovalHistory> lastAction(long instanceId, long taskId, String action);

    void appendCc(ApprovalCc cc);

    List<ApprovalCc> ccs(long instanceId);

    // ---- 审批中心 ----

    PageSlice<ApprovalTask> todoTasks(long tenantId, long approverId, int page, int size);

    PageSlice<ApprovalTask> doneTasks(long tenantId, long approverId, int page, int size);

    PageSlice<ApprovalInstance> myInstances(long tenantId, long initiatorId, int page, int size);
}
