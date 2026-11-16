package com.example.platform.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that the required action code comes from the request body (r18).
 *
 * <p>One URL carries many actions: {@code POST /requirements/{id}/actions} submits, approves,
 * rejects, accepts, closes a requirement, and each of those is a different grant. The path cannot
 * express that, so the action code is read from the body field {@link #field()} and prefixed with
 * {@link #prefix()} ({@code req.action.} + {@code approve}).</p>
 *
 * <p>The body is cached by {@link RequestBodyCacheFilter} so the interceptor can read it and the
 * controller still receives it through {@code @RequestBody}.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresActionFromBody {

    /** Page code that owns the actions ({@code ActionRegistry} menu code). */
    String module();

    /** Body field holding the action verb; defaults to {@code action}. */
    String field() default "action";

    /** Prefix prepended to the body value to form the action code. */
    String prefix() default "";
}
