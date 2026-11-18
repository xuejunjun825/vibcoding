package com.example.req.approval;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.req.release.FlowChainViews;

/**
 * 把「一条审批实例的事实」投影成逐级审批阶梯（r30 / AC-2 / AC-3）。
 *
 * <p>为什么投影只有这一处：版本详情与发布单详情都要展示 {@code approvalLevels[]}，
 * 若各自从 {@code wf_task} / {@code wf_history} 推一遍状态，两页对「当前走到第几级」的判断迟早分叉
 * （一边认为 n2 已开始、一边认为还在 n1），而分叉的表现是「同一个人在两页看到不同的待办」。
 * 因此状态推导只写在 {@link #project(long)}，两页都调它。</p>
 *
 * <p>状态口径（TECHNICAL §3.3）：历史里有 {@code NODE_APPROVED} → {@code APPROVED}；该节点有 {@code REJECT} →
 * {@code REJECTED}；实例 {@code currentNode} 且 {@code PENDING} → {@code ACTIVE}；其余 {@code WAITING}。
 * {@code WAITING} 的 approvers 是空数组——不预解析角色成员，避免详情页每刷一次就去查一遍组织架构。</p>
 *
 * <p>长度与顺序恒等于<b>定义节点序列</b>（AC-2）：不按“已建待办”的节点投影，
 * 否则第一级没走完时列表只有一行，前端画不出「后面还有两级」的台阶。</p>
 */
@Service
public class ApprovalLevelProjector {

    private final ApprovalInstanceRepository instances;
    private final ApprovalTaskRepository tasks;
    private final ApprovalHistoryRepository histories;
    private final WorkflowRegistry registry;
    private final WorkflowJson json;

    public ApprovalLevelProjector(ApprovalInstanceRepository instances,
                                  ApprovalTaskRepository tasks,
                                  ApprovalHistoryRepository histories,
                                  WorkflowRegistry registry,
                                  WorkflowJson json) {
        this.instances = instances;
        this.tasks = tasks;
        this.histories = histories;
        this.registry = registry;
        this.json = json;
    }

    /** 一次投影：4 条小查询（实例、定义、任务、历史），不做 N+1。找不到实例 → 空列表。 */
    @Transactional(readOnly = true)
    public List<FlowChainViews.ApprovalLevel> project(long instanceId) {
        ApprovalInstance instance = instances.findById(instanceId).orElse(null);
        return instance == null ? List.of() : project(instance);
    }

    /**
     * 详情页挂载：有在途实例投影在途的，否则投影<b>最后一次</b>实例（驳回后仍有历史可读），
     * 从未发起 → {@code activeInstanceId=null} + 空列表。
     */
    @Transactional(readOnly = true)
    public Mount mount(long tenantId, String bizType, long bizId) {
        ApprovalInstance active = instances
                .findFirstByTenantIdAndBizTypeAndBizIdAndActiveFlag(tenantId, bizType, bizId,
                        ApprovalInstance.ACTIVE)
                .orElse(null);
        if (active != null) {
            return new Mount(active.getId(), project(active));
        }
        ApprovalInstance latest = instances
                .findFirstByTenantIdAndBizTypeAndBizIdOrderByIdDesc(tenantId, bizType, bizId)
                .orElse(null);
        return latest == null ? new Mount(null, List.of()) : new Mount(null, project(latest));
    }

    /** 详情页挂载结果：{@code activeInstanceId} 只指向在途实例，历史投影不算在途。 */
    public record Mount(Long activeInstanceId, List<FlowChainViews.ApprovalLevel> levels) {
    }

    // ------------------------------------------------------------------ 内部

