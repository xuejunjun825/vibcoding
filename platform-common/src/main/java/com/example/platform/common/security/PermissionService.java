package com.example.platform.common.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.ActionRegistry;
import com.example.platform.contract.MenuRegistry;
import com.example.platform.contract.PermissionMatrix;
import com.example.platform.contract.PermissionMatrix.Access;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The single authorization decision point (r18) — three layers, one merge rule, one cache.
 *
 * <p>Before r18 the decision was {@code PermissionMatrix.accessOf(function, roles)} evaluated at
 * each call site: the frontend, the interceptor and {@code TenantContext.can} each re-derived it,
 * and the matrix was compile-time. r18 makes the grants data (a tenant can narrow a built-in role),
 * so the derivation has to live in exactly one class or the button and the 403 start to disagree.</p>
 *
 * <p>Three layers, decided in this order by {@link #decide(String, String)}:</p>
 * <ol>
 *   <li><b>MENU</b> — the page must not be DENY (a page-level deny hides the entry and refuses the
 *       call even when the action is granted);</li>
 *   <li><b>ACTION</b> — the action must not be DENY; RESTRICTED passes (it means "allowed, then
 *       narrowed");</li>
 *   <li><b>DataScope</b> — untouched. The range narrowing stays in {@code ScopeSql}/{@code ScopeGuard};
 *       this class never decides "which rows", only "which verbs" (r18 AC-7).</li>
 * </ol>
 *
 * <p>Merge rule across the caller's roles: <b>no row ⇒ DENY; any explicit DENY ⇒ DENY; otherwise the
 * highest access wins</b>. The explicit-DENY rule is what stops "give the user one more role" from
 * widening access: every built-in role is seeded with explicit DENY rows for what it must not do.</p>
 *
 * <p>Cache: one immutable snapshot per tenant, rebuilt on first use, when {@code epoch} changed or
 * after the TTL (default 45s). A revocation calls {@link #invalidate(long)}, which bumps the epoch
 * after commit, so the next request already sees it — the AC-5 "撤销在第 1 次请求即生效" claim.
 * The access levels themselves are NOT redeclared here: {@link Access} stays the only set, and
 * {@link Access#max} the only ordering.</p>
 */
@Service
public class PermissionService {

    /** The three grant layers, in decision order. */
    public enum Layer {
        FUNCTION,
        MENU,
        ACTION
    }

    /**
     * Immutable per-tenant grant snapshot: {@code byRole} is layer → role code → subject → access.
     *
     * <p>Layer is the outer key on purpose: the same string could be a page code and a function
     * code in principle, and merging them would make one denial silently hide an unrelated grant.</p>
     */
    public record Snapshot(long tenantId, long epoch, long loadedAtMillis,
                           Map<Layer, Map<String, Map<String, Access>>> byRole) {
    }

    /** The caller's effective access per layer, merged over every role it holds. */
    public record Effective(long epoch, Map<String, Access> functions, Map<String, Access> menus,
                            Map<String, Access> actions) {
    }

    private final PermissionGrantsRepository repository;
    private final long ttlMillis;
    private final Map<Long, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<Long, Long> epochs = new ConcurrentHashMap<>();
    private final ThreadLocal<Effective> memo = new ThreadLocal<>();

    public PermissionService(PermissionGrantsRepository repository,
                             @Value("${platform.rbac.cache-ttl-seconds:45}") long ttlSeconds) {
        this.repository = repository;
        this.ttlMillis = Math.max(1, ttlSeconds) * 1000L;
    }

    // ---------------------------------------------------------------- decision

    /**
     * Page-then-action decision. A null/blank action means "page only" (the page admission alone).
     * Returns DENY / RESTRICTED / ALLOW; RESTRICTED is a pass for callers that only want a boolean.
     */
    public Access decide(String menuCode, String actionCode) {
        Access menu = menuCode == null || menuCode.isBlank()
                ? Access.ALLOW
                : accessOf(Layer.MENU, menuCode);
        if (menu == Access.DENY) {
            return Access.DENY;
        }
        if (actionCode == null || actionCode.isBlank()) {
            return menu;
        }
        Access action = accessOf(Layer.ACTION, actionCode);
        if (action == Access.DENY) {
            return Access.DENY;
        }
        // The weaker of the two still lets the call through; the value is carried to the audit so
        // "granted but narrowed" stays distinguishable from "fully granted".
        return Access.max(menu, action);
    }

    public Access accessOf(Layer layer, String code) {
        if (code == null || code.isBlank()) {
            return Access.DENY;
        }
        Map<String, Access> merged = layer(effective(), layer);
        return merged.getOrDefault(code, Access.DENY);
    }

    public boolean canFunction(String code) {
        return accessOf(Layer.FUNCTION, code) != Access.DENY;
    }

    public boolean canMenu(String menuCode) {
        return accessOf(Layer.MENU, menuCode) != Access.DENY;
    }

    public boolean canAction(String actionCode) {
        return accessOf(Layer.ACTION, actionCode) != Access.DENY;
    }

    /**
     * Whether the current caller may perform {@code actionCode} on {@code menuCode}. Kept as a
     * convenience for the interceptor so the two-step order is never re-implemented there.
     */
    public boolean allows(String menuCode, String actionCode) {
        return decide(menuCode, actionCode) != Access.DENY;
    }

    /** Granted subject codes of one layer, in registry order (stable for the frontend). */
    public List<String> granted(Layer layer) {
        Map<String, Access> merged = layer(effective(), layer);
        List<String> result = new ArrayList<>();
        for (String code : universe(layer)) {
            if (merged.getOrDefault(code, Access.DENY) != Access.DENY) {
                result.add(code);
            }
        }
        return List.copyOf(result);
    }

    /** RESTRICTED subject codes of one layer (the "granted within your data scope" set). */
    public List<String> restricted(Layer layer) {
        Map<String, Access> merged = layer(effective(), layer);
        List<String> result = new ArrayList<>();
        for (String code : universe(layer)) {
            if (merged.get(code) == Access.RESTRICTED) {
                result.add(code);
            }
        }
        return List.copyOf(result);
    }

    public Effective effective() {
        Effective cached = memo.get();
        if (cached != null) {
            return cached;
        }
        Snapshot snapshot = snapshot();
        List<String> roles = TenantContext.roles();
        Effective computed = new Effective(snapshot.epoch(),
                merge(rows(snapshot, Layer.FUNCTION, roles)),
                merge(rows(snapshot, Layer.MENU, roles)),
                merge(rows(snapshot, Layer.ACTION, roles)));
        memo.set(computed);
        return computed;
    }

    public long epoch() {
        long tenantId = tenantIdOrZero();
        if (tenantId <= 0) {
            return 0L;
        }
        return epochs.getOrDefault(tenantId, 0L);
    }

    /**
     * Drop the tenant snapshot so the next request re-reads the grants. Called after a grant save,
     * a role status change or a reset. When a transaction is active the epoch is bumped only on
     * commit: invalidating inside a transaction would let a concurrent request cache the rows
     * before they were committed and then keep them for a whole TTL.
     */
    public void invalidate(long tenantId) {
        if (tenantId <= 0) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            drop(tenantId);
                        }
                    });
            return;
        }
        drop(tenantId);
    }

    /** Forget the caller's per-request memo; the servlet interceptor calls this when done. */
    public void clearRequestMemo() {
        memo.remove();
    }

    // ---------------------------------------------------------------- internals

    private void drop(long tenantId) {
        epochs.merge(tenantId, 1L, Long::sum);
        snapshots.remove(tenantId);
        memo.remove();
    }

    private Snapshot snapshot() {
        long tenantId = tenantIdOrZero();
        if (tenantId <= 0) {
            return emptySnapshot(0);
        }
        Snapshot cached = snapshots.get(tenantId);
        long epoch = epochs.getOrDefault(tenantId, 0L);
        if (cached != null && cached.epoch() == epoch
                && System.currentTimeMillis() - cached.loadedAtMillis() <= ttlMillis) {
            return cached;
        }
        Snapshot loaded = repository.load(tenantId, epoch);
        snapshots.put(tenantId, loaded);
        return loaded;
    }

    private static Snapshot emptySnapshot(long epoch) {
        return new Snapshot(0L, epoch, System.currentTimeMillis(), Map.of(
                Layer.FUNCTION, Map.of(), Layer.MENU, Map.of(), Layer.ACTION, Map.of()));
    }

    private long tenantIdOrZero() {
        try {
            return TenantContext.require().tenantId();
        } catch (RuntimeException ex) {
            // No request scope (startup seeding, scripts): treat as "nothing granted".
            return 0L;
        }
    }

    private static List<Map<String, Access>> rows(Snapshot snapshot, Layer layer,
                                                  Collection<String> roles) {
        Map<String, Map<String, Access>> byRole = snapshot.byRole().getOrDefault(layer, Map.of());
        List<Map<String, Access>> result = new ArrayList<>();
        for (String role : roles) {
            Map<String, Access> row = byRole.get(role);
            if (row != null && !row.isEmpty()) {
                result.add(row);
            }
        }
        return result;
    }

    /**
     * Merge rule: a subject absent from every role row is DENY (the map simply lacks it); an
     * explicit DENY in any role wins; otherwise the highest access wins.
     */
    public static Map<String, Access> merge(List<Map<String, Access>> rows) {
        Map<String, Access> merged = new LinkedHashMap<>();
        for (Map<String, Access> row : rows) {
            for (Map.Entry<String, Access> entry : row.entrySet()) {
                merged.merge(entry.getKey(), entry.getValue(), PermissionService::union);
            }
        }
        return Map.copyOf(merged);
    }

    /** Any explicit DENY wins; otherwise the weaker (higher-ordinal) access is kept. */
    public static Access union(Access left, Access right) {
        if (left == Access.DENY || right == Access.DENY) {
            return Access.DENY;
        }
        return Access.max(left, right);
    }

    private static Map<String, Access> layer(Effective effective, Layer layer) {
        return switch (layer) {
            case FUNCTION -> effective.functions();
            case MENU -> effective.menus();
            case ACTION -> effective.actions();
        };
    }

    /** The registered subject codes of a layer, in the order the admin and frontend show them. */
    private static List<String> universe(Layer layer) {
        return switch (layer) {
            case FUNCTION -> PermissionMatrix.functions();
            case MENU -> MenuRegistry.codes();
            case ACTION -> ActionRegistry.codes();
        };
    }
}
