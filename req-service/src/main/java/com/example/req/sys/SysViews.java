package com.example.req.sys;

import java.util.List;

/** Response shapes for the system-administration endpoints (mirrors web/src/api/admin.ts). */
public final class SysViews {

    public record TenantRow(long tenantId, String code, String name, String status,
                            String createdAt) {
    }

    public record Provisioned(int roles, int domains, int dicts, int orgs, String adminUser) {
    }

    public record CreatedTenant(long tenantId, String code, String name, Provisioned provisioned) {
    }

    public record OrgRow(long id, String name, Long parentId, Long tenantId, String code,
                         String type, List<OrgRow> children) {
    }

    public record DomainRow(long id, String code, String name, Long ownerId, String ownerName,
                            boolean enabled) {
    }

    public record VendorRow(long id, String code, String name, String contact, String status,
                            List<Long> domainIds) {
    }

    public record UserRow(long id, String username, String name, List<String> roles, Long orgId,
                          String orgName, List<Long> domainIds, Long vendorId, String status) {
    }

    public record AuditRow(long id, String action, String bizType, Long bizId, String userName,
                           Object detail, String createdAt) {
    }

    public record RoleRow(String code, String name) {
    }

    public record PermissionFunction(String code, List<String> allow, List<String> restricted,
                                     List<String> deny) {
    }

    public record PermissionMatrixView(List<PermissionFunction> functions, List<RoleRow> roles) {
    }

    private SysViews() {
    }
}
