package com.example.req.approval;

import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * {@link ApproverDirectory} 的 JPA 实现：只读目录表，原生 SQL。
 *
 * <p>不用 ScopedJdbc 的原因：审批人解析读的是「人/角色/组织/域」目录而不是业务行，且必须能在
 * 任何 scope 下解析（例如 PMO 审批全局单据）；但所有 SQL 都显式带 {@code tenant_id}。
 * 容忍 {@code status} 为空的历史行（{@code status <> 'DISABLED'}）。
 */
@Component
public class JpaApproverDirectory implements ApproverDirectory {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @SuppressWarnings("unchecked")
    public List<Long> activeUsersByRole(long tenantId, String roleCode) {
        List<?> rows = entityManager.createNativeQuery("""
                        select distinct u.id
                          from sys_user u
                          join sys_user_role ur on ur.user_id = u.id and ur.tenant_id = u.tenant_id
                          join sys_role r on r.id = ur.role_id and r.tenant_id = u.tenant_id
                         where u.tenant_id = ?1
                           and r.code = ?2
                           and (u.status is null or u.status <> 'DISABLED')
                         order by u.id
                        """)
                .setParameter(1, tenantId)
                .setParameter(2, roleCode)
                .getResultList();
        return longs(rows);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Long> activeUsersByRoleInOrgs(long tenantId, String roleCode, Collection<Long> orgIds) {
        if (orgIds == null || orgIds.isEmpty()) {
            return List.of();
        }
        List<?> rows = entityManager.createNativeQuery("""
                        select distinct u.id
                          from sys_user u
                          join sys_user_role ur on ur.user_id = u.id and ur.tenant_id = u.tenant_id
                          join sys_role r on r.id = ur.role_id and r.tenant_id = u.tenant_id
                         where u.tenant_id = ?1
                           and r.code = ?2
                           and u.org_id in (?3)
                           and (u.status is null or u.status <> 'DISABLED')
                         order by u.id
                        """)
                .setParameter(1, tenantId)
                .setParameter(2, roleCode)
                .setParameter(3, orgIds)
                .getResultList();
        return longs(rows);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Long> activeUsersByIds(long tenantId, Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        List<?> rows = entityManager.createNativeQuery("""
                        select u.id
                          from sys_user u
                         where u.tenant_id = ?1
                           and u.id in (?2)
                           and (u.status is null or u.status <> 'DISABLED')
                         order by u.id
                        """)
                .setParameter(1, tenantId)
                .setParameter(2, userIds)
                .getResultList();
        return longs(rows);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Long> domainOwners(long tenantId, Collection<Long> domainIds) {
        if (domainIds == null || domainIds.isEmpty()) {
            return List.of();
        }
        List<?> rows = entityManager.createNativeQuery("""
                        select d.owner_user_id
                          from biz_domain d
                         where d.tenant_id = ?1
                           and d.id in (?2)
                           and d.owner_user_id is not null
                           and (d.enabled is null or d.enabled = 1)
                         order by d.id
                        """)
                .setParameter(1, tenantId)
                .setParameter(2, domainIds)
                .getResultList();
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (Object row : rows) {
            Long id = asLong(row);
            if (id != null) {
                unique.add(id);
            }
        }
        return new ArrayList<>(unique);
    }

    @Override
    public Long orgIdOf(long tenantId, long userId) {
        List<?> rows = entityManager.createNativeQuery(
                        "select u.org_id from sys_user u where u.tenant_id = ?1 and u.id = ?2")
                .setParameter(1, tenantId)
                .setParameter(2, userId)
                .getResultList();
        return rows.isEmpty() ? null : asLong(rows.get(0));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Long> orgChain(long tenantId, long orgId) {
        List<?> rows = entityManager.createNativeQuery(
                        "select o.id, o.parent_id from sys_org o where o.tenant_id = ?1")
                .setParameter(1, tenantId)
                .getResultList();
        java.util.Map<Long, Long> parentOf = new java.util.HashMap<>();
        for (Object row : rows) {
            Object[] cells = (Object[]) row;
            Long id = asLong(cells[0]);
            if (id != null) {
                parentOf.put(id, asLong(cells[1]));
            }
        }
        List<Long> chain = new ArrayList<>();
        Long cursor = orgId;
        while (cursor != null && !chain.contains(cursor)) {
            chain.add(cursor);
            cursor = parentOf.get(cursor);
        }
        return chain;
    }

    @Override
    public boolean hasAnyRole(long tenantId, long userId, Collection<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return false;
        }
        List<?> rows = entityManager.createNativeQuery("""
                        select count(*)
                          from sys_user_role ur
                          join sys_role r on r.id = ur.role_id and r.tenant_id = ur.tenant_id
                         where ur.tenant_id = ?1
                           and ur.user_id = ?2
                           and r.code in (?3)
                        """)
                .setParameter(1, tenantId)
                .setParameter(2, userId)
                .setParameter(3, roleCodes)
                .getResultList();
        Long count = rows.isEmpty() ? 0L : asLong(rows.get(0));
        return count != null && count > 0;
    }

    private static List<Long> longs(List<?> rows) {
        List<Long> ids = new ArrayList<>(rows.size());
        for (Object row : rows) {
            Long id = asLong(row);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
