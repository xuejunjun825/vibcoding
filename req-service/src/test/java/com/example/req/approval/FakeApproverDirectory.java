package com.example.req.approval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 内存版人员目录：角色 → 用户、用户 → 组织、组织 → 上级链、域 → 负责人。 */
final class FakeApproverDirectory implements ApproverDirectory {

    private final Map<String, List<Long>> roleUsers = new LinkedHashMap<>();
    private final Map<Long, Long> userOrg = new LinkedHashMap<>();
    private final Map<Long, List<Long>> orgChains = new LinkedHashMap<>();
    private final Map<Long, List<Long>> domainOwners = new LinkedHashMap<>();
    private final LinkedHashSet<Long> activeUsers = new LinkedHashSet<>();

    FakeApproverDirectory role(String roleCode, Long... userIds) {
        roleUsers.put(roleCode, List.of(userIds));
        activeUsers.addAll(List.of(userIds));
        return this;
    }

    FakeApproverDirectory userInOrg(long userId, long orgId, Long... chain) {
        activeUsers.add(userId);
        userOrg.put(userId, orgId);
        List<Long> levels = new ArrayList<>();
        levels.add(orgId);
        if (chain != null) {
            levels.addAll(List.of(chain));
        }
        orgChains.put(orgId, levels);
        return this;
    }

    FakeApproverDirectory domainOwner(long domainId, Long... userIds) {
        domainOwners.put(domainId, List.of(userIds));
        activeUsers.addAll(List.of(userIds));
        return this;
    }

    FakeApproverDirectory active(long... userIds) {
        activeUsers.addAll(List.of(Long.valueOf(0)).subList(0, 0));
        for (long userId : userIds) {
            activeUsers.add(userId);
        }
        return this;
    }

    @Override
    public List<Long> activeUsersByRole(long tenantId, String roleCode) {
        return new ArrayList<>(roleUsers.getOrDefault(roleCode, List.of()));
    }

    @Override
    public List<Long> activeUsersByRoleInOrgs(long tenantId, String roleCode, Collection<Long> orgIds) {
        List<Long> users = roleUsers.getOrDefault(roleCode, List.of());
        List<Long> found = new ArrayList<>();
        for (Long user : users) {
            Long org = userOrg.get(user);
            if (org != null && orgIds.contains(org)) {
                found.add(user);
            }
        }
        return found;
    }

    @Override
    public List<Long> activeUsersByIds(long tenantId, Collection<Long> userIds) {
        List<Long> found = new ArrayList<>();
        for (Long userId : userIds) {
            if (userId != null && activeUsers.contains(userId) && !found.contains(userId)) {
                found.add(userId);
            }
        }
        return found;
    }

    @Override
    public List<Long> domainOwners(long tenantId, Collection<Long> domainIds) {
        LinkedHashSet<Long> owners = new LinkedHashSet<>();
        for (Long domainId : domainIds) {
            owners.addAll(domainOwners.getOrDefault(domainId, List.of()));
        }
        return new ArrayList<>(owners);
    }

    @Override
    public Long orgIdOf(long tenantId, long userId) {
        return userOrg.get(userId);
    }

    @Override
    public List<Long> orgChain(long tenantId, long orgId) {
        return orgChains.getOrDefault(orgId, List.of(orgId));
    }

    @Override
    public boolean hasAnyRole(long tenantId, long userId, Collection<String> roleCodes) {
        for (String roleCode : roleCodes) {
            if (roleUsers.getOrDefault(roleCode, List.of()).contains(userId)) {
                return true;
            }
        }
        return false;
    }
}
