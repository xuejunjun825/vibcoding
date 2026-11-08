package com.example.req.approval;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 发布前校验（AC-6）：结构可达、分支有默认、审批人可解析。
 *
 * <p>三条都是「发布时暴露」而不是「运行时卡住」：无审批人的流程发布直接 {@code 400 APPROVER_UNRESOLVED}
 * 且 detail 带 {@code nodeKey}；结构问题 {@code 400 WORKFLOW_INVALID}，detail 同样带定位。
 * 运行时仍保留一次校验（定义被手工改库时 fail-closed）。
 */
@Component
public class WorkflowDefinitionValidator {

    private final ApproverResolver resolver;
    private final ApprovalActorProvider actors;

    public WorkflowDefinitionValidator(ApproverResolver resolver, ApprovalActorProvider actors) {
        this.resolver = resolver;
        this.actors = actors;
    }

    /** 蓝图签名：租户/发起人取当前请求上下文（发布动作的发起人即校验基准人）。 */
    public void validate(WorkflowDefinition definition) {
        ApprovalActor actor = actors.current();
        validate(definition, actor.tenantId(), actor.userId(), List.of());
    }

    public void validate(WorkflowDefinition definition, long tenantId, long initiatorId,
                         Collection<Long> domainIds) {
        validate(definition, tenantId, initiatorId, domainIds, true);
    }

    /**
     * @param requireApprovers 发布时必须为 true（AC-6：无审批人发布直接 400 且带 nodeKey）；
     *                         草稿保存时为 false —— 草稿允许先写角色、后建账号，
     *                         若把审批人解析也压到创建期，未配齐角色的定义根本存不下来。
     */
    public void validate(WorkflowDefinition definition, long tenantId, long initiatorId,
                         Collection<Long> domainIds, boolean requireApprovers) {
        if (definition == null) {
            invalid(null, "流程定义为空");
        }
        if (definition.schemaVersion() == null || definition.schemaVersion() != WorkflowDefinition.SUPPORTED_SCHEMA) {
            invalid(null, "不支持的定义结构版本：" + definition.schemaVersion());
        }
        if (definition.nodes().isEmpty()) {
            invalid(null, "流程定义没有任何节点");
        }

        Map<String, WorkflowDefinition.Node> byKey = new LinkedHashMap<>();
        for (WorkflowDefinition.Node node : definition.nodes()) {
            if (node == null) {
                invalid(null, "存在空节点");
            }
            if (node.key() == null || node.key().isBlank()) {
                invalid(null, "节点缺少 key");
            }
            if (WorkflowDefinition.END.equals(node.key())) {
                invalid(node.key(), "节点 key 不能使用保留字 end");
            }
            if (byKey.put(node.key(), node) != null) {
                invalid(node.key(), "节点 key 重复");
            }
        }

        if (definition.start() == null || definition.start().isBlank()) {
            invalid(null, "流程缺少 start");
        }
        if (!byKey.containsKey(definition.start())) {
            invalid(definition.start(), "start 指向不存在的节点");
        }

        for (WorkflowDefinition.Node node : definition.nodes()) {
            validateNode(node, byKey);
        }
        validateReachable(definition, byKey);

        if (!requireApprovers) {
            return;
        }
        for (WorkflowDefinition.Node node : definition.nodes()) {
            if (node.isApproval()) {
                List<Long> approvers = resolver.resolveApprovers(node, tenantId, initiatorId, domainIds);
                if (approvers.isEmpty()) {
                    throw new ApprovalException(400, ApprovalException.APPROVER_UNRESOLVED,
                            "节点「" + node.displayName() + "」解析不出审批人，不能发布",
                            Map.of("nodeKey", node.key()));
                }
            }
        }
    }

    /** 蓝图签名：给单节点做审批人解析预检（发布校验与运行期兜底共用）。 */
    public List<Long> resolveApprovers(WorkflowDefinition.Node node, long tenantId, long initiatorId) {
        return resolver.resolveApprovers(node, tenantId, initiatorId);
    }

