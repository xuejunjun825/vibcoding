package com.example.req.approval;

import com.example.platform.contract.ApprovalCallback;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 自研审批引擎：会签/或签/条件/抄送 + 加签/减签/转办/撤回/催办 + 版本化实例。
 *
 * <p>设计要点（对应 AC-6..AC-9）：
 * <ul>
 *   <li><b>只认定义与回调</b>：不 import 任何单据类，四类单据通过 {@link ApprovalCallback} 反向注册，
 *       所以审批引擎可以独立演进（§5.6）。</li>
 *   <li><b>版本固化</b>：实例落库时写死 {@code definitionId + definitionVersion}，后续推进只读该版本行，
 *       v2 发布不会改变在途 v1 的走向（AC-8）。</li>
 *   <li><b>并发只有一个赢家</b>：所有推进先锁实例再锁任务，重复处理返回 409
 *       {@code TASK_ALREADY_HANDLED}；「单在途实例」另有唯一键兜底。</li>
 *   <li><b>顺序语义</b>：{@code sort_no} 决定先后——前加签取比当前任务更小的值，后加签取更大的值；
 *       或签节点只有在「身后没有排队的人」时才结束节点（AC-9 前/后加签）。</li>
 * </ul>
 */
@Service
public class ApprovalEngine {

    /** 定义里存在环时最多走这么多跳，避免死循环拖垮请求（发布校验已拦不可达/无 next，这里是运行期兜底）。 */
    private static final int MAX_HOPS = 64;

    /** 同一任务催办冷却。 */
    private static final Duration URGE_COOLDOWN = Duration.ofSeconds(60);

    private final ApprovalStore store;
    private final WorkflowRegistry workflows;
    private final ApproverResolver resolver;
    private final ApproverDirectory directory;
    private final ApprovalActorProvider actors;
    private final ApprovalNotifier notifier;
    private final List<ApprovalCallback> callbacks;
    private final List<DocumentSummaryProvider> documents;
    private final WorkflowJson json;
    private final Clock clock;

