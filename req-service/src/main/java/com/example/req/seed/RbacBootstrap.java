package com.example.req.seed;

import java.util.ArrayList;
import java.util.List;

import com.example.req.sys.SysDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tops up the permission data of every existing tenant once the context is up.
 *
 * <p>An upgrade is the awkward case: V12 creates the tables but writes no rows, and tenants that
 * were provisioned by an older version have nothing in {@code sys_role_permission}. Without this
 * runner they would boot into a grid of "no rows", which the resolver reads as {@code DENY} —
 * every user of every pre-existing tenant would lose access after the deploy. So the runner seeds
 * the global registries and then walks {@code sys_tenant}, seeding each tenant.</p>
 *
 * <p>It is an {@link ApplicationRunner} rather than part of the migration because the rows are
 * derived from Java constants; SQL cannot compute them without duplicating the formula. Each tenant
 * gets its own transaction so one bad tenant cannot roll back the others, and the two registries
 * commit before any tenant is touched. Failures are not swallowed: a tenant that cannot be seeded
 * is a broken deployment, and booting anyway would only move the failure to the first login.</p>
 */
@Component
public class RbacBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RbacBootstrap.class);

    private final SysDb db;
    private final RbacSeeder seeder;
    private final TransactionTemplate transactions;

    public RbacBootstrap(SysDb db, RbacSeeder seeder, PlatformTransactionManager transactionManager) {
        this.db = db;
        this.seeder = seeder;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        seeder.seedRegistries();
        int tenants = seedAllTenants();
        log.info("rbac bootstrap: registries ready, {} tenant(s) seeded", tenants);
    }

    /**
     * Seeds every tenant in the database, one transaction each.
     *
     * @return number of tenants visited
     */
    public int seedAllTenants() {
        List<Long> tenantIds = new ArrayList<>();
        for (Object value : db.scalars("SELECT id FROM sys_tenant ORDER BY id")) {
            tenantIds.add(SysDb.asLong(value));
        }
        for (long tenantId : tenantIds) {
            transactions.executeWithoutResult(status -> seeder.seedTenant(tenantId));
        }
        return tenantIds.size();
    }
}