    private void validateNode(WorkflowDefinition.Node node, Map<String, WorkflowDefinition.Node> byKey) {
        if (node.isApproval()) {
            String mode = node.mode();
            if (!WorkflowDefinition.MODE_ALL.equalsIgnoreCase(mode) && !WorkflowDefinition.MODE_ANY.equalsIgnoreCase(mode)) {
                invalid(node.key(), "审批节点的 mode 只能是 ALL 或 ANY，实际 " + mode);
            }
            if (node.approvers().isEmpty()) {
                throw new ApprovalException(400, ApprovalException.APPROVER_UNRESOLVED,
                        "节点「" + node.displayName() + "」没有配置审批人",
                        Map.of("nodeKey", node.key()));
            }
            for (WorkflowDefinition.Approver spec : node.approvers()) {
                validateApproverSpec(node.key(), spec);
            }
            requireNext(node.key(), node.next(), byKey);
            return;
        }
        if (node.isCondition()) {
            if (node.defaultNext() == null || node.defaultNext().isBlank()) {
                invalid(node.key(), "条件节点必须配置 defaultNext");
            }
            requireNext(node.key(), node.defaultNext(), byKey);
            for (WorkflowDefinition.Branch branch : node.branches()) {
                if (branch == null) {
                    invalid(node.key(), "条件分支为空");
                }
                requireNext(node.key(), branch.next(), byKey);
                if (branch.when() == null) {
                    invalid(node.key(), "条件分支缺少 when");
                }
            }
            return;
        }
        if (node.isCc()) {
            if (node.receivers().isEmpty()) {
                invalid(node.key(), "抄送节点没有配置接收人");
            }
            for (WorkflowDefinition.Approver spec : node.receivers()) {
                validateApproverSpec(node.key(), spec);
            }
            requireNext(node.key(), node.next(), byKey);
            return;
        }
        invalid(node.key(), "未知节点类型：" + node.type());
    }

    private void validateApproverSpec(String nodeKey, WorkflowDefinition.Approver spec) {
        if (spec == null || spec.type() == null) {
            invalid(nodeKey, "审批人声明缺少 type");
        }
        boolean known = spec.is(WorkflowDefinition.Approver.TYPE_ROLE)
                || spec.is(WorkflowDefinition.Approver.TYPE_USER)
                || spec.is(WorkflowDefinition.Approver.TYPE_SUPERVISOR)
                || spec.is(WorkflowDefinition.Approver.TYPE_DOMAIN_OWNER);
        if (!known) {
            invalid(nodeKey, "未知审批人类型：" + spec.type());
        }
        if ((spec.is(WorkflowDefinition.Approver.TYPE_ROLE) || spec.is(WorkflowDefinition.Approver.TYPE_USER))
                && (spec.value() == null || spec.value().isBlank())) {
            invalid(nodeKey, "审批人 " + spec.type() + " 缺少 value");
        }
        if (spec.is(WorkflowDefinition.Approver.TYPE_USER) && ConditionEvaluator.asLong(spec.value()) == null) {
            invalid(nodeKey, "USER 型审批人的 value 必须是用户 ID");
        }
    }

    private void requireNext(String nodeKey, String next, Map<String, WorkflowDefinition.Node> byKey) {
        if (next == null || next.isBlank()) {
            invalid(nodeKey, "节点缺少 next");
        }
        if (!WorkflowDefinition.END.equals(next) && !byKey.containsKey(next)) {
            invalid(nodeKey, "next 指向不存在的节点：" + next);
        }
    }

    private void validateReachable(WorkflowDefinition definition, Map<String, WorkflowDefinition.Node> byKey) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(definition.start());
        while (!queue.isEmpty()) {
            String key = queue.poll();
            if (WorkflowDefinition.END.equals(key) || !visited.add(key)) {
                continue;
            }
            WorkflowDefinition.Node node = byKey.get(key);
            if (node == null) {
                continue;
            }
            queue.addAll(successors(node));
        }
        Set<String> unreachable = new LinkedHashSet<>(byKey.keySet());
        unreachable.removeAll(visited);
        if (!unreachable.isEmpty()) {
            String firstUnreachable = unreachable.iterator().next();
            invalid(firstUnreachable, "节点从 start 不可达：" + unreachable);
        }
    }

    static List<String> successors(WorkflowDefinition.Node node) {
        List<String> nexts = new ArrayList<>();
        if (node.isApproval() || node.isCc()) {
            if (node.next() != null) {
                nexts.add(node.next());
            }
        } else {
            for (WorkflowDefinition.Branch branch : node.branches()) {
                if (branch != null && branch.next() != null) {
                    nexts.add(branch.next());
                }
            }
            if (node.defaultNext() != null) {
                nexts.add(node.defaultNext());
            }
        }
        return nexts;
    }

    private static void invalid(String nodeKey, String message) {
        Map<String, Object> detail = new LinkedHashMap<>();
        if (nodeKey != null) {
            detail.put("nodeKey", nodeKey);
        }
        throw new ApprovalException(400, ApprovalException.WORKFLOW_INVALID, message, detail);
    }
}
