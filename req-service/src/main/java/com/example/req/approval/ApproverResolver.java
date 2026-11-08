package com.example.req.approval;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 把定义里的审批人声明解析成「启用用户 ID 列表」。
 *
 * <p>解析规则（§5.6）：
 * <ul>
 *   <li>{@code ROLE}：租户内该角色的启用用户</li>
 *   <li>{@code USER}：按 ID 取，非启用/跨租户一律忽略</li>
 *   <li>{@code SUPERVISOR}：发起人组织链<b>由近及远</b>的上级组织里的管理者角色；全无则退到租户级</li>
 *   <li>{@code DOMAIN_OWNER}：单据所属域的负责人（域来自文档属性，可能多个）</li>
 * </ul>
 * 同一节点内的多个声明取并集（配合 ANY＝或签），顺序稳定、去重；解析为空由调用方转
 * {@code 400 APPROVER_UNRESOLVED}（发布时）或 {@code 409}（运行时）。
 */
@Component
public class ApproverResolver {

    /** SUPERVISOR 语义取「上级管理者」这些角色码，按顺序尝试。 */
    static final List<String> SUPERVISOR_ROLE_CODES = List.of("PMO", "SUB_BUSINESS_LEADER", "MGMT");

    private final ApproverDirectory directory;

    public ApproverResolver(ApproverDirectory directory) {
        this.directory = directory;
    }

    /** 蓝图签名：不带域属性时按「无域」解析（DOMAIN_OWNER 会解析为空）。 */
    public List<Long> resolveApprovers(WorkflowDefinition.Node node, long tenantId, long initiatorId) {
        return resolveApprovers(node, tenantId, initiatorId, List.of());
    }

    public List<Long> resolveApprovers(WorkflowDefinition.Node node, long tenantId, long initiatorId,
                                       Collection<Long> domainIds) {
        if (node == null || node.approvers().isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> resolved = new LinkedHashSet<>();
        for (WorkflowDefinition.Approver spec : node.approvers()) {
            resolved.addAll(resolve(spec, tenantId, initiatorId, domainIds));
        }
        return new ArrayList<>(resolved);
    }

    public List<Long> resolve(WorkflowDefinition.Approver spec, long tenantId, long initiatorId,
                              Collection<Long> domainIds) {
        if (spec == null) {
            return List.of();
        }
        if (spec.is(WorkflowDefinition.Approver.TYPE_ROLE)) {
            return spec.value() == null ? List.of() : directory.activeUsersByRole(tenantId, spec.value());
        }
        if (spec.is(WorkflowDefinition.Approver.TYPE_USER)) {
            Long userId = ConditionEvaluator.asLong(spec.value());
            return userId == null ? List.of() : directory.activeUsersByIds(tenantId, List.of(userId));
        }
        if (spec.is(WorkflowDefinition.Approver.TYPE_SUPERVISOR)) {
            return supervisors(tenantId, initiatorId);
        }
        if (spec.is(WorkflowDefinition.Approver.TYPE_DOMAIN_OWNER)) {
            return directory.domainOwners(tenantId, domainIds == null ? List.of() : domainIds);
        }
        return List.of();
    }

    /**
     * 上级管理者：从发起人组织的<b>直接上级</b>开始逐级向上，取第一个能解析出管理者的层级；
     * 只有顶层组织时才退回本组织；仍为空则退到租户级（发起人本人始终排除）。
     */
    List<Long> supervisors(long tenantId, long initiatorId) {
        Long orgId = directory.orgIdOf(tenantId, initiatorId);
        List<Long> chain = orgId == null ? List.of() : directory.orgChain(tenantId, orgId);
        List<Long> levels = chain.size() > 1 ? chain.subList(1, chain.size()) : chain;
        for (Long level : levels) {
            List<Long> found = managersInOrg(tenantId, level, initiatorId);
            if (!found.isEmpty()) {
                return found;
            }
        }
        LinkedHashSet<Long> tenantWide = new LinkedHashSet<>();
        for (String roleCode : SUPERVISOR_ROLE_CODES) {
            tenantWide.addAll(directory.activeUsersByRole(tenantId, roleCode));
        }
        tenantWide.remove(initiatorId);
        return new ArrayList<>(tenantWide);
    }

    private List<Long> managersInOrg(long tenantId, long orgId, long initiatorId) {
        LinkedHashSet<Long> users = new LinkedHashSet<>();
        for (String roleCode : SUPERVISOR_ROLE_CODES) {
            users.addAll(directory.activeUsersByRoleInOrgs(tenantId, roleCode, List.of(orgId)));
        }
        users.remove(initiatorId);
        return new ArrayList<>(users);
    }
}
