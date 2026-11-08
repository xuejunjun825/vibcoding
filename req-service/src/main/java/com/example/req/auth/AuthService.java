package com.example.req.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.example.platform.common.error.ApiException;
import com.example.platform.common.security.UserIdentity;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.JwtCodec;
import com.example.platform.contract.JwtPayload;
import com.example.platform.contract.PermissionMatrix;
import com.example.platform.contract.ScopeTier;
import com.example.req.sys.PasswordService;
import com.example.req.sys.SysDb;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Login and the "who am I / what may I do" endpoint. */
@Service
public class AuthService {

    private static final String DEFAULT_ROUTE = "/requirements";

    private final SysDb db;
    private final PasswordService passwords;
    private final JwtCodec codec;
    private final long ttlMinutes;

    public AuthService(SysDb db, PasswordService passwords,
                       @Value("${platform.jwt.secret:dev-only-jwt-secret-please-change-32bytes-min}")
                       String secret,
                       @Value("${platform.jwt.ttl-minutes:720}") long ttlMinutes) {
        this.db = db;
        this.passwords = passwords;
        this.codec = new JwtCodec(secret);
        this.ttlMinutes = ttlMinutes;
    }

    @Transactional
    public AuthViews.LoginResponse login(AuthViews.LoginRequest request) {
        if (request == null || blank(request.tenantCode()) || blank(request.username())
                || blank(request.password())) {
            throw new ApiException(400, "VALIDATION_FAILED", "租户编码、用户名和密码均为必填", null);
        }
        String tenantCode = request.tenantCode().trim();
        Object[] tenant = db.row("SELECT id, code, name, status FROM sys_tenant WHERE code = ?",
                tenantCode).orElseThrow(() -> new ApiException(401, "INVALID_CREDENTIALS",
                "租户、用户名或密码不正确", null));
        long tenantId = SysDb.asLong(tenant[0]);
        if (!"ACTIVE".equalsIgnoreCase(SysDb.asString(tenant[3]))) {
            throw new ApiException(403, "TENANT_DISABLED", "租户已停用", null);
        }
        Object[] user = db.row("SELECT id, name, password_hash, status, vendor_id, org_id"
                        + " FROM sys_user WHERE tenant_id = ? AND username = ?",
                tenantId, request.username().trim())
                .orElseThrow(() -> new ApiException(401, "INVALID_CREDENTIALS",
                        "租户、用户名或密码不正确", null));
        if (!passwords.matches(request.password(), SysDb.asString(user[2]))) {
            throw new ApiException(401, "INVALID_CREDENTIALS", "租户、用户名或密码不正确", null);
        }
        if (!"ACTIVE".equalsIgnoreCase(SysDb.asString(user[3]))) {
            throw new ApiException(403, "USER_DISABLED", "账号已停用", null);
        }
        long userId = SysDb.asLong(user[0]);
        List<String> roles = roles(tenantId, userId);
        if (roles.isEmpty()) {
            throw new ApiException(403, "NO_ROLE", "账号未分配任何角色，无法登录", null);
        }
        Set<Long> domainIds = domains(tenantId, userId);
        Long vendorId = SysDb.asNullableLong(user[4]);
        Set<Long> orgIds = orgSubtree(tenantId, SysDb.asNullableLong(user[5]));

        long now = Instant.now().getEpochSecond();
        long expiresAt = now + ttlMinutes * 60;
        JwtPayload payload = new JwtPayload(userId, tenantId, request.username().trim(), roles,
                domainIds, vendorId, orgIds, now, expiresAt);
        String token = codec.issue(payload, Duration.ofMinutes(ttlMinutes));
        db.update("UPDATE sys_user SET last_login_at = CURRENT_TIMESTAMP(3)"
                + " WHERE tenant_id = ? AND id = ?", tenantId, userId);

        return new AuthViews.LoginResponse(token, ttlMinutes * 60, sessionUser(userId,
                SysDb.asString(user[1]), roles, domainIds, vendorId, tenantId,
                SysDb.asString(tenant[2]), request.username().trim()),
                permissions(roles), DEFAULT_ROUTE, tenantId, tenantCode);
    }

