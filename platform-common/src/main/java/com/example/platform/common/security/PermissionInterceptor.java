package com.example.platform.common.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.ActionRegistry;
import com.example.platform.contract.PermissionMatrix;
import com.example.platform.contract.PermissionMatrix.Access;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One entry gate: page admission, then the action, then the (untouched) data scope.
 *
 * <p>r18 keeps the single-interceptor design of r17 and replaces only the oracle: it asks
 * {@link PermissionService} instead of re-deriving {@code PermissionMatrix} per call site. The 403
 * contract is unchanged — {@code FORBIDDEN} / "无权执行该操作" — and so is the audit action
 * {@code PERMISSION_DENIED}; the {@code detail} now names the page and the action, which is what the
 * r18 UI shows ("你在「缺陷」页没有「指派」动作").</p>
 *
 * <p>Resolution order for what a request needs:</p>
 * <ol>
 *   <li>{@link RequiresAction} on the handler (an exact action code);</li>
 *   <li>{@link RequiresActionFromBody} (the action code travels in the JSON body);</li>
 *   <li>{@link PermissionRouteTable} (page list + optional action, body-prefixed actions);</li>
 *   <li>{@link RequiresPermission} — kept for handlers that still name legacy function codes.</li>
 * </ol>
 *
 * <p>Page admission is any-of: one URL serves several pages, and opening any of them is enough.
 * The per-request memo is cleared in {@link #afterCompletion} so a long-lived thread never reuses
 * another tenant's answer.</p>
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private final AuditService auditService;
    private final PermissionService permissions;
    private final ObjectMapper objectMapper;

    public PermissionInterceptor(AuditService auditService, PermissionService permissions,
                                 ObjectMapper objectMapper) {
        this.auditService = auditService;
        this.permissions = permissions;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        // 先丢上一次请求留下的判定结果：被拒绝的请求是抛异常结束的，DispatcherServlet 的
        // triggerAfterCompletion 未必回落到本拦截器，afterCompletion 里的清理就可能不执行；
        // 而 Tomcat 线程是复用的，一份残留的 Effective 会让下一个请求（不同用户/角色）读到
        // 上一个请求的答案——线上表现为「某些人莫名其妙 403，重启就好」。
        permissions.clearRequestMemo();
        String path = AuthFilter.path(request);
        if (isOpen(path)) {
            return true;
        }
        if (handler instanceof HandlerMethod method && pageOnly(method)) {
            return true;
        }
        Declared declared = declared(methodOrNull(handler), request, path);
        if (declared == null) {
            return true;
        }
        if (declared.granted()) {
            return true;
        }
        deny(request, path, declared);
        throw new ApiException(403, "FORBIDDEN", "无权执行该操作", declared.detail(
                request.getMethod(), path, TenantContext.roles()));
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        permissions.clearRequestMemo();
    }

    // ---------------------------------------------------------------- resolution

    /** One resolved requirement, already evaluated against the current caller. */
    private record Declared(List<String> pages, String action, String bodyPrefix,
                            List<String> legacyFunctions, boolean legacyAnyOf, boolean granted) {

        Map<String, Object> detail(String method, String path, List<String> roles) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("path", path);
            detail.put("method", method);
            if (!pages.isEmpty()) {
                detail.put("page", pages.get(0));
            }
            detail.put("pages", pages);
            if (action != null) {
                detail.put("action", action);
            }
            if (!legacyFunctions.isEmpty()) {
                detail.put("functions", legacyFunctions);
            }
            detail.put("roles", roles);
            detail.put("access", "DENY");
            return detail;
        }
    }

    private Declared declared(HandlerMethod handler, HttpServletRequest request, String path) {
        PermissionService permissionService = permissions;
        if (handler != null) {
            RequiresAction exact = handler.getMethodAnnotation(RequiresAction.class);
            if (exact == null) {
                exact = handler.getBeanType().getAnnotation(RequiresAction.class);
            }
            if (exact != null) {
                String page = pageOfAction(exact.value());
                return evaluate(List.of(page), exact.value(), null, List.of(), false,
                        permissionService, request);
            }
            RequiresActionFromBody fromBody = handler.getMethodAnnotation(
                    RequiresActionFromBody.class);
            if (fromBody != null) {
                String prefix = fromBody.prefix().isBlank()
                        ? modulePrefix(fromBody.module())
                        : fromBody.prefix();
                return evaluate(List.of(fromBody.module()), null, prefix, List.of(), false,
                        permissionService, request);
            }
            RequiresPermission legacy = handler.getMethodAnnotation(RequiresPermission.class);
            if (legacy == null) {
                legacy = handler.getBeanType().getAnnotation(RequiresPermission.class);
            }
            if (legacy != null && legacy.value().length > 0) {
                List<String> codes = List.of(legacy.value());
                boolean granted = legacy.anyOf()
                        ? codes.stream().anyMatch(permissionService::canFunction)
                        : codes.stream().allMatch(permissionService::canFunction);
                return new Declared(List.of(), null, null, codes, legacy.anyOf(), granted);
            }
        }
        PermissionRouteTable.Requirement requirement =
                PermissionRouteTable.requirementFor(request.getMethod(), path);
        if (requirement == null) {
            return null;
        }
        return evaluate(requirement.pages(), requirement.action(), requirement.bodyPrefix(),
                List.of(), false, permissionService, request);
    }

    /**
     * Evaluate a requirement. When the action comes from the body the caller must be able to read
     * it; an unreadable or unknown action fails closed instead of falling back to page admission.
     */
    private Declared evaluate(List<String> pages, String action, String bodyPrefix,
                              List<String> legacyFunctions, boolean legacyAnyOf,
                              PermissionService permissionService, HttpServletRequest request) {
        String resolvedAction = action;
        if (bodyPrefix != null && !bodyPrefix.isBlank()) {
            String verb = bodyField(request, "action");
            resolvedAction = verb == null || verb.isBlank() ? null : bodyPrefix + verb;
            if (resolvedAction != null) {
                // 状态机用动词命名一次流转（缺陷的 start / submit_verify / verify_pass 等），
                // 可授权的动作却是它的能力名（handle / resolve / verify）。先归一化再判注册，
                // 否则平台管理员会被自己的状态机动词判 403；未登记且无别名的动词仍然 fail-closed。
                resolvedAction = ActionRegistry.canonical(resolvedAction);
                if (!ActionRegistry.isRegistered(resolvedAction)) {
                    // Unregistered verb: not grantable by construction, so deny rather than guess.
                    return new Declared(pages, resolvedAction, bodyPrefix, legacyFunctions,
                            legacyAnyOf, false);
                }
            }
        }
        boolean granted = false;
        if (!pages.isEmpty()) {
            for (String page : pages) {
                Access access = permissionService.decide(page, resolvedAction);
                if (access != Access.DENY) {
                    granted = true;
                    break;
                }
            }
        } else if (resolvedAction != null) {
            granted = permissionService.canAction(resolvedAction);
        }
        return new Declared(pages, resolvedAction, bodyPrefix, legacyFunctions, legacyAnyOf,
                granted);
    }

    private String bodyField(HttpServletRequest request, String field) {
        if (request == null) {
            return null;
        }
        if (request instanceof RequestBodyCacheFilter.CachedBodyRequest cached) {
            return read(cached.bodyText(), field);
        }
        return null;
    }

    /** Parse the cached JSON body; a malformed body yields {@code null} (fail closed). */
    private String read(String json, String field) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode value = node.get(field);
            return value == null || value.isNull() ? null : value.asString();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String pageOfAction(String actionCode) {
        return ActionRegistry.action(actionCode).map(ActionRegistry.ActionDef::menuCode).orElse(null);
    }

    /** {@code req.action.} for module {@code req.pool}. */
    private static String modulePrefix(String moduleCode) {
        if (moduleCode == null || moduleCode.isBlank()) {
            return "";
        }
        int dot = moduleCode.indexOf('.');
        String module = dot < 0 ? moduleCode : moduleCode.substring(0, dot);
        return module + ".action.";
    }

    private static boolean pageOnly(HandlerMethod method) {
        PageOnly annotation = method.getMethodAnnotation(PageOnly.class);
        return annotation != null;
    }

    private static HandlerMethod methodOrNull(Object handler) {
        return handler instanceof HandlerMethod method ? method : null;
    }

    private void deny(HttpServletRequest request, String path, Declared declared) {
        Map<String, Object> detail = declared.detail(request.getMethod(), path,
                TenantContext.roles());
        auditService.recordInNewTx("PERMISSION_DENIED", "permission", null, detail);
    }

    /**
     * The single list of paths that take part in no RBAC decision at all (login, health, internal
     * callbacks, the open test-execution API). The startup declaration check reads the same method,
     * so "public endpoint" can never mean one thing to the interceptor and another to the check.
     */
    public static boolean isOpen(String path) {
        if (path == null) {
            return true;
        }
        if (path.equals("/auth/login") || path.equals("/auth/me") || path.equals("/health")
                || path.equals("/error") || path.equals("/favicon.ico")
                || path.startsWith("/auth/me/")) {
            return true;
        }
        if (path.startsWith("/dictionaries") || path.startsWith("/v1/test-executions")
                || path.startsWith("/integration/webhooks")) {
            return true;
        }
        return path.startsWith("/internal/") || path.startsWith("/actuator/")
                || path.startsWith("/openapi/");
    }

    /** Roles that hold at least one of the codes; used by tests to prove the table is complete. */
    public static Set<String> rolesWith(String function) {
        return Set.copyOf(PermissionMatrix.roles().stream()
                .filter(role -> PermissionMatrix.accessFor(role, function) != Access.DENY)
                .toList());
    }
}
