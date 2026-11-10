package com.example.req.sys;

import java.util.ArrayList;
import java.util.List;

import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only option dictionaries for every signed-in account.
 *
 * <p>Why this surface exists at all: the non-admin pages (requirement pool, release plan, approval
 * centre, test plans, code policy) need domain / vendor / user options and used to borrow them from
 * {@code /admin/*}, which is guarded by {@code org:manage}. A pm or dev opening such a page got a
 * 403 from the picker and the whole page fell over. Reading options is not administering the org,
 * so the enumerations live here, authenticated only and deliberately <b>absent</b> from
 * {@code PermissionRouteTable} — {@code PermissionRouteTableTest} pins that "no rule" contract so a
 * well-meaning future rule cannot silently undo the fix.</p>
 *
 * <p>Tenant and vendor boundaries come from {@link DataScope}, never from a request parameter: the
 * tenant is the JWT's, and a vendor account ({@code vendorId != null}) only sees its own vendor,
 * its own people and the domains actually granted to it in {@code sys_user_domain}. An account
 * with no granted domain gets an empty list, not an error.</p>
 */
@RestController
@RequestMapping("/dictionaries")
@Transactional(readOnly = true)
public class DictionaryController {

    private static final Logger log = LoggerFactory.getLogger(DictionaryController.class);

    /**
     * Contract upper bound for the user picker (a dropdown, not a report). When a tenant reaches
     * it the list is truncated by id and a warning is logged instead of paginating silently.
     */
    static final int USER_LIMIT = 1000;

    private final SysDb db;

    public DictionaryController(SysDb db) {
        this.db = db;
    }

    /** Enabled domains of the caller's tenant, narrowed to the granted ones for a vendor account. */
    @GetMapping("/domains")
    public List<DictionaryViews.DomainOption> domains() {
        DataScope scope = TenantContext.require();
        List<Object[]> rows;
        if (scope.vendorId() == null) {
            rows = db.rows("SELECT d.id, d.code, d.name FROM biz_domain d"
                    + " WHERE d.tenant_id = ? AND d.enabled = 1 ORDER BY d.id", scope.tenantId());
        } else {
            // Granted domains ∩ enabled domains. The grant table is the fact; scope.domainIds()
            // is a derived value, so the join keeps "only my domains" readable in one place.
            rows = db.rows("SELECT d.id, d.code, d.name FROM biz_domain d"
                    + " JOIN sys_user_domain ud ON ud.domain_id = d.id"
                    + " AND ud.user_id = ? AND ud.tenant_id = d.tenant_id"
                    + " WHERE d.tenant_id = ? AND d.enabled = 1 ORDER BY d.id",
                    scope.userId(), scope.tenantId());
        }
        List<DictionaryViews.DomainOption> options = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            options.add(new DictionaryViews.DomainOption(SysDb.asLong(row[0]),
                    SysDb.asString(row[1]), SysDb.asString(row[2])));
        }
        return options;
    }

    /** Active vendors of the tenant; a vendor account only ever sees itself. */
    @GetMapping("/vendors")
    public List<DictionaryViews.VendorOption> vendors() {
        DataScope scope = TenantContext.require();
        List<Object[]> rows;
        if (scope.vendorId() == null) {
            rows = db.rows("SELECT id, code, name FROM sys_vendor"
                    + " WHERE tenant_id = ? AND status = 'ACTIVE' ORDER BY id", scope.tenantId());
        } else {
            rows = db.rows("SELECT id, code, name FROM sys_vendor"
                            + " WHERE tenant_id = ? AND status = 'ACTIVE' AND id = ? ORDER BY id",
                    scope.tenantId(), scope.vendorId());
        }
        List<DictionaryViews.VendorOption> options = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            options.add(new DictionaryViews.VendorOption(SysDb.asLong(row[0]),
                    SysDb.asString(row[1]), SysDb.asString(row[2])));
        }
        return options;
    }

    /** Active accounts of the tenant, by id, capped at {@link #USER_LIMIT}. */
    @GetMapping("/users")
    public List<DictionaryViews.UserOption> users() {
        DataScope scope = TenantContext.require();
        List<Object[]> rows;
        if (scope.vendorId() == null) {
            rows = db.rows("SELECT id, username, name FROM sys_user"
                            + " WHERE tenant_id = ? AND status = 'ACTIVE' ORDER BY id LIMIT "
                            + USER_LIMIT,
                    scope.tenantId());
        } else {
            rows = db.rows("SELECT id, username, name FROM sys_user"
                            + " WHERE tenant_id = ? AND status = 'ACTIVE' AND vendor_id = ?"
                            + " ORDER BY id LIMIT " + USER_LIMIT,
                    scope.tenantId(), scope.vendorId());
        }
        if (rows.size() >= USER_LIMIT) {
            log.warn("user dictionary hit the {} row cap for tenant {}: options are truncated by id",
                    USER_LIMIT, scope.tenantId());
        }
        List<DictionaryViews.UserOption> options = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            options.add(new DictionaryViews.UserOption(SysDb.asLong(row[0]),
                    SysDb.asString(row[1]), SysDb.asString(row[2])));
        }
        return options;
    }
}
