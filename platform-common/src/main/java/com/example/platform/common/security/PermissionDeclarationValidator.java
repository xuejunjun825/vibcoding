package com.example.platform.common.security;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.example.platform.contract.ActionRegistry;
import com.example.platform.contract.MenuRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Startup gate: every write handler must name a page, and every code it names must be registered.
 *
 * <p>The failure this prevents is not "a request was refused" but "a request was never checked":
 * an endpoint added without a declaration silently runs for anyone with a token. Discovering that
 * at boot (and refusing to start) costs one build cycle; discovering it in production costs an
 * incident. The report lists the offending handlers in one message, so a fix is a single edit
 * round rather than one restart per endpoint.</p>
 *
 * <p>Checks, per write handler:</p>
 * <ul>
 *   <li>a requirement exists (annotation or route table) and names at least one page;</li>
 *   <li>every page code is in {@link MenuRegistry};</li>
 *   <li>a fixed action code is in {@link ActionRegistry} and belongs to one of the pages;</li>
 *   <li>a body-supplied action has a non-blank prefix and its page is registered;</li>
 *   <li>a handler with only page admission is recorded in {@link #PAGE_ONLY_WHITELIST} — that set
 *       is printed on startup and asserted by the gate script, so "no action" stays a decision
 *       rather than an omission.</li>
 * </ul>
 *
 * <p>{@code platform.rbac.fail-fast=false} downgrades the failure to a warning (for an environment
 * that must boot to be repaired); the default is {@code true}.</p>
 */
@Component
public class PermissionDeclarationValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PermissionDeclarationValidator.class);

    /** Handlers that are intentionally protected by page admission only (audited). */
    public static final Set<String> PAGE_ONLY_WHITELIST = ConcurrentHashMap.newKeySet();

    private static final Set<String> WRITE_METHODS =
            Set.of("POST", "PUT", "PATCH", "DELETE");

    private final RequestMappingHandlerMapping mappings;
    private final boolean failFast;

    public PermissionDeclarationValidator(RequestMappingHandlerMapping mappings,
                                          @Value("${platform.rbac.fail-fast:true}") boolean failFast) {
        this.mappings = mappings;
        this.failFast = failFast;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> violations = new ArrayList<>();
        int checked = 0;
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            HandlerMethod handler = entry.getValue();
            for (String method : info.getMethodsCondition().getMethods().stream()
                    .map(item -> item.name().toUpperCase(Locale.ROOT)).toList()) {
                if (!WRITE_METHODS.contains(method)) {
                    continue;
                }
                for (String pattern : info.getPatternValues()) {
                    checked++;
                    String problem = check(method, pattern, handler);
                    if (problem != null) {
                        violations.add(problem);
                        break;
                    }
                }
            }
        }
        if (violations.isEmpty()) {
            log.info("RBAC declaration check: {} write endpoints declared, {} page-only exemptions",
                    checked, PAGE_ONLY_WHITELIST.size());
            return;
        }
        String message = "RBAC declaration check failed for " + violations.size()
                + " write endpoint(s):\n  " + String.join("\n  ",
                violations.subList(0, Math.min(20, violations.size())));
        if (failFast) {
            throw new IllegalStateException(message);
        }
        log.error(message);
    }

    /** Returns {@code null} when the handler is properly declared, else the reason. */
    private String check(String method, String pattern, HandlerMethod handler) {
        String label = method + " " + pattern + " (" + handler.getShortLogMessage() + ")";
        String syntheticPath = pattern.replaceAll("\\{[^/]+}", "1");

        RequiresAction exact = handler.getMethodAnnotation(RequiresAction.class);
        if (exact == null) {
            exact = handler.getBeanType().getAnnotation(RequiresAction.class);
        }
        RequiresActionFromBody fromBody = handler.getMethodAnnotation(
                RequiresActionFromBody.class);
        PageOnly pageOnly = handler.getMethodAnnotation(PageOnly.class);

        if (exact != null) {
            if (!ActionRegistry.isRegistered(exact.value())) {
                return label + " → 未注册的动作码 " + exact.value();
            }
            String page = ActionRegistry.require(exact.value()).menuCode();
            if (!MenuRegistry.isRegistered(page)) {
                return label + " → 动作 " + exact.value() + " 所属页面 " + page + " 未注册";
            }
            return null;
        }
        if (fromBody != null) {
            if (!MenuRegistry.isRegistered(fromBody.module())) {
                return label + " → 页面 " + fromBody.module() + " 未注册";
            }
            if (fromBody.prefix().isBlank() && fromBody.module().isBlank()) {
                return label + " → 请求体动作码缺少前缀";
            }
            return null;
        }

        PermissionRouteTable.Requirement requirement =
                PermissionRouteTable.requirementFor(method, syntheticPath);
        if (requirement == null) {
            return label + " → 写端点未声明（PermissionRouteTable 无规则，且无动作注解）";
        }
        if (requirement.pages().isEmpty()) {
            return label + " → 未声明页面码";
        }
        for (String page : new LinkedHashSet<>(requirement.pages())) {
            if (!MenuRegistry.isRegistered(page)) {
                return label + " → 页面码 " + page + " 未注册";
            }
        }
        if (requirement.fromBody()) {
            String prefix = requirement.bodyPrefix();
            if (prefix == null || prefix.isBlank()) {
                return label + " → 请求体动作码缺少前缀";
            }
            boolean known = ActionRegistry.codes().stream().anyMatch(code -> code.startsWith(prefix));
            if (!known) {
                return label + " → 请求体动作前缀 " + prefix + " 不对应任何动作码";
            }
            return null;
        }
        if (requirement.action() == null) {
            if (pageOnly == null && !PermissionRouteTable.pageOnlyPatterns().isEmpty()) {
                PAGE_ONLY_WHITELIST.add(method + " " + pattern);
            }
            return null;
        }
        if (!ActionRegistry.isRegistered(requirement.action())) {
            return label + " → 未注册的动作码 " + requirement.action();
        }
        String owner = ActionRegistry.require(requirement.action()).menuCode();
        if (!requirement.pages().contains(owner)) {
            return label + " → 动作 " + requirement.action() + " 属于页面 " + owner
                    + "，与声明的页面 " + requirement.pages() + " 不一致";
        }
        return null;
    }
}
