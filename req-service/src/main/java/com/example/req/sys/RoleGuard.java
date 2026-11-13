package com.example.req.sys;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.platform.common.error.ApiException;
import com.example.platform.contract.ActionRegistry;
import com.example.platform.contract.MenuRegistry;
import com.example.platform.contract.PermissionMatrix;
import com.example.platform.contract.PermissionMatrix.Access;

/**
 * The rules that keep a permission editor from making the system unadministrable, as pure code.
 *
 * <p>Two rules live here, and both are cheap to test because neither touches the database:</p>
 * <ul>
 *   <li><b>No lockout.</b> The platform-admin role must keep {@code org:manage} and
 *       {@code tenant:manage}; clearing either — or leaving the tenant with no active administrator
 *       at all — is refused with {@code 409 ADMIN_LOCKOUT_RISK}. This is the one 409 that protects
 *       against irreversible self-harm: without it, the person who unticks the box loses the page
 *       they would need to tick it back.</li>
 *   <li><b>High-risk confirmation.</b> Force-release, user-disable and role-grant need an explicit
 *       {@code confirmHighRisk} flag, and only when they are <em>newly</em> granted — re-saving an
 *       unchanged grant must not nag.</li>
 * </ul>
 */
public final class RoleGuard {

    /** The two function codes the platform administrator may never lose. */
    public static final List<String> ADMIN_CODES = List.of("org:manage", "tenant:manage");

    public static final String LOCKOUT_RISK = "ADMIN_LOCKOUT_RISK";
    public static final String HIGH_RISK_REQUIRES_CONFIRM = "HIGH_RISK_REQUIRES_CONFIRM";

    /**
     * Throws {@code 409 ADMIN_LOCKOUT_RISK} when the draft would leave nobody able to administer
     * the tenant.
     *
     * @param draftForAdminRole the function levels the platform-admin role would end up with; an
     *                          empty map means "this save does not touch that role" (not a lockout)
     * @param activeAdminCount  users who currently hold the platform-admin role and are active
     */
    public static void assertNoLockout(Map<String, Access> draftForAdminRole,
                                       int activeAdminCount) {
        if (draftForAdminRole == null || draftForAdminRole.isEmpty()) {
            return;
        }
        List<String> lost = new ArrayList<>();
        for (String code : ADMIN_CODES) {
            Access access = draftForAdminRole.get(code);
            if (access == null || access == Access.DENY) {
                lost.add(code);
            }
        }
        if (!lost.isEmpty()) {
            throw new ApiException(409, LOCKOUT_RISK,
                    "不能移除平台管理员的" + String.join("、", lost)
                            + "权限：移除后将无人能进入权限管理页",
                    Map.of("codes", lost, "activeAdminCount", activeAdminCount));
        }
        if (activeAdminCount <= 0) {
            throw new ApiException(409, LOCKOUT_RISK, "租户没有可用的平台管理员账号",
                    Map.of("codes", ADMIN_CODES, "activeAdminCount", 0));
        }
    }

    /** High-risk action codes the save is about to hand out. */
    public static List<String> highRiskGrants(String roleCode, List<SysViews.GrantInput> grants) {
        return highRiskGrants(roleCode, grants, Map.of());
    }

    /**
     * High-risk actions granted by this save that were not granted before.
     *
     * @param before action code → level as currently stored (missing = DENY)
     */
    public static List<String> highRiskGrants(String roleCode, List<SysViews.GrantInput> grants,
                                              Map<String, String> before) {
        Set<String> codes = new LinkedHashSet<>();
        for (SysViews.GrantInput grant : grants) {
            if (grant == null || grant.code() == null || !grantCodeIsAction(grant)) {
                continue;
            }
            Access access = parse(grant.access());
            if (access == Access.DENY) {
                continue;
            }
            if (!ActionRegistry.isRegistered(grant.code())) {
                continue;
            }
            if (!ActionRegistry.require(grant.code()).highRisk()) {
                continue;
            }
            String was = before.get(grant.code());
            if (parse(was) == Access.DENY) {
                codes.add(grant.code());
            }
        }
        return List.copyOf(codes);
    }

    /** The tenant's administrator role code — the only role the lockout rule protects. */
    public static String adminRoleCode() {
        return RoleCatalog.ROLES.stream()
                .map(RoleCatalog.RoleDef::code)
                .filter("PLATFORM_ADMIN"::equals)
                .findFirst()
                .orElse("PLATFORM_ADMIN");
    }

    public static Access parse(String value) {
        if (value == null || value.isBlank()) {
            return Access.DENY;
        }
        try {
            return Access.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Access.DENY;
        }
    }

    /** Validate a subject type/code pair against the registries. */
    public static void validateSubject(String type, String code) {
        if (type == null || type.isBlank()) {
            throw new ApiException(400, "UNKNOWN_SUBJECT", "授权对象缺少类型");
        }
        switch (type.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "FUNCTION" -> {
                if (!PermissionMatrix.functions().contains(code)) {
                    throw new ApiException(400, "UNKNOWN_SUBJECT", "未知功能码：" + code);
                }
            }
            case "MENU" -> {
                if (!MenuRegistry.isRegistered(code)) {
                    throw new ApiException(400, "UNKNOWN_SUBJECT", "未知页面码：" + code);
                }
            }
            case "ACTION" -> {
                if (!ActionRegistry.isRegistered(code)) {
                    throw new ApiException(400, "UNKNOWN_SUBJECT", "未知动作码：" + code);
                }
            }
            default -> throw new ApiException(400, "UNKNOWN_SUBJECT", "未知授权类型：" + type);
        }
    }

    private static boolean grantCodeIsAction(SysViews.GrantInput grant) {
        return grant.type() != null
                && "ACTION".equals(grant.type().trim().toUpperCase(java.util.Locale.ROOT));
    }

    private RoleGuard() {
    }
}
