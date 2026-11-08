package com.example.platform.common.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.PermissionMatrix;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link PermissionMatrix} before the controller runs.
 *
 * <p>DENY → 403 with an audit row written in its own transaction (the request has no business
 * transaction to roll back). RESTRICTED passes: the matrix says "allowed within your data scope",
 * and the scope narrowing happens in SQL, not by refusing the call. An empty role set denies
 * everything (the contract's {@code accessOf} already fail-closes on empty roles).</p>
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private final AuditService auditService;

    public PermissionInterceptor(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        String path = AuthFilter.path(request);
        if (isOpen(path)) {
            return true;
        }
        PermissionRouteTable.Requirement requirement = requirement(handler, request, path);
        if (requirement == null || requirement.codes().isEmpty()) {
            return true;
        }
        List<String> roles = TenantContext.roles();
        boolean granted = requirement.anyOf()
                ? requirement.codes().stream()
                .anyMatch(code -> PermissionMatrix.accessOf(code, roles)
                        != PermissionMatrix.Access.DENY)
                : requirement.codes().stream()
                .allMatch(code -> PermissionMatrix.accessOf(code, roles)
                        != PermissionMatrix.Access.DENY);
        if (granted) {
            return true;
        }
        deny(request, path, requirement, roles);
        throw new ApiException(403, "FORBIDDEN", "无权执行该操作", Map.of(
                "functions", requirement.codes(),
                "path", path,
                "method", request.getMethod()));
    }

    private PermissionRouteTable.Requirement requirement(Object handler, HttpServletRequest request,
                                                          String path) {
        if (handler instanceof HandlerMethod method) {
            RequiresPermission annotation = method.getMethodAnnotation(RequiresPermission.class);
            if (annotation == null) {
                annotation = method.getBeanType().getAnnotation(RequiresPermission.class);
            }
            if (annotation != null && annotation.value().length > 0) {
                return new PermissionRouteTable.Requirement(List.of(annotation.value()),
                        annotation.anyOf());
            }
        }
        return PermissionRouteTable.requirementFor(request.getMethod(), path);
    }

    private void deny(HttpServletRequest request, String path,
                      PermissionRouteTable.Requirement requirement, List<String> roles) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("path", path);
        detail.put("method", request.getMethod());
        detail.put("functions", requirement.codes());
        detail.put("roles", roles);
        Map<String, String> access = new LinkedHashMap<>();
        for (String code : requirement.codes()) {
            access.put(code, PermissionMatrix.accessOf(code, roles).name());
        }
        detail.put("access", access);
        auditService.recordInNewTx("PERMISSION_DENIED", "function", null, detail);
    }

    private static boolean isOpen(String path) {
        if (path == null) {
            return true;
        }
        if (path.equals("/auth/login") || path.equals("/health") || path.equals("/error")
                || path.equals("/favicon.ico")) {
            return true;
        }
        return path.startsWith("/internal/") || path.startsWith("/actuator/")
                || path.startsWith("/openapi/");
    }

    /** Roles that hold at least one of the codes; used by tests to prove the table is complete. */
    public static Set<String> rolesWith(String function) {
        return Set.copyOf(PermissionMatrix.roles().stream()
                .filter(role -> PermissionMatrix.accessFor(role, function)
                        != PermissionMatrix.Access.DENY)
                .toList());
    }
}