    private List<FlowChainViews.ApprovalLevel> project(ApprovalInstance instance) {
        WorkflowRegistry.ResolvedWorkflow resolved = registry.byId(instance.getDefinitionId());
        if (resolved == null || resolved.definition() == null) {
            return List.of();
        }
        List<ApprovalTask> instanceTasks = tasks.findByInstanceIdOrderBySortNoAscIdAsc(instance.getId());
        List<ApprovalHistory> history = histories.findByInstanceIdOrderByIdAsc(instance.getId());
        Map<String, List<ApprovalTask>> tasksByNode = new LinkedHashMap<>();
        for (ApprovalTask task : instanceTasks) {
            tasksByNode.computeIfAbsent(task.getNodeKey(), k -> new ArrayList<>()).add(task);
        }
        // 节点级留痕：NODE_APPROVED 的 detail 里有 nodeKey；REJECT 落在具体任务上，按任务的节点归位。
        Map<String, ApprovalHistory> approvedAt = new LinkedHashMap<>();
        Map<String, ApprovalHistory> rejectedAt = new LinkedHashMap<>();
        Map<Long, ApprovalHistory> lastApproveByTask = new LinkedHashMap<>();
        for (ApprovalHistory entry : history) {
            String nodeKey = nodeKeyOf(entry, instanceTasks);
            if (ApprovalHistory.NODE_APPROVED.equals(entry.getAction()) && nodeKey != null) {
                approvedAt.put(nodeKey, entry);
            }
            if (ApprovalHistory.REJECT.equals(entry.getAction()) && nodeKey != null) {
                rejectedAt.put(nodeKey, entry);
            }
            if (ApprovalHistory.APPROVE.equals(entry.getAction()) && entry.getTaskId() != null) {
                lastApproveByTask.put(entry.getTaskId(), entry);
            }
        }
        List<FlowChainViews.ApprovalLevel> levels = new ArrayList<>();
        int index = 1;
        for (WorkflowDefinition.Node node : resolved.definition().nodes()) {
            String nodeKey = node.key();
            List<ApprovalTask> nodeTasks = tasksByNode.getOrDefault(nodeKey, List.of());
            TreeSet<Long> approvers = new TreeSet<>();
            for (ApprovalTask task : nodeTasks) {
                if (task.getApproverId() != null) {
                    approvers.add(task.getApproverId());
                }
            }
            String status;
            String handledAt = null;
            String comment = null;
            ApprovalHistory approved = approvedAt.get(nodeKey);
            ApprovalHistory rejected = rejectedAt.get(nodeKey);
            if (approved != null) {
                status = FlowChainViews.LEVEL_APPROVED;
                handledAt = text(approved.getCreatedAt());
                comment = lastApproveComment(nodeTasks, lastApproveByTask);
            } else if (rejected != null) {
                status = FlowChainViews.LEVEL_REJECTED;
                handledAt = text(rejected.getCreatedAt());
                comment = rejected.getComment();
            } else if (nodeKey.equals(instance.getCurrentNode())
                    && ApprovalInstance.STATUS_PENDING.equals(instance.getStatus())) {
                status = FlowChainViews.LEVEL_ACTIVE;
            } else {
                status = FlowChainViews.LEVEL_WAITING;
            }
            levels.add(new FlowChainViews.ApprovalLevel(index++, nodeKey, node.displayName(),
                    node.mode(), status, List.copyOf(approvers), handledAt, comment));
        }
        return levels;
    }

    /** 该节点最后一位审批人的意见（会签节点里“最后一条 APPROVE”的 comment）。 */
    private String lastApproveComment(List<ApprovalTask> nodeTasks, Map<Long, ApprovalHistory> lastApproveByTask) {
        ApprovalHistory last = null;
        for (ApprovalTask task : nodeTasks) {
            ApprovalHistory entry = lastApproveByTask.get(task.getId());
            if (entry != null && (last == null || entry.getId() > last.getId())) {
                last = entry;
            }
        }
        return last == null ? null : last.getComment();
    }

    /** 历史行属于哪个节点：NODE_APPROVED 从 detail 读，其余按 taskId 反查任务。 */
    private String nodeKeyOf(ApprovalHistory entry, List<ApprovalTask> instanceTasks) {
        if (entry.getTaskId() != null) {
            for (ApprovalTask task : instanceTasks) {
                if (entry.getTaskId().equals(task.getId())) {
                    return task.getNodeKey();
                }
            }
        }
        if (entry.getDetailJson() == null || entry.getDetailJson().isBlank()) {
            return null;
        }
        try {
            Object nodeKey = json.readMap(entry.getDetailJson()).get("nodeKey");
            return nodeKey == null ? null : String.valueOf(nodeKey);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String text(LocalDateTime value) {
        return value == null ? null : value.toString();
    }
}