    public AuthViews.MeResponse me() {
        DataScope scope = TenantContext.require();
        TenantContext.Principal principal = TenantContext.principal();
        if (principal == null) {
            throw new ApiException(401, "UNAUTHENTICATED", "未登录", null);
        }
        List<Long> domainIds = new ArrayList<>(scope.domainIds());
        domainIds.sort(Long::compareTo);
        AuthViews.SessionUser user = new AuthViews.SessionUser(principal.userId(),
                principal.name(), principal.roles(), domainIds, principal.vendorId(),
                scope.tenantId(), principal.tenantName(), principal.username());
        List<Long> orgIds = new ArrayList<>(scope.orgSubtreeIds());
        orgIds.sort(Long::compareTo);
        return new AuthViews.MeResponse(user, permissions(principal.roles()),
                new AuthViews.ScopeInfo(scope.tier().name(), domainIds, scope.vendorId(), orgIds,
                        label(scope)));
    }

    private static String label(DataScope scope) {
        return switch (scope.tier()) {
            case TENANT -> "全部数据";
            case VENDOR -> "厂商范围";
            case DOMAIN -> scope.domainIds().size() + " 个业务域";
            case ORG -> "组织范围";
            case BIZ -> "业务单元范围";
            case SELF -> "仅本人提报";
        };
    }

    public List<AuthViews.PermissionEntry> permissions(List<String> roles) {
        List<AuthViews.PermissionEntry> entries = new ArrayList<>();
        for (String function : PermissionMatrix.functions()) {
            PermissionMatrix.Access access = PermissionMatrix.accessOf(function, roles);
            if (access != PermissionMatrix.Access.DENY) {
                entries.add(new AuthViews.PermissionEntry(function, access.name()));
            }
        }
        return entries;
    }

    private AuthViews.SessionUser sessionUser(long id, String name, List<String> roles,
                                              Set<Long> domainIds, Long vendorId, long tenantId,
                                              String tenantName, String username) {
        List<Long> domains = new ArrayList<>(domainIds);
        domains.sort(Long::compareTo);
        return new AuthViews.SessionUser(id, name, roles, domains, vendorId, tenantId,
                tenantName, username);
    }

    private List<String> roles(long tenantId, long userId) {
        return db.scalars("SELECT r.code FROM sys_user_role ur"
                        + " JOIN sys_role r ON r.id = ur.role_id AND r.tenant_id = ur.tenant_id"
                        + " WHERE ur.tenant_id = ? AND ur.user_id = ? ORDER BY r.id",
                tenantId, userId).stream().map(SysDb::asString).toList();
    }

    private Set<Long> domains(long tenantId, long userId) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Object value : db.scalars("SELECT domain_id FROM sys_user_domain"
                + " WHERE tenant_id = ? AND user_id = ?", tenantId, userId)) {
            ids.add(SysDb.asLong(value));
        }
        return ids;
    }

    private Set<Long> orgSubtree(long tenantId, Long orgId) {
        if (orgId == null) {
            return Set.of();
        }
        Optional<Object[]> org = db.row("SELECT path FROM sys_org WHERE tenant_id = ? AND id = ?",
                tenantId, orgId);
        if (org.isEmpty()) {
            return Set.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (Object value : db.scalars("SELECT id FROM sys_org WHERE tenant_id = ? AND path LIKE ?",
                tenantId, SysDb.asString(org.get()[0]) + "%")) {
            ids.add(SysDb.asLong(value));
        }
        return ids;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** A tenant admin sees the whole tenant; used by the login response's scope hint. */
    public static ScopeTier tierOf(List<String> roles, Long vendorId) {
        if (roles.contains("PLATFORM_ADMIN")) {
            return ScopeTier.TENANT;
        }
        if (vendorId != null) {
            return ScopeTier.VENDOR;
        }
        if (roles.stream().anyMatch(UserIdentity.BIZ_ROLES::contains)) {
            return ScopeTier.BIZ;
        }
        if (roles.stream().anyMatch(UserIdentity.SELF_ROLES::contains)) {
            return ScopeTier.SELF;
        }
        return ScopeTier.DOMAIN;
    }
}
