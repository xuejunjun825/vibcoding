package com.example.platform.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a write handler is intentionally protected by its page only — no action code.
 *
 * <p>Without this, "I forgot the action code" and "this endpoint genuinely has no separate verb"
 * look the same to the startup validator, and the safe default (fail the boot) would be wrong half
 * the time. A reason is required, and the fully qualified handler name is collected into
 * {@link PermissionDeclarationValidator#PAGE_ONLY_WHITELIST} so the exemption is auditable: the
 * gate script prints the list and a reviewer can tell whether it grew for a good reason.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PageOnly {

    /** Why this endpoint has no action code (shown in the startup log and the gate script). */
    String reason();
}
