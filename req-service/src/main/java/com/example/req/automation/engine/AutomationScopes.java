package com.example.req.automation.engine;

import java.util.function.Supplier;

import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;

/**
 * Binds a rule's data scope onto the current thread for the duration of the work.
 *
 * <p>{@code ScopedJdbc} reads {@link TenantContext} at execution time, so "the rule's scope" can
 * only ever be a thread binding. This is the single place that binds it (and the single place that
 * restores it): the worker thread has no request context, and a leaked binding would make the next
 * event run under the previous rule's scope.</p>
 */
public final class AutomationScopes {

    private AutomationScopes() {
    }

    public static <T> T withScope(DataScope scope, Supplier<T> work) {
        DataScope previous = TenantContext.scopeOrNull();
        TenantContext.Principal previousPrincipal = TenantContext.isSet()
                ? TenantContext.principal()
                : null;
        TenantContext.set(scope);
        // The automation thread is not a request: nobody is "the caller". Audit rows still need the
        // tenant, which comes from the scope above, so dropping the principal is safe and avoids
        // attributing a rule's writes to the user who happened to trigger the event.
        TenantContext.setPrincipal(null);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
                if (previousPrincipal != null) {
                    TenantContext.setPrincipal(previousPrincipal);
                }
            }
        }
    }
}
