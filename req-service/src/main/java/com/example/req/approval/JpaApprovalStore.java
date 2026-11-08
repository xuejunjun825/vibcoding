package com.example.req.approval;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * {@link ApprovalStore} 的 JPA 实现。
 *
 * <p>加锁顺序固定为「实例 → 任务」：所有会改动审批状态的动作都先锁 {@code wf_instance}，
 * 再锁具体 {@code wf_task}。这样即使两个审批人同时点通过，也只有一个能进入推进逻辑，
 * 另一个在拿到锁后看到任务已非 PENDING → 409。
 */
@Component
public class JpaApprovalStore implements ApprovalStore {

    private final ApprovalInstanceRepository instances;
    private final ApprovalTaskRepository tasks;
    private final ApprovalHistoryRepository histories;
    private final ApprovalCcRepository ccs;

    public JpaApprovalStore(ApprovalInstanceRepository instances,
                            ApprovalTaskRepository tasks,
                            ApprovalHistoryRepository histories,
                            ApprovalCcRepository ccs) {
        this.instances = instances;
        this.tasks = tasks;
        this.histories = histories;
        this.ccs = ccs;
    }

    @Override
    public ApprovalInstance lockInstance(long instanceId) {
        return instances.lockById(instanceId)
                .orElseThrow(() -> ApprovalException.notFound("审批实例不存在：" + instanceId));
    }

    @Override
    public Optional<ApprovalInstance> instance(long instanceId) {
        return instances.findById(instanceId);
    }

    @Override
    public Optional<ApprovalInstance> activeInstance(long tenantId, String bizType, long bizId) {
        return instances.findFirstByTenantIdAndBizTypeAndBizIdAndActiveFlag(
                tenantId, bizType, bizId, ApprovalInstance.ACTIVE);
    }

    @Override
    public ApprovalInstance saveInstance(ApprovalInstance instance) {
        return instances.save(instance);
    }

    @Override
    public ApprovalTask lockTask(long taskId) {
        return tasks.lockById(taskId)
                .orElseThrow(() -> ApprovalException.notFound("审批任务不存在：" + taskId));
    }

    @Override
    public Optional<ApprovalTask> task(long taskId) {
        return tasks.findById(taskId);
    }

    @Override
    public ApprovalTask saveTask(ApprovalTask task) {
        return tasks.save(task);
    }

    @Override
    public List<ApprovalTask> tasks(long instanceId) {
        return tasks.findByInstanceIdOrderBySortNoAscIdAsc(instanceId);
    }

    @Override
    public List<ApprovalTask> pendingAt(long instanceId, String nodeKey) {
        return tasks.findByInstanceIdAndNodeKeyAndStatusOrderBySortNoAscIdAsc(
                instanceId, nodeKey, ApprovalTask.STATUS_PENDING);
    }

    @Override
    public List<ApprovalTask> tasksOf(long instanceId, String nodeKey, long approverId) {
        return tasks.findByInstanceIdAndNodeKeyAndApproverIdOrderBySignNoDesc(instanceId, nodeKey, approverId);
    }

    @Override
    public long pendingCount(long instanceId) {
        return tasks.countByInstanceIdAndStatus(instanceId, ApprovalTask.STATUS_PENDING);
    }

    @Override
    public void appendHistory(ApprovalHistory history) {
        histories.save(history);
    }

    @Override
    public List<ApprovalHistory> history(long instanceId) {
        return histories.findByInstanceIdOrderByIdAsc(instanceId);
    }

    @Override
    public Optional<ApprovalHistory> lastAction(long instanceId, long taskId, String action) {
        return histories.findFirstByInstanceIdAndTaskIdAndActionOrderByIdDesc(instanceId, taskId, action);
    }

    @Override
    public void appendCc(ApprovalCc cc) {
        ccs.save(cc);
    }

    @Override
    public List<ApprovalCc> ccs(long instanceId) {
        return ccs.findByInstanceIdOrderByIdAsc(instanceId);
    }

    @Override
    public PageSlice<ApprovalTask> todoTasks(long tenantId, long approverId, int page, int size) {
        return slice(tasks.findByTenantIdAndApproverIdAndStatusOrderByIdDesc(
                        tenantId, approverId, ApprovalTask.STATUS_PENDING, pageRequest(page, size)),
                page, size);
    }

    @Override
    public PageSlice<ApprovalTask> doneTasks(long tenantId, long approverId, int page, int size) {
        // 「已办」= 我处理过的（通过/驳回）或被系统取消的（别人先通过导致我的待办失效）。
        return slice(tasks.findByTenantIdAndApproverIdAndStatusInOrderByIdDesc(
                tenantId, approverId,
                List.of(ApprovalTask.STATUS_APPROVED, ApprovalTask.STATUS_REJECTED, ApprovalTask.STATUS_CANCELLED),
                pageRequest(page, size)), page, size);
    }

    @Override
    public PageSlice<ApprovalInstance> myInstances(long tenantId, long initiatorId, int page, int size) {
        Page<ApprovalInstance> result = instances.findByTenantIdAndInitiatorIdOrderByIdDesc(
                tenantId, initiatorId, pageRequest(page, size));
        return new PageSlice<>(result.getContent(), result.getTotalElements(), page, size);
    }

    private static PageRequest pageRequest(int page, int size) {
        // 契约里的 page 是 1 基（与 DeployOrderService、前端分页器一致），Spring 的 PageRequest 是 0 基：
        // 不减 1 时 page=1 会跳过前 size 行，待办列表 total=2 却 items=[]。
        int safePage = Math.max(page - 1, 0);
        int safeSize = Math.min(Math.max(size, 1), 200);
        return PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "id"));
    }

    private static PageSlice<ApprovalTask> slice(Page<ApprovalTask> page, int pageNo, int size) {
        return new PageSlice<>(page.getContent(), page.getTotalElements(), pageNo, size);
    }
}
