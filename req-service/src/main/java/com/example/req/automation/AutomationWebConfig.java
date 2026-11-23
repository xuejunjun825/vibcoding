package com.example.req.automation;

import com.example.req.automation.api.AutomationInternalOnlyInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 只做一件事：把厂商闸门挂在 {@code /automation/**} 上，并排在权限校验之前。
 *
 * <p>{@code order(-100)} 是语义要求而不是调优：{@code PermissionWebConfig} 注册的
 * {@code PermissionInterceptor} 用默认 order 0，若本拦截器在后面，
 * 厂商账号会先拿到 {@code FORBIDDEN}，AC-3 要求的 {@code INTERNAL_ONLY} 就永远出不来。</p>
 */
@Configuration
public class AutomationWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AutomationInternalOnlyInterceptor())
                .addPathPatterns("/automation/**")
                .order(-100);
    }
}
