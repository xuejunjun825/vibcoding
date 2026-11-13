package com.example.req.sys;

import java.util.List;
import java.util.Map;

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

    /** r18 role row: {@code builtin} and {@code status} drive the UI's lock/stop controls. */
    public record RoleRow(long id, String code, String name, String description, boolean builtin,
                          String status, int userCount, String updatedAt) {
    }

    public record PermissionFunction(String code, List<String> allow, List<String> restricted,
                                     List<String> deny) {
    }

    public record PermissionMatrixView(List<PermissionFunction> functions, List<RoleRow> roles) {
    }

    // ------------------------------------------------------------------ r18 grants

    /** One subject's level, as the client sends it back on save. */
    public record GrantInput(String type, String code, String access) {
    }

    public record FunctionGrant(String code, String access) {
    }

    public record ActionGrant(String code, String name, String access, boolean highRisk,
                              List<String> functionCodes) {
    }

    public record PageGrant(String code, String name, String routeName, String access,
                            List<FunctionGrant> functionCodes, List<ActionGrant> actions) {
    }

    public record ModuleGrant(String code, String name, List<PageGrant> pages) {
    }

    /**
     * A configuration that is stored but has no effect yet — e.g. actions granted while the page
     * itself is DENY. Surfacing it is what keeps "I ticked it and nothing happened" from becoming
     * a support ticket.
     */
    public record Warning(String code, String page, List<String> actions) {
    }

    public record RoleGrantView(RoleRow role, long epoch, List<ModuleGrant> modules,
                                List<Warning> warnings) {
    }

    public record GrantSaveResult(long epoch, int added, int removed, int changed,
                                  List<String> highRiskGranted, List<Warning> warnings,
                                  Map<String, Object> effective) {
    }

    private SysViews() {
    }
}
