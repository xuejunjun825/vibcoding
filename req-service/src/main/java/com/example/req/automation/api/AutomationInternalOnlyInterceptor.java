package com.example.req.automation.api;

import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 厂商账号访问自动化规则一律 403 {@code INTERNAL_ONLY}。
 *
 * <p>为什么要在服务端再加一道：前端 nav 隐藏与路由守卫只挡正常人，挡不住直接打接口的请求；
 * 而 {@code PermissionInterceptor} 会先把厂商判成 {@code FORBIDDEN}，错误码就不是 AC-3 要的那个。
 * 因此本拦截器必须以 {@code order(-100)} 注册在它之前（见 {@code AutomationWebConfig}）。</p>
 */
public class AutomationInternalOnlyInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!TenantContext.isSet()) {
            // 未认证请求交给后面的权限链处理，这里不抢它的 401/403 语义。
            return true;
        }
        TenantContext.Principal principal = TenantContext.principal();
        if (principal != null && principal.vendorId() != null) {
            throw new ApiException(403, "INTERNAL_ONLY", "仅内部账号可访问自动化规则");
        }
        return true;
    }
}
