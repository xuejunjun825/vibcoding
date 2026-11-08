package com.example.platform.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the function code(s) a handler needs.
 *
 * <p>Optional: handlers that are not annotated are resolved through
 * {@link PermissionRouteTable}, which keeps today's controllers untouched while still giving one
 * place where the path → function mapping lives.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresPermission {

    String[] value() default {};

    /** When true, any one of {@link #value()} grants access; otherwise all are required. */
    boolean anyOf() default false;
}
