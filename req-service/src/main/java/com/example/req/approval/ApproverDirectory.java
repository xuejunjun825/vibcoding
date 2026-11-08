package com.example.req.approval;

import java.util.Collection;
import java.util.List;

/**
 * 审批人解析所需的人员/组织目录（SPI）。
 *
 * <p>引擎只认这个接口：生产实现走 {@link JpaApproverDirectory}（原生 SQL 读 T1 的
 * {@code sys_user / sys_user_role / sys_role / sys_org / biz_domain}），单测用内存实现。
 * 目录查询不是业务读路径，故不经过 ScopedJdbc，但<b>每个方法都必须带 tenantId</b>，
 * 否则跨租户解析审批人会把单据推给别的租户。
 */
public interface ApproverDirectory {

    /** 租户内拥有该角色的启用用户。 */
    List<Long> activeUsersByRole(long tenantId, String roleCode);

    /** 限定组织集合内拥有该角色的启用用户。 */
    List<Long> activeUsersByRoleInOrgs(long tenantId, String roleCode, Collection<Long> orgIds);

    /** 按 ID 取启用用户（用于 USER 型审批人、转办/加签目标校验）。 */
    List<Long> activeUsersByIds(long tenantId, Collection<Long> userIds);

    /** 域负责人（可多域，去重保序）。 */
    List<Long> domainOwners(long tenantId, Collection<Long> domainIds);

    /** 用户所属组织；不存在返回 {@code null}。 */
    Long orgIdOf(long tenantId, long userId);

    /** 组织自身 + 各级上级组织 ID，按「近 → 远」排序。 */
    List<Long> orgChain(long tenantId, long orgId);

    /** 用户是否拥有给定角色之一。 */
    boolean hasAnyRole(long tenantId, long userId, Collection<String> roleCodes);
}
