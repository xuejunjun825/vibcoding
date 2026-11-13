package com.example.platform.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the action code a write handler requires.
 *
 * <p>An annotation beats the path table: it is the only way to say "this URL performs a different
 * action than its sibling" (e.g. {@code PATCH /tasks/{id}} updates a task while
 * {@code POST /tasks/{id}/move} reassigns it). The declaration is verified at startup by
 * {@link PermissionDeclarationValidator}, so a typo fails the boot instead of returning 403 in
 * production.</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresAction {

    /** Registered action code from {@code ActionRegistry}. */
    String value();
}
