package com.example.req.approval;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 审批中心查询侧：待办 / 已办 / 我发起的 / 详情 / 历史。
 *
 * <p>读写分离在这里落地——引擎负责推进，查询全部走只读事务，且每个入口都先过租户校验，
 * 详情再按「发起人 / 审批人 / 抄送人 / 管理员」四选一判定可见性（AC-6 的跨租户隔离靠它兜住）。
 */
@Service
@Transactional(readOnly = true)
public class ApprovalCenterService {

    private final ApprovalStore store;
    private final WorkflowRegistry workflows;
    private final ApprovalActorProvider actors;
    private final List<DocumentSummaryProvider> documents;

    public ApprovalCenterService(ApprovalStore store,
                                 WorkflowRegistry workflows,
                                 ApprovalActorProvider actors,
                                 List<DocumentSummaryProvider> documents) {
        this.store = store;
        this.workflows = workflows;
        this.actors = actors;
        this.documents = documents == null ? List.of() : List.copyOf(documents);
    }

    public PageSlice<ApprovalViews.TaskRow> todo(int page, int size) {
        ApprovalActor actor = actors.current();
        PageSlice<ApprovalTask> slice = store.todoTasks(actor.tenantId(), actor.userId(), page, size);
        List<ApprovalViews.TaskRow> rows = new ArrayList<>(slice.items().size());
        for (ApprovalTask task : slice.items()) {
            rows.add(taskRow(task, actor));
        }
        return new PageSlice<>(rows, slice.total(), slice.page(), slice.size());
    }

    public PageSlice<ApprovalViews.TaskRow> done(int page, int size) {
        ApprovalActor actor = actors.current();
        PageSlice<ApprovalTask> slice = store.doneTasks(actor.tenantId(), actor.userId(), page, size);
        List<ApprovalViews.TaskRow> rows = new ArrayList<>(slice.items().size());
        for (ApprovalTask task : slice.items()) {
            rows.add(taskRow(task, actor));
        }
        return new PageSlice<>(rows, slice.total(), slice.page(), slice.size());
    }

    public PageSlice<ApprovalViews.MineRow> mine(int page, int size) {
        ApprovalActor actor = actors.current();
        PageSlice<ApprovalInstance> slice = store.myInstances(actor.tenantId(), actor.userId(), page, size);
        List<ApprovalViews.MineRow> rows = new ArrayList<>(slice.items().size());
        for (ApprovalInstance instance : slice.items()) {
            rows.add(new ApprovalViews.MineRow(
                    instance.getId() == null ? 0L : instance.getId(),
                    instance.getBizType(),
                    instance.getBizId() == null ? 0L : instance.getBizId(),
                    instance.getStatus(),
                    instance.getCurrentNode(),
                    instance.getDefinitionVersion() == null ? 0 : instance.getDefinitionVersion(),
                    instance.getStartedAt(),
                    instance.getEndedAt(),
                    summary(instance, actor.userId())));
        }
        return new PageSlice<>(rows, slice.total(), slice.page(), slice.size());
    }

    public ApprovalViews.InstanceDetail detail(long instanceId) {
        ApprovalActor actor = actors.current();
        ApprovalInstance instance = visibleInstance(instanceId, actor);
        return new ApprovalViews.InstanceDetail(
                ApprovalViews.InstanceView.of(instance),
                summary(instance, actor.userId()),
                nodes(instance),
                tasks(instanceId),
                timeline(instanceId),
                ccs(instanceId));
    }

    /** AC-8「历史可按版本查」：返回实例钉住的版本号 + 全部流水条目。 */
    public ApprovalViews.HistoryView history(long instanceId) {
        ApprovalActor actor = actors.current();
        ApprovalInstance instance = visibleInstance(instanceId, actor);
        return new ApprovalViews.HistoryView(
                instance.getId(),
                instance.getDefinitionVersion() == null ? 0 : instance.getDefinitionVersion(),
                store.history(instanceId).stream().map(ApprovalViews.TimelineEntry::of).toList());
    }

    // ------------------------------------------------------------------ 内部

