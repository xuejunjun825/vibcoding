package com.example.req.approval;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单测用的内存实现：把「状态推进」与「MySQL/锁/唯一键」解耦，
 * 让 AC-6..AC-9 的每条断言都能在没有数据库的情况下跑。
 */
final class InMemoryApprovalStore implements ApprovalStore {

    private final Map<Long, ApprovalInstance> instances = new LinkedHashMap<>();
    private final Map<Long, ApprovalTask> tasks = new LinkedHashMap<>();
    private final List<ApprovalHistory> histories = new ArrayList<>();
    private final List<ApprovalCc> ccs = new ArrayList<>();
    private final AtomicLong instanceIds = new AtomicLong();
    private final AtomicLong taskIds = new AtomicLong();
    private final AtomicLong historyIds = new AtomicLong();

    @Override
    public ApprovalInstance lockInstance(long instanceId) {
        return instanceOf(instanceId);
    }

    @Override
    public Optional<ApprovalInstance> instance(long instanceId) {
        return Optional.ofNullable(instances.get(instanceId));
    }

    @Override
    public Optional<ApprovalInstance> activeInstance(long tenantId, String bizType, long bizId) {
        return instances.values().stream()
                .filter(instance -> instance.isPending()
                        && instance.getActiveFlag() != null
                        && Long.valueOf(tenantId).equals(instance.getTenantId())
                        && String.valueOf(bizType).equals(instance.getBizType())
                        && Long.valueOf(bizId).equals(instance.getBizId()))
                .findFirst();
    }

    @Override
    public ApprovalInstance saveInstance(ApprovalInstance instance) {
        if (instance.getId() == null) {
            instance.setId(instanceIds.incrementAndGet());
        }
        instances.put(instance.getId(), instance);
        return instance;
    }

    @Override
    public ApprovalTask lockTask(long taskId) {
        return taskOf(taskId);
    }

    @Override
    public Optional<ApprovalTask> task(long taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public ApprovalTask saveTask(ApprovalTask task) {
        if (task.getId() == null) {
            task.setId(taskIds.incrementAndGet());
        }
        tasks.put(task.getId(), task);
        return task;
    }

    @Override
    public List<ApprovalTask> tasks(long instanceId) {
        return tasks.values().stream()
                .filter(task -> Long.valueOf(instanceId).equals(task.getInstanceId()))
                .sorted(Comparator.comparing((ApprovalTask task) -> sortOf(task)).thenComparing(ApprovalTask::getId))
                .toList();
    }

    @Override
    public List<ApprovalTask> pendingAt(long instanceId, String nodeKey) {
        return tasks.values().stream()
                .filter(ApprovalTask::isPending)
                .filter(task -> Long.valueOf(instanceId).equals(task.getInstanceId()))
                .filter(task -> nodeKey == null || nodeKey.equals(task.getNodeKey()))
                .sorted(Comparator.comparing((ApprovalTask task) -> sortOf(task)).thenComparing(ApprovalTask::getId))
                .toList();
    }

    @Override
    public List<ApprovalTask> tasksOf(long instanceId, String nodeKey, long approverId) {
        return tasks.values().stream()
                .filter(task -> Long.valueOf(instanceId).equals(task.getInstanceId()))
                .filter(task -> String.valueOf(nodeKey).equals(task.getNodeKey()))
                .filter(task -> task.getApproverId() != null && task.getApproverId() == approverId)
                .sorted(Comparator.comparing(ApprovalTask::getId))
                .toList();
    }

    @Override
    public long pendingCount(long instanceId) {
        return tasks(instanceId).stream().filter(ApprovalTask::isPending).count();
    }

    @Override
    public void appendHistory(ApprovalHistory history) {
        if (history.getId() == null) {
            history.setId(historyIds.incrementAndGet());
        }
        if (history.getCreatedAt() == null) {
            history.setCreatedAt(LocalDateTime.now());
        }
        histories.add(history);
    }

    @Override
    public List<ApprovalHistory> history(long instanceId) {
        return histories.stream()
                .filter(entry -> Long.valueOf(instanceId).equals(entry.getInstanceId()))
                .sorted(Comparator.comparing(ApprovalHistory::getId))
                .toList();
    }

    @Override
    public Optional<ApprovalHistory> lastAction(long instanceId, long taskId, String action) {
        ApprovalHistory found = null;
        for (ApprovalHistory entry : histories) {
            if (Long.valueOf(instanceId).equals(entry.getInstanceId())
                    && Long.valueOf(taskId).equals(entry.getTaskId())
                    && String.valueOf(action).equals(entry.getAction())) {
                found = entry;
            }
        }
        return Optional.ofNullable(found);
    }

    @Override
    public void appendCc(ApprovalCc cc) {
        ccs.add(cc);
    }

    @Override
    public List<ApprovalCc> ccs(long instanceId) {
        return ccs.stream()
                .filter(cc -> Long.valueOf(instanceId).equals(cc.getInstanceId()))
                .toList();
    }

    @Override
    public PageSlice<ApprovalTask> todoTasks(long tenantId, long approverId, int page, int size) {
        List<ApprovalTask> rows = tasks.values().stream()
                .filter(task -> task.isPending()
                        && Long.valueOf(tenantId).equals(task.getTenantId())
                        && task.getApproverId() != null && task.getApproverId() == approverId)
                .sorted(Comparator.comparing(ApprovalTask::getId).reversed())
                .toList();
        return slice(rows, page, size);
    }

    @Override
    public PageSlice<ApprovalTask> doneTasks(long tenantId, long approverId, int page, int size) {
        List<ApprovalTask> rows = tasks.values().stream()
                .filter(task -> !task.isPending()
                        && Long.valueOf(tenantId).equals(task.getTenantId())
                        && task.getApproverId() != null && task.getApproverId() == approverId)
                .sorted(Comparator.comparing(ApprovalTask::getId).reversed())
                .toList();
        return slice(rows, page, size);
    }

    @Override
    public PageSlice<ApprovalInstance> myInstances(long tenantId, long initiatorId, int page, int size) {
        List<ApprovalInstance> rows = instances.values().stream()
                .filter(instance -> Long.valueOf(tenantId).equals(instance.getTenantId())
                        && instance.getInitiatorId() != null && instance.getInitiatorId() == initiatorId)
                .sorted(Comparator.comparing(ApprovalInstance::getId).reversed())
                .toList();
        return slice(rows, page, size);
    }

    ApprovalInstance instanceOf(long instanceId) {
        ApprovalInstance instance = instances.get(instanceId);
        if (instance == null) {
            throw ApprovalException.notFound("审批实例不存在：" + instanceId);
        }
        return instance;
    }

    ApprovalTask taskOf(long taskId) {
        ApprovalTask task = tasks.get(taskId);
        if (task == null) {
            throw ApprovalException.notFound("审批任务不存在：" + taskId);
        }
        return task;
    }

    private static <T> PageSlice<T> slice(List<T> rows, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        int from = Math.min((safePage - 1) * safeSize, rows.size());
        int to = Math.min(from + safeSize, rows.size());
        return new PageSlice<>(rows.subList(from, to), rows.size(), safePage, safeSize);
    }

    private static int sortOf(ApprovalTask task) {
        return task.getSortNo() == null ? 0 : task.getSortNo();
    }
}