    public ApprovalEngine(ApprovalStore store,
                          WorkflowRegistry workflows,
                          ApproverResolver resolver,
                          ApproverDirectory directory,
                          ApprovalActorProvider actors,
                          ApprovalNotifier notifier,
                          List<ApprovalCallback> callbacks,
                          List<DocumentSummaryProvider> documents,
                          WorkflowJson json,
                          Clock clock) {
        this.store = store;
        this.workflows = workflows;
        this.resolver = resolver;
        this.directory = directory;
        this.actors = actors;
        this.notifier = notifier;
        this.callbacks = callbacks == null ? List.of() : List.copyOf(callbacks);
        this.documents = documents == null ? List.of() : List.copyOf(documents);
        this.json = json;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 发起

    @Transactional
    public ApprovalViews.InstanceView start(StartApproval cmd) {
        ApprovalActor actor = actors.current();
        long tenantId = actor.tenantId();
        long initiatorId = cmd.initiatorId() == null ? actor.userId() : cmd.initiatorId();
        requireBizType(cmd.bizType());

        Optional<ApprovalInstance> running = store.activeInstance(tenantId, cmd.bizType(), cmd.bizId());
        if (running.isPresent()) {
            throw ApprovalException.conflict(ApprovalException.ILLEGAL_TRANSITION,
                    "该单据已有在途审批实例，不能重复发起", "instanceId", running.get().getId());
        }

        WorkflowRegistry.ResolvedWorkflow workflow = resolveWorkflow(tenantId, cmd);
        LocalDateTime now = LocalDateTime.now(clock);

        ApprovalInstance instance = new ApprovalInstance();
        instance.setTenantId(tenantId);
        instance.setDefinitionId(workflow.id());
        instance.setDefinitionVersion(workflow.version());
        instance.setBizType(cmd.bizType());
        instance.setBizId(cmd.bizId());
        instance.setInitiatorId(initiatorId);
        instance.setStatus(ApprovalInstance.STATUS_PENDING);
        instance.setActiveFlag(ApprovalInstance.ACTIVE);
        instance.setCurrentNode(workflow.definition().start());
        instance.setStartedAt(now);
        instance.setCreatedAt(now);
        instance.setUpdatedAt(now);
        instance.setCreatedBy(initiatorId);
        instance.setUpdatedBy(initiatorId);
        try {
            instance = store.saveInstance(instance);
        } catch (DataIntegrityViolationException ex) {
            // 并发提交时唯一键 (tenant,biz_type,biz_id,active_flag) 是最后一道闸门。
            throw ApprovalException.conflict(ApprovalException.ILLEGAL_TRANSITION,
                    "该单据已有在途审批实例，不能重复发起");
        }

        Map<String, Object> document = new LinkedHashMap<>(cmd.attributes());
        if (cmd.reqType() != null) {
            document.putIfAbsent("reqType", cmd.reqType());
        }
        if (cmd.domainId() != null) {
            document.putIfAbsent("domainId", cmd.domainId());
        }
        Map<String, Object> submitDetail = new LinkedHashMap<>();
        submitDetail.put("definitionId", workflow.id());
        submitDetail.put("definitionVersion", workflow.version());
        submitDetail.put("document", document);
        history(instance, null, ApprovalHistory.SUBMIT, initiatorId, null, submitDetail);

        advance(instance, workflow.definition(), workflow.definition().start(), document, initiatorId);
        return ApprovalViews.InstanceView.of(instance);
    }

    private WorkflowRegistry.ResolvedWorkflow resolveWorkflow(long tenantId, StartApproval cmd) {
        WorkflowRegistry.ResolvedWorkflow workflow = cmd.definitionId() != null
                ? workflows.byId(cmd.definitionId())
                : workflows.resolve(tenantId, cmd.bizType(), cmd.reqType(),
                        cmd.domainId() == null ? WorkflowVersion.ANY_DOMAIN : cmd.domainId());
        if (workflow == null) {
            throw ApprovalException.badRequest(ApprovalException.NO_WORKFLOW,
                    "没有匹配的已发布流程（bizType=" + cmd.bizType() + "）");
        }
        if (!WorkflowVersion.STATUS_PUBLISHED.equals(workflow.status())) {
            throw ApprovalException.badRequest(ApprovalException.NO_WORKFLOW,
                    "流程第 " + workflow.version() + " 版未发布，不能发起审批");
        }
        return workflow;
    }

    // ------------------------------------------------------------------ 通过 / 驳回

    @Transactional
    public void approve(long taskId, String comment) {
        ApprovalActor actor = actors.current();
        ApprovalInstance instance = lockInstanceOfTask(taskId, actor);
        ApprovalTask task = store.lockTask(taskId);
        assertPending(instance, task);
        assertAssignee(task, actor);

        LocalDateTime now = LocalDateTime.now(clock);
        task.setStatus(ApprovalTask.STATUS_APPROVED);
        task.setResult(ApprovalTask.RESULT_APPROVE);
        task.setComment(comment);
        task.setHandledAt(now);
        task.setUpdatedAt(now);
        task.setUpdatedBy(actor.userId());
        store.saveTask(task);
        history(instance, task, ApprovalHistory.APPROVE, actor.userId(), comment, null);

        WorkflowDefinition definition = definitionOf(instance);
        WorkflowDefinition.Node node = requireNode(definition, task.getNodeKey());

        if (nodeStillOpen(instance, task, node)) {
            return;
        }
        if (WorkflowDefinition.MODE_ANY.equalsIgnoreCase(node.mode())) {
            cancelPendingAt(instance, node.key(), task.getId(), actor.userId(),
                    "或签节点已由他人处理，待办自动关闭");
        }
        history(instance, task, ApprovalHistory.NODE_APPROVED, actor.userId(), null,
                Map.of("nodeKey", node.key(), "mode", node.mode()));
        advance(instance, definition, node.next(), documentOf(instance), actor.userId());
    }

    @Transactional
    public void reject(long taskId, String comment) {
        ApprovalActor actor = actors.current();
        ApprovalInstance instance = lockInstanceOfTask(taskId, actor);
        ApprovalTask task = store.lockTask(taskId);
        assertPending(instance, task);
        assertAssignee(task, actor);

        LocalDateTime now = LocalDateTime.now(clock);
        task.setStatus(ApprovalTask.STATUS_REJECTED);
        task.setResult(ApprovalTask.RESULT_REJECT);
        task.setComment(comment);
        task.setHandledAt(now);
        task.setUpdatedAt(now);
        task.setUpdatedBy(actor.userId());
        store.saveTask(task);
        history(instance, task, ApprovalHistory.REJECT, actor.userId(), comment, null);

        cancelPendingAt(instance, null, task.getId(), actor.userId(), "实例被驳回，待办关闭");
        instance.finish(ApprovalInstance.STATUS_REJECTED, now);
        instance.setUpdatedBy(actor.userId());
        store.saveInstance(instance);
        history(instance, task, ApprovalHistory.INSTANCE_REJECTED, actor.userId(), comment,
                Map.of("nodeKey", task.getNodeKey()));

        callback(instance).ifPresent(cb -> cb.onRejected(instance.getBizId(), instance.getId(),
                comment == null ? "" : comment));
        notifier.notify(instance.getInitiatorId(), "APPROVAL_RESULT", "审批被驳回",
                "单据 " + instance.getBizType() + "#" + instance.getBizId() + " 在节点「"
                        + safeName(task) + "」被驳回" + (comment == null || comment.isBlank() ? "" : "：" + comment),
                instance.getBizType(), instance.getBizId());
    }

    // ------------------------------------------------------------------ 加签 / 减签

    @Transactional
    public void addSign(long taskId, String type, List<Long> userIds, String comment) {
        ApprovalActor actor = actors.current();
        String signType = normaliseSignType(type);
        if (userIds == null || userIds.isEmpty()) {
            throw ApprovalException.badRequest(ApprovalException.VALIDATION_FAILED, "加签必须至少指定一个审批人");
        }
        List<Long> targets = distinct(userIds);
        ApprovalInstance instance = lockInstanceOfTask(taskId, actor);
        ApprovalTask task = store.lockTask(taskId);
        assertAssignee(task, actor);
        if (!task.isPending()) {
            // AC-9：已处理的审批任务加签必须 409，否则新人的处理顺序无从确定。
            throw ApprovalException.conflict(ApprovalException.TASK_ALREADY_HANDLED,
                    "该审批任务已处理，不能加签", "taskId", taskId);
        }
        List<Long> active = resolverActiveUsers(instance.getTenantId(), targets);
        if (active.size() != targets.size()) {
            List<Long> missing = new ArrayList<>(targets);
            missing.removeAll(active);
            throw new ApprovalException(400, ApprovalException.VALIDATION_FAILED,
                    "加签对象不是本租户的启用用户：" + missing, Map.of("userIds", missing));
        }

        WorkflowDefinition definition = definitionOf(instance);
        WorkflowDefinition.Node node = requireNode(definition, task.getNodeKey());
        List<ApprovalTask> existing = store.pendingAt(instance.getId(), node.key());
        int baseSort = sortBase(existing, task, signType);
        LocalDateTime now = LocalDateTime.now(clock);

        for (int i = 0; i < targets.size(); i++) {
            long userId = targets.get(i);
            int sortNo = ApprovalTask.SIGN_BEFORE.equals(signType)
                    ? baseSort - (targets.size() - i)
                    : baseSort + i + 1;
            ApprovalTask added = newTask(instance, node, userId, sortNo, now, actor.userId());
            added.setOrigin(ApprovalTask.ORIGIN_ADD_SIGN);
            added.setAddSignType(signType);
            added.setParentTaskId(task.getId());
            added.setSignNo(nextSignNo(instance.getId(), node.key(), userId));
            store.saveTask(added);
            notifier.notify(userId, "APPROVAL", "加签待办",
                    "你在单据 " + instance.getBizType() + "#" + instance.getBizId() + " 的节点「"
                            + safeName(task) + "」被加签，请及时处理", instance.getBizType(), instance.getBizId());
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", signType);
        detail.put("userIds", targets);
        detail.put("parentTaskId", task.getId());
        history(instance, task, ApprovalHistory.ADD_SIGN, actor.userId(), comment, detail);
    }

    @Transactional
    public void removeSign(long taskId, long targetUserId, String reason) {
        if (reason == null || reason.isBlank()) {
            // AC-9：减签必须填原因，空原因 400（不是 409，也不是静默成功）。
            throw ApprovalException.badRequest(ApprovalException.REASON_REQUIRED, "减签必须填写原因");
        }
        ApprovalActor actor = actors.current();
        ApprovalInstance instance = lockInstanceOfTask(taskId, actor);
        ApprovalTask task = store.lockTask(taskId);
        assertPending(instance, task);
        boolean owner = task.getApproverId() != null && task.getApproverId() == actor.userId();
        boolean admin = actors.isAdmin(instance.getTenantId(), actor.userId());
        if (!owner && !admin) {
            throw ApprovalException.forbidden("只有节点负责人或管理员可以减签");
        }

        List<ApprovalTask> candidates = store.tasksOf(instance.getId(), task.getNodeKey(), targetUserId);
        ApprovalTask target = null;
        for (ApprovalTask candidate : candidates) {
            if (candidate.isPending()) {
                target = candidate;
                break;
            }
        }
        if (target == null) {
            throw ApprovalException.notFound("审批人 " + targetUserId + " 在该节点没有待处理任务");
        }
        List<ApprovalTask> pending = store.pendingAt(instance.getId(), task.getNodeKey());
        if (pending.size() <= 1) {
            // 末位减签会把节点卡死在无人可批，必须 409 拒绝——本人就是最后一人时同样成立，
            // 所以这条判定必须排在「不能减签自己」之前（否则 AC-9 的末位场景永远走不到）。
            throw ApprovalException.conflict(ApprovalException.LAST_APPROVER,
                    "该节点只剩最后一名审批人，不能减签", "nodeKey", task.getNodeKey());
        }
        if (target.getId() != null && target.getId().equals(task.getId())) {
            throw ApprovalException.badRequest(ApprovalException.VALIDATION_FAILED, "不能减签自己");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        target.setStatus(ApprovalTask.STATUS_CANCELLED);
        target.setComment("减签：" + reason);
        target.setHandledAt(now);
        target.setUpdatedAt(now);
        target.setUpdatedBy(actor.userId());
        store.saveTask(target);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("targetUserId", targetUserId);
        detail.put("targetTaskId", target.getId());
        detail.put("reason", reason);
        history(instance, task, ApprovalHistory.REMOVE_SIGN, actor.userId(), reason, detail);
        notifier.notify(targetUserId, "APPROVAL", "你已被移出审批节点",
                "单据 " + instance.getBizType() + "#" + instance.getBizId() + " 的节点「" + safeName(task)
                        + "」已将你减签，原因：" + reason, instance.getBizType(), instance.getBizId());
    }

    // ------------------------------------------------------------------ 转办 / 撤回 / 催办

    @Transactional
    public void transfer(long taskId, long toUserId) {
        ApprovalActor actor = actors.current();
        ApprovalInstance instance = lockInstanceOfTask(taskId, actor);
        ApprovalTask task = store.lockTask(taskId);
        assertPending(instance, task);
        boolean owner = task.getApproverId() != null && task.getApproverId() == actor.userId();
        if (!owner && !actors.isAdmin(instance.getTenantId(), actor.userId())) {
            throw ApprovalException.forbidden("只有待办负责人或管理员可以转办");
        }
        if (task.getApproverId() != null && task.getApproverId() == toUserId) {
            throw ApprovalException.badRequest(ApprovalException.VALIDATION_FAILED, "不能转办给自己");
        }
        if (resolverActiveUsers(instance.getTenantId(), List.of(toUserId)).isEmpty()) {
            throw new ApprovalException(400, ApprovalException.VALIDATION_FAILED,
                    "转办对象不是本租户的启用用户", Map.of("toUserId", toUserId));
        }

        long fromUserId = task.getApproverId() == null ? 0L : task.getApproverId();
        task.setApproverId(toUserId);
        task.setSignNo(nextSignNo(instance.getId(), task.getNodeKey(), toUserId));
        task.setUpdatedAt(LocalDateTime.now(clock));
        task.setUpdatedBy(actor.userId());
        store.saveTask(task);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fromUserId", fromUserId);
        detail.put("toUserId", toUserId);
        history(instance, task, ApprovalHistory.TRANSFER, actor.userId(), null, detail);
        notifier.notify(toUserId, "APPROVAL", "转办待办",
                "单据 " + instance.getBizType() + "#" + instance.getBizId() + " 的节点「" + safeName(task)
                        + "」转办给你，请及时处理", instance.getBizType(), instance.getBizId());
    }

    @Transactional
    public void withdraw(long instanceId) {
        ApprovalActor actor = actors.current();
        ApprovalInstance instance = store.lockInstance(instanceId);
        requireTenant(instance, actor);
        if (!instance.isPending()) {
            throw ApprovalException.conflict(ApprovalException.ILLEGAL_TRANSITION,
                    "审批已结束，不能撤回", "status", instance.getStatus());
        }
        if (instance.getInitiatorId() == null || instance.getInitiatorId() != actor.userId()) {
            throw ApprovalException.forbidden("只有发起人可以撤回");
        }
        boolean handled = store.tasks(instanceId).stream().anyMatch(t -> !t.isPending());
        if (handled) {
            // AC-9：只有「未处理」的节点能撤；已经有人批过就必须走驳回/继续流程。
            throw ApprovalException.conflict(ApprovalException.ILLEGAL_TRANSITION,
                    "已有节点被处理，不能撤回");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        for (ApprovalTask pending : store.tasks(instanceId)) {
            if (!pending.isPending()) {
                continue;
            }
            pending.setStatus(ApprovalTask.STATUS_CANCELLED);
            pending.setComment("实例被发起人撤回");
            pending.setHandledAt(now);
            pending.setUpdatedAt(now);
            pending.setUpdatedBy(actor.userId());
            store.saveTask(pending);
        }
        instance.finish(ApprovalInstance.STATUS_WITHDRAWN, now);
        instance.setUpdatedBy(actor.userId());
        store.saveInstance(instance);
        history(instance, null, ApprovalHistory.INSTANCE_WITHDRAWN, actor.userId(), null, null);
        callback(instance).ifPresent(cb -> cb.onWithdrawn(instance.getBizId(), instance.getId()));
    }

    @Transactional
    public void urge(long taskId) {
        ApprovalActor actor = actors.current();
        ApprovalInstance instance = lockInstanceOfTask(taskId, actor);
        ApprovalTask task = store.lockTask(taskId);
        if (!task.isPending()) {
            throw ApprovalException.conflict(ApprovalException.TASK_ALREADY_HANDLED,
                    "该审批任务已处理，无需催办", "taskId", taskId);
        }
        boolean initiator = instance.getInitiatorId() != null && instance.getInitiatorId() == actor.userId();
        if (!initiator && !actors.isAdmin(instance.getTenantId(), actor.userId())) {
            throw ApprovalException.forbidden("只有发起人或管理员可以催办");
        }
        Optional<ApprovalHistory> last = store.lastAction(instance.getId(), taskId, ApprovalHistory.URGE);
        LocalDateTime now = LocalDateTime.now(clock);
        if (last.isPresent() && last.get().getCreatedAt() != null
                && last.get().getCreatedAt().isAfter(now.minus(URGE_COOLDOWN))) {
            throw ApprovalException.tooManyRequests(ApprovalException.URGE_TOO_FREQUENT,
                    "催办过于频繁，请 60 秒后再试");
        }
        history(instance, task, ApprovalHistory.URGE, actor.userId(), null, null);
        notifier.notify(task.getApproverId(), "APPROVAL", "审批催办",
                "单据 " + instance.getBizType() + "#" + instance.getBizId() + " 的节点「" + safeName(task)
                        + "」等待你处理，发起人已催办", instance.getBizType(), instance.getBizId());
    }

    // ------------------------------------------------------------------ 推进

    /**
     * 从 {@code fromKey} 开始推进，直到落在审批节点或结束。
     *
     * <p>条件节点按定义顺序取第一个命中分支，未命中走 defaultNext；抄送节点只落记录+通知，不阻塞。
     */
    private void advance(ApprovalInstance instance, WorkflowDefinition definition, String fromKey,
                         Map<String, Object> document, long operatorId) {
        String key = fromKey;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            if (definition.endsAt(key)) {
                finishApproved(instance, operatorId);
                return;
            }
            WorkflowDefinition.Node node = definition.node(key);
            if (node == null) {
                throw ApprovalException.conflict(ApprovalException.ILLEGAL_TRANSITION,
                        "流程定义缺少节点：" + key, "nodeKey", key);
            }
            instance.setCurrentNode(key);
            instance.setUpdatedAt(LocalDateTime.now(clock));
            if (node.isApproval()) {
                createNodeTasks(instance, node, document, operatorId);
                return;
            }
            if (node.isCondition()) {
                key = route(instance, node, document, operatorId);
                continue;
            }
            if (node.isCc()) {
                sendCc(instance, node, document, operatorId);
                key = node.next();
                continue;
            }
            throw ApprovalException.conflict(ApprovalException.ILLEGAL_TRANSITION,
                    "未知节点类型：" + node.type(), "nodeKey", key);
        }
        throw ApprovalException.conflict(ApprovalException.ILLEGAL_TRANSITION,
                "流程定义存在环或层级过深，已中止推进");
    }

    private void finishApproved(ApprovalInstance instance, long operatorId) {
        LocalDateTime now = LocalDateTime.now(clock);
        instance.setCurrentNode(WorkflowDefinition.END);
        instance.finish(ApprovalInstance.STATUS_APPROVED, now);
        instance.setUpdatedBy(operatorId);
        store.saveInstance(instance);
        history(instance, null, ApprovalHistory.INSTANCE_APPROVED, operatorId, null, null);
        callback(instance).ifPresent(cb -> cb.onApproved(instance.getBizId(), instance.getId()));
        notifier.notify(instance.getInitiatorId(), "APPROVAL_RESULT", "审批通过",
                "单据 " + instance.getBizType() + "#" + instance.getBizId() + " 已审批通过",
                instance.getBizType(), instance.getBizId());
    }

    private String route(ApprovalInstance instance, WorkflowDefinition.Node node,
                         Map<String, Object> document, long operatorId) {
        int matched = ConditionEvaluator.firstMatchingBranch(node.branches(), document);
        String target = matched >= 0 ? node.branches().get(matched).next() : node.defaultNext();
        if (target == null || target.isBlank()) {
            throw ApprovalException.conflict(ApprovalException.ILLEGAL_TRANSITION,
                    "条件节点没有可走的分支：" + node.key(), "nodeKey", node.key());
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("nodeKey", node.key());
        detail.put("branch", matched);
        detail.put("target", target);
        detail.put("document", document);
        history(instance, null, ApprovalHistory.CONDITION_ROUTE, operatorId, null, detail);
        return target;
    }

    private void sendCc(ApprovalInstance instance, WorkflowDefinition.Node node,
                        Map<String, Object> document, long operatorId) {
        // 抄送节点声明的是 receivers 而不是 approvers，必须逐个 spec 解析，否则抄送永远为空。
        List<Long> receivers = new ArrayList<>();
        Collection<Long> domainIds = domainIds(document);
        for (WorkflowDefinition.Approver spec : node.receivers()) {
            for (Long receiver : resolver.resolve(spec, instance.getTenantId(),
                    instance.getInitiatorId(), domainIds)) {
                if (!receivers.contains(receiver)) {
                    receivers.add(receiver);
                }
            }
        }
        for (Long receiver : receivers) {
            ApprovalCc cc = new ApprovalCc();
            cc.setTenantId(instance.getTenantId());
            cc.setInstanceId(instance.getId());
            cc.setNodeKey(node.key());
            cc.setReceiverId(receiver);
            cc.setCreatedAt(LocalDateTime.now(clock));
            store.appendCc(cc);
            notifier.notify(receiver, "CC", "审批抄送",
                    "单据 " + instance.getBizType() + "#" + instance.getBizId() + " 抄送给你（节点："
                            + node.displayName() + "）", instance.getBizType(), instance.getBizId());
        }
        history(instance, null, ApprovalHistory.CC_SEND, operatorId, null,
                Map.of("nodeKey", node.key(), "receivers", receivers));
    }

    private void createNodeTasks(ApprovalInstance instance, WorkflowDefinition.Node node,
                                 Map<String, Object> document, long operatorId) {
        List<Long> approvers = resolver.resolveApprovers(node, instance.getTenantId(),
                instance.getInitiatorId(), domainIds(document));
        if (approvers.isEmpty()) {
            history(instance, null, ApprovalHistory.APPROVER_UNRESOLVED, operatorId, null,
                    Map.of("nodeKey", node.key()));
            throw ApprovalException.conflict(ApprovalException.APPROVER_UNRESOLVED,
                    "节点「" + node.displayName() + "」解析不出审批人，单据已停在当前节点",
                    "nodeKey", node.key());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        int baseSort = maxSort(store.pendingAt(instance.getId(), node.key()));
        int index = 0;
        for (Long approver : approvers) {
            ApprovalTask task = newTask(instance, node, approver, baseSort + index + 1, now, operatorId);
            task.setSignNo(nextSignNo(instance.getId(), node.key(), approver));
            store.saveTask(task);
            index++;
            notifier.notify(approver, "APPROVAL", "待审批",
                    "单据 " + instance.getBizType() + "#" + instance.getBizId() + " 的节点「"
                            + node.displayName() + "」等待你处理", instance.getBizType(), instance.getBizId());
        }
    }

    private ApprovalTask newTask(ApprovalInstance instance, WorkflowDefinition.Node node, long approverId,
                                 int sortNo, LocalDateTime now, long operatorId) {
        ApprovalTask task = new ApprovalTask();
        task.setTenantId(instance.getTenantId());
        task.setInstanceId(instance.getId());
        task.setNodeKey(node.key());
        task.setNodeName(node.displayName());
        task.setApproverId(approverId);
        task.setMode(node.mode());
        task.setStatus(ApprovalTask.STATUS_PENDING);
        task.setOrigin(ApprovalTask.ORIGIN_INITIAL);
        task.setSignNo(1);
        task.setSortNo(sortNo);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        task.setCreatedBy(operatorId);
        task.setUpdatedBy(operatorId);
        return task;
    }

    /**
     * 节点是否还要继续等：会签看是否还有待办；或签看「身后是否还排着人」。
     * 前加签的人先处理后，本人（parent）仍待处理，因此节点不能提前结束。
     */
    private boolean nodeStillOpen(ApprovalInstance instance, ApprovalTask task, WorkflowDefinition.Node node) {
        List<ApprovalTask> pending = store.pendingAt(instance.getId(), node.key());
        if (pending.isEmpty()) {
            return false;
        }
        if (WorkflowDefinition.MODE_ALL.equalsIgnoreCase(node.mode())) {
            return true;
        }
        for (ApprovalTask other : pending) {
            if (other.isAddSign() && sortOf(other) > sortOf(task)) {
                return true;
            }
        }
        if (task.isAddSign() && ApprovalTask.SIGN_BEFORE.equals(task.getAddSignType())
                && task.getParentTaskId() != null) {
            ApprovalTask parent = store.task(task.getParentTaskId()).orElse(null);
            return parent != null && parent.isPending();
        }
        return false;
    }

    private void cancelPendingAt(ApprovalInstance instance, String nodeKey, long exceptTaskId,
                                 long operatorId, String comment) {
        LocalDateTime now = LocalDateTime.now(clock);
        for (ApprovalTask open : store.tasks(instance.getId())) {
            if (!open.isPending() || (nodeKey != null && !nodeKey.equals(open.getNodeKey()))) {
                continue;
            }
            if (open.getId() != null && open.getId() == exceptTaskId) {
                continue;
            }
            open.setStatus(ApprovalTask.STATUS_CANCELLED);
            open.setComment(comment);
            open.setHandledAt(now);
            open.setUpdatedAt(now);
            open.setUpdatedBy(operatorId);
            store.saveTask(open);
            history(instance, open, ApprovalHistory.TASK_CANCELLED, operatorId, comment,
                    Map.of("nodeKey", open.getNodeKey()));
        }
    }

    // ------------------------------------------------------------------ 工具

    private ApprovalInstance lockInstanceOfTask(long taskId, ApprovalActor actor) {
        ApprovalTask snapshot = store.task(taskId)
                .orElseThrow(() -> ApprovalException.notFound("审批任务不存在：" + taskId));
        if (snapshot.getTenantId() != null && actor.tenantId() != snapshot.getTenantId()) {
            throw new ApprovalException(403, ApprovalException.TENANT_MISMATCH,
                    "审批任务不属于当前租户", Map.of());
        }
        ApprovalInstance instance = store.lockInstance(snapshot.getInstanceId());
        requireTenant(instance, actor);
        return instance;
    }

    private void requireTenant(ApprovalInstance instance, ApprovalActor actor) {
        if (instance.getTenantId() != null && actor.tenantId() != instance.getTenantId()) {
            throw new ApprovalException(403, ApprovalException.TENANT_MISMATCH, "审批实例不属于当前租户", Map.of());
        }
    }

    private void assertPending(ApprovalInstance instance, ApprovalTask task) {
        if (!instance.isPending()) {
            throw ApprovalException.conflict(ApprovalException.TASK_ALREADY_HANDLED,
                    "审批实例已结束，任务不可再处理", "instanceStatus", instance.getStatus());
        }
        if (!task.isPending()) {
            throw ApprovalException.conflict(ApprovalException.TASK_ALREADY_HANDLED,
                    "该审批任务已被处理", "taskStatus", task.getStatus());
        }
    }

    private void assertAssignee(ApprovalTask task, ApprovalActor actor) {
        if (task.getApproverId() == null || task.getApproverId() != actor.userId()) {
            throw ApprovalException.forbidden("不是你的待办任务");
        }
    }

    private WorkflowDefinition definitionOf(ApprovalInstance instance) {
        WorkflowRegistry.ResolvedWorkflow workflow = workflows.byId(instance.getDefinitionId());
        if (workflow == null) {
            throw ApprovalException.conflict(ApprovalException.ILLEGAL_TRANSITION,
                    "实例钉住的流程定义已不存在：" + instance.getDefinitionId(),
                    "definitionId", instance.getDefinitionId());
        }
        if (workflow.version() != instance.getDefinitionVersion()) {
            // 版本不一致说明实例被改过库；宁可 409 也不能按别的版本推进（AC-8 不跳步）。
            throw ApprovalException.conflict(ApprovalException.ILLEGAL_TRANSITION,
                    "实例版本与流程定义版本不一致", "definitionVersion", instance.getDefinitionVersion());
        }
        return workflow.definition();
    }

    private WorkflowDefinition.Node requireNode(WorkflowDefinition definition, String nodeKey) {
        WorkflowDefinition.Node node = definition.node(nodeKey);
        if (node == null) {
            throw ApprovalException.conflict(ApprovalException.ILLEGAL_TRANSITION,
                    "流程定义缺少节点：" + nodeKey, "nodeKey", nodeKey);
        }
        return node;
    }

    /**
     * 条件/抄送所需的单据属性：优先取 SUBMIT 流水的快照（启动即冻结），
     * 没有快照才回读 {@link DocumentSummaryProvider}。这样中途改单据属性不会重路由。
     */
    private Map<String, Object> documentOf(ApprovalInstance instance) {
        for (ApprovalHistory entry : store.history(instance.getId())) {
            if (ApprovalHistory.SUBMIT.equals(entry.getAction()) && entry.getDetailJson() != null) {
                Object snapshot = json.readMap(entry.getDetailJson()).get("document");
                if (snapshot instanceof Map<?, ?> map) {
                    Map<String, Object> document = new LinkedHashMap<>();
                    map.forEach((key, value) -> document.put(String.valueOf(key), value));
                    return document;
                }
            }
        }
        for (DocumentSummaryProvider provider : documents) {
            if (instance.getBizType().equals(provider.bizType())) {
                try {
                    Map<String, Object> attributes = provider.attributes(
                            instance.getTenantId(), instance.getBizId());
                    if (attributes != null) {
                        return attributes;
                    }
                } catch (RuntimeException ignored) {
                    // 单据读不到时按「无条件」推进，最终走 defaultNext。
                }
            }
        }
        return Map.of();
    }

    private Optional<ApprovalCallback> callback(ApprovalInstance instance) {
        return callbacks.stream().filter(cb -> instance.getBizType().equals(cb.bizType())).findFirst();
    }

    private List<Long> resolverActiveUsers(long tenantId, Collection<Long> userIds) {
        return directory.activeUsersByIds(tenantId, userIds);
    }

    private List<Long> distinct(List<Long> userIds) {
        return new ArrayList<>(new LinkedHashSet<>(userIds));
    }

    private int sortBase(List<ApprovalTask> existing, ApprovalTask task, String signType) {
        int min = sortOf(task);
        int max = sortOf(task);
        for (ApprovalTask other : existing) {
            min = Math.min(min, sortOf(other));
            max = Math.max(max, sortOf(other));
        }
        return ApprovalTask.SIGN_BEFORE.equals(signType) ? min : max;
    }

    private int maxSort(List<ApprovalTask> tasks) {
        int max = 0;
        for (ApprovalTask task : tasks) {
            max = Math.max(max, sortOf(task));
        }
        return max;
    }

    private static int sortOf(ApprovalTask task) {
        return task.getSortNo() == null ? 0 : task.getSortNo();
    }

    private int nextSignNo(long instanceId, String nodeKey, long approverId) {
        List<ApprovalTask> existing = store.tasksOf(instanceId, nodeKey, approverId);
        int max = 0;
        for (ApprovalTask task : existing) {
            max = Math.max(max, task.getSignNo() == null ? 0 : task.getSignNo());
        }
        return max + 1;
    }

    private String normaliseSignType(String type) {
        if (type == null) {
            throw ApprovalException.badRequest(ApprovalException.VALIDATION_FAILED, "加签类型必须是 BEFORE 或 AFTER");
        }
        String upper = type.trim().toUpperCase();
        if (!ApprovalTask.SIGN_BEFORE.equals(upper) && !ApprovalTask.SIGN_AFTER.equals(upper)) {
            throw ApprovalException.badRequest(ApprovalException.VALIDATION_FAILED,
                    "加签类型必须是 BEFORE 或 AFTER，实际：" + type);
        }
        return upper;
    }

    private static void requireBizType(String bizType) {
        if (bizType == null || bizType.isBlank()) {
            throw ApprovalException.badRequest(ApprovalException.VALIDATION_FAILED, "单据类型不能为空");
        }
    }

    private static Collection<Long> domainIds(Map<String, Object> document) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        Object single = document.get("domainId");
        if (single != null) {
            Long id = ConditionEvaluator.asLong(single);
            if (id != null) {
                ids.add(id);
            }
        }
        Object many = document.get("domainIds");
        if (many instanceof Collection<?> collection) {
            for (Object item : collection) {
                Long id = ConditionEvaluator.asLong(item);
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private void history(ApprovalInstance instance, ApprovalTask task, String action, long operatorId,
                         String comment, Map<String, Object> detail) {
        ApprovalHistory entry = new ApprovalHistory();
        entry.setTenantId(instance.getTenantId());
        entry.setInstanceId(instance.getId());
        entry.setTaskId(task == null ? null : task.getId());
        entry.setAction(action);
        entry.setOperatorId(operatorId);
        entry.setComment(comment);
        if (detail != null && !detail.isEmpty()) {
            entry.setDetailJson(json.write(detail));
        }
        entry.setCreatedAt(LocalDateTime.now(clock));
        store.appendHistory(entry);
    }

    private static String safeName(ApprovalTask task) {
        return task.getNodeName() == null ? task.getNodeKey() : task.getNodeName();
    }
}