    private ApprovalViews.TaskRow taskRow(ApprovalTask task, ApprovalActor actor) {
        ApprovalInstance instance = store.instance(task.getInstanceId()).orElse(null);
        String bizType = instance == null ? null : instance.getBizType();
        long bizId = instance == null || instance.getBizId() == null ? 0L : instance.getBizId();
        long initiatorId = instance == null || instance.getInitiatorId() == null ? 0L : instance.getInitiatorId();
        Map<String, Object> document = instance == null
                ? Map.of()
                : summary(instance, actor.userId());
        return new ApprovalViews.TaskRow(
                task.getId() == null ? 0L : task.getId(),
                task.getInstanceId(),
                bizType,
                bizId,
                task.getNodeKey(),
                task.getNodeName(),
                initiatorId,
                instance == null ? null : instance.getStatus(),
                task.getStatus(),
                task.getCreatedAt(),
                document);
    }

    private List<ApprovalViews.NodeView> nodes(ApprovalInstance instance) {
        WorkflowRegistry.ResolvedWorkflow workflow = workflows.byId(instance.getDefinitionId());
        if (workflow == null || workflow.definition() == null || workflow.definition().nodes() == null) {
            return List.of();
        }
        return workflow.definition().nodes().stream()
                .map(node -> new ApprovalViews.NodeView(node.key(), node.displayName(), node.type(), node.mode()))
                .toList();
    }

    private List<ApprovalViews.TaskView> tasks(long instanceId) {
        return store.tasks(instanceId).stream().map(ApprovalViews.TaskView::of).toList();
    }

    private List<ApprovalViews.TimelineEntry> timeline(long instanceId) {
        return store.history(instanceId).stream().map(ApprovalViews.TimelineEntry::of).toList();
    }

    private List<ApprovalViews.CcView> ccs(long instanceId) {
        List<ApprovalViews.CcView> views = new ArrayList<>();
        for (ApprovalCc cc : store.ccs(instanceId)) {
            views.add(new ApprovalViews.CcView(cc.getReceiverId(), cc.getNodeKey(), cc.getReadAt()));
        }
        return views;
    }

    /** 可见性：租户相同，且是发起人/审批人/抄送人/管理员之一；否则 403（不泄漏单据是否存在）。 */
    private ApprovalInstance visibleInstance(long instanceId, ApprovalActor actor) {
        ApprovalInstance instance = store.instance(instanceId)
                .orElseThrow(() -> ApprovalException.notFound("审批实例不存在：" + instanceId));
        if (!Objects.equals(instance.getTenantId(), actor.tenantId())) {
            throw new ApprovalException(403, ApprovalException.TENANT_MISMATCH,
                    "审批实例不属于当前租户", Map.of());
        }
        if (isParticipant(instance, actor)) {
            return instance;
        }
        throw ApprovalException.forbidden("无权查看该审批实例");
    }

    private boolean isParticipant(ApprovalInstance instance, ApprovalActor actor) {
        if (instance.getInitiatorId() != null && instance.getInitiatorId() == actor.userId()) {
            return true;
        }
        Collection<ApprovalTask> tasks = store.tasks(instance.getId());
        for (ApprovalTask task : tasks) {
            if (task.getApproverId() != null && task.getApproverId() == actor.userId()) {
                return true;
            }
        }
        for (ApprovalCc cc : store.ccs(instance.getId())) {
            if (cc.getReceiverId() != null && cc.getReceiverId() == actor.userId()) {
                return true;
            }
        }
        return actors.isAdmin(actor.tenantId(), actor.userId());
    }

    /** 单据摘要：实现方按 viewer 的 scope 裁剪；没有实现方时返回空摘要（列表仍可用）。 */
    private Map<String, Object> summary(ApprovalInstance instance, long viewerId) {
        if (instance.getBizType() == null) {
            return Map.of();
        }
        for (DocumentSummaryProvider provider : documents) {
            if (instance.getBizType().equals(provider.bizType())) {
                try {
                    Map<String, Object> summary = provider.summary(
                            instance.getTenantId(), instance.getBizId(), viewerId);
                    return summary == null ? Map.of() : summary;
                } catch (RuntimeException ex) {
                    return Map.of();
                }
            }
        }
        return Map.of();
    }

    /** 节点去重（同一 key 只保留一个），供前端渲染时间线左侧的节点清单。 */
    static List<String> distinctNodeKeys(List<ApprovalViews.NodeView> nodes) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        nodes.forEach(node -> keys.add(node.key()));
        return new ArrayList<>(keys);
    }
}
