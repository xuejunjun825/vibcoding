package com.example.req.seed;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.platform.contract.ActionRegistry;
import com.example.platform.contract.MenuRegistry;
import com.example.platform.contract.PermissionMatrix;
import com.example.req.sys.SysDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Derives the three permission layers from the registries and writes them into the database.
 *
 * <p>V12 creates the tables but inserts no rows, because the {@code 1848} rows per tenant are a
 * function of the code, not constants: {@code FUNCTION} comes from {@link PermissionMatrix},
 * {@code MENU} and {@code ACTION} from the function codes {@link MenuRegistry} and
 * {@link ActionRegistry} bind to each page / action (empty binding means {@code ALLOW}, which is
 * how the four pages with no function code stay visible). Writing the rows as SQL would force a
 * migration edit — and a Flyway checksum change — every time a page is added; here a new page is
 * picked up by the next seed run. This is the only implementation of that derivation: the tests
 * and {@code s2} recompute expectations independently and compare, so a second copy of the formula
 * cannot drift away from this one.</p>
 *
 * <p>{@link #preset(String)} is the single source of truth for "what the built-in roles look like
 * out of the box". All three write paths use it: {@code RbacBootstrap} on startup, {@code
 * TenantProvisioner} when a tenant is created, and {@code DemoResetService} when the demo data is
 * reset. Being {@code static} and side-effect free is what lets a unit test assert the 168-row
 * shape without a database.</p>
 *
 * <p>Writes are {@code INSERT IGNORE}, never {@code REPLACE}: an existing row always wins, so a
 * restart does not silently reset a grid an administrator has edited back to the preset. The
 * unique key {@code uk_rp} makes that idempotent, and it is also why a {@code DENY} is stored as a
 * row rather than expressed by the absence of one — otherwise "explicitly denied" and "this
 * version has never heard of the code" would be indistinguishable, and a preset flip would
 * resurrect permissions nobody granted.</p>
 */
@Component
public class RbacSeeder {

    private static final Logger log = LoggerFactory.getLogger(RbacSeeder.class);

    /** Row count per statement. 1848 rows × 5 params stays far below MySQL's placeholder limit. */
    private static final int CHUNK = 200;

    static final String FUNCTION = "FUNCTION";
    static final String MENU = "MENU";
    static final String ACTION = "ACTION";

    /**
     * 角色 × 动作的显式 DENY 覆盖：唯一一处「动作不只是功能码的函数」。
     *
     * <p>为什么在这里而不在 {@link PermissionMatrix}：那个类的契约是「角色 × 60 功能码」，运行时不读
     * 它；动作覆盖是角色×动作的第二个维度，塞进矩阵会把网格契约与 s2 的 max 公式一起改掉。放在种子
     * 派生层，三条写入口（启动自愈 / 建租户 / 演示重置）自动一致，且只有这一份清单。</p>
     *
     * <p>r24 的 11 条全部属于 VENDOR_PM：它靠 4 个页面准入键（{@code release:plan} /
     * {@code task:manage} / {@code case:manage} / {@code plan:manage}）才能看见版本、迭代、用例与
     * 测试计划页，而 max 派生会顺带把这些页上的 4+6+1 个写动作一起点亮。厂商项目经理要跟踪交付，
     * 不审批版本、不新建/指派/关闭任务与迭代、不改用例——所以逐条显式 DENY。</p>
     *
     * <p>r40 再补 2 条（11→13）：两个变更动作的 functionCodes 是 any-of，
     * {@code req.action.change_apply} 的 {@code req:deliver} 厂商项目经理本来就有（它是交付跟踪
     * 角色），只在功能码层派生会把它放行；而「谁能应用变更」是需求内容的写权限，厂商不能自己
     * 批准自己的变更。这里必须在预置层显式 DENY，路由表的动作规则才有意义。</p>
     */
    private static final Map<String, Set<String>> ACTION_DENY_OVERRIDES = Map.of(
            "VENDOR_PM", Set.of(
                    "release.action.create", "release.action.update",
                    "release.action.approve", "release.action.close",
                    "task.action.create", "task.action.assign",
                    "task.action.update", "task.action.close",
                    "sprint.action.create", "sprint.action.close",
                    "test.action.case_edit",
                    "req.action.change_request", "req.action.change_apply"),
            // r47 重复合并：req:review 的 ALLOW 集是 PLATFORM_ADMIN / PMO / PRODUCT /
            // SUB_BUSINESS_LEADER，而契约 §3.3 的准入只有前三个。域负责人持有 req:review 是为了
            // 评审，不该因此获得「把别人的需求合掉」的权限，所以逐动作显式 DENY（推导仍是唯一
            // 默认规则，覆盖只有这一格，测试里当作声明式白名单校验）。
            "SUB_BUSINESS_LEADER", Set.of("req.action.merge_duplicate"));

    private final SysDb db;

    public RbacSeeder(SysDb db) {
        this.db = db;
    }

    /**
     * Upserts the global registries ({@code sys_menu} 36 rows, {@code sys_action} 72 rows).
     *
     * <p>Upsert rather than insert-ignore: these tables mirror the code, so a renamed page or a
     * newly risky action must land on an existing row. They are global (no {@code tenant_id}) and
     * carry no administrator edits — the tenant-scoped grid in {@code sys_role_permission} is the
     * one that must never be overwritten.</p>
     */
    @Transactional
    public void seedRegistries() {
        List<MenuRegistry.MenuPage> pages = MenuRegistry.pages();
        for (int from = 0; from < pages.size(); from += CHUNK) {
            List<MenuRegistry.MenuPage> chunk = pages.subList(from, Math.min(from + CHUNK, pages.size()));
            StringBuilder sql = new StringBuilder("""
                    INSERT INTO sys_menu (code, module, name, route_name, function_codes, sort_no)
                    VALUES """);
            List<Object> params = new ArrayList<>(chunk.size() * 6);
            for (int i = 0; i < chunk.size(); i++) {
                MenuRegistry.MenuPage page = chunk.get(i);
                sql.append(i == 0 ? "" : ", ").append("(?, ?, ?, ?, ?, ?)");
                params.add(page.code());
                params.add(page.module());
                params.add(page.name());
                params.add(page.routeName());
                params.add(String.join(",", page.functionCodes()));
                params.add(page.sort());
            }
            sql.append("""
                     AS incoming
                    ON DUPLICATE KEY UPDATE module = incoming.module, name = incoming.name,
                        route_name = incoming.route_name, function_codes = incoming.function_codes,
                        sort_no = incoming.sort_no""");
            db.update(sql.toString(), params.toArray());
        }

        List<ActionRegistry.ActionDef> actions = ActionRegistry.actions();
        for (int from = 0; from < actions.size(); from += CHUNK) {
            List<ActionRegistry.ActionDef> chunk =
                    actions.subList(from, Math.min(from + CHUNK, actions.size()));
            StringBuilder sql = new StringBuilder("""
                    INSERT INTO sys_action (code, menu_code, name, function_codes, high_risk, sort_no)
                    VALUES """);
            List<Object> params = new ArrayList<>(chunk.size() * 6);
            for (int i = 0; i < chunk.size(); i++) {
                ActionRegistry.ActionDef action = chunk.get(i);
                sql.append(i == 0 ? "" : ", ").append("(?, ?, ?, ?, ?, ?)");
                params.add(action.code());
                params.add(action.menuCode());
                params.add(action.name());
                params.add(String.join(",", action.functionCodes()));
                params.add(action.highRisk() ? 1 : 0);
                params.add(action.sort());
            }
            sql.append("""
                     AS incoming
                    ON DUPLICATE KEY UPDATE menu_code = incoming.menu_code, name = incoming.name,
                        function_codes = incoming.function_codes,
                        high_risk = incoming.high_risk, sort_no = incoming.sort_no""");
            db.update(sql.toString(), params.toArray());
        }
        log.info("rbac seed: registries upserted ({} pages, {} actions)", pages.size(), actions.size());
    }

    /**
     * Writes the preset grid of every built-in role the tenant owns.
     *
     * <p>Roles are looked up by code inside the tenant, not assumed to be 10 contiguous ids: a
     * tenant can be created by this version (11 roles), upgraded from a version with fewer, or
     * carry extra custom roles — the seed only touches codes {@link PermissionMatrix#roles()} knows,
     * and only rows that already exist, so a preset never invents a role. Runs inside the caller's
     * transaction on purpose: provisioning a tenant and seeding its grid must commit together.</p>
     *
     * @return number of grant rows actually inserted (0 when the tenant was already complete)
     */
    @Transactional
    public int seedTenant(long tenantId) {
        Map<String, Long> roleIds = new LinkedHashMap<>();
        for (Object[] row : db.rows("SELECT code, id FROM sys_role WHERE tenant_id = ? ORDER BY id",
                tenantId)) {
            roleIds.put(SysDb.asString(row[0]), SysDb.asLong(row[1]));
        }
        if (roleIds.isEmpty()) {
            log.warn("rbac seed: tenant {} has no roles; skipped", tenantId);
            return 0;
        }
        int inserted = 0;
        for (String roleCode : PermissionMatrix.roles()) {
            Long roleId = roleIds.get(roleCode);
            if (roleId == null) {
                log.warn("rbac seed: tenant {} is missing built-in role {}; skipped", tenantId,
                        roleCode);
                continue;
            }
            inserted += insertGrants(tenantId, roleId, preset(roleCode));
        }
        log.info("rbac seed: tenant {} topped up with {} preset grant rows", tenantId, inserted);
        return inserted;
    }

    /**
     * The preset grid of one built-in role: 60 {@code FUNCTION} + 36 {@code MENU} + 72
     * {@code ACTION} rows, {@code DENY} included.
     *
     * <p>Pure and static: the tests, {@code s2} and the demo reset all need the same 168 rows and
     * none of them should have to start a database to get them.</p>
     */
    public static List<Row> preset(String roleCode) {
        List<Row> rows = new ArrayList<>(PermissionMatrix.functions().size()
                + MenuRegistry.pages().size() + ActionRegistry.actions().size());
        for (String function : PermissionMatrix.functions()) {
            rows.add(new Row(FUNCTION, function,
                    PermissionMatrix.accessFor(roleCode, function).name()));
        }
        for (MenuRegistry.MenuPage page : MenuRegistry.pages()) {
            rows.add(new Row(MENU, page.code(),
                    PermissionMatrix.accessForAny(roleCode, page.functionCodes()).name()));
        }
        for (ActionRegistry.ActionDef action : ActionRegistry.actions()) {
            rows.add(new Row(ACTION, action.code(), actionAccessFor(roleCode, action).name()));
        }
        return rows;
    }

    /**
     * 角色 × 动作的最终档位：先看显式覆盖，再回落到 {@code functionCodes} 的 max 派生。
     *
     * <p>这是 {@link #preset(String)} 里 ACTION 行的唯一判据，也是测试与 {@code s2} 重算期望值的
     * 唯一入口：两侧都调它，才不会出现「seeder 认为该 DENY、门禁按 max 认为该 ALLOW」的分歧。</p>
     */
    public static PermissionMatrix.Access actionAccessFor(
            String roleCode, ActionRegistry.ActionDef action) {
        if (ACTION_DENY_OVERRIDES.getOrDefault(roleCode, Set.of()).contains(action.code())) {
            return PermissionMatrix.Access.DENY;
        }
        return PermissionMatrix.accessForAny(roleCode, action.functionCodes());
    }

    /** 某角色的显式 ACTION 覆盖集合（只读，供测试与 s2 对照，不要让调用方改它）。 */
    public static Set<String> actionDenyOverrides(String roleCode) {
        return ACTION_DENY_OVERRIDES.getOrDefault(roleCode, Set.of());
    }

    /**
     * 与预设逐格不同的格子数：缺行、多余行、档位不同都各计 1；恰好等于预设时为 0。
     *
     * <p>期望侧只来自 {@link #preset(String)}，不另写一段「SQL 版期望」：判差异与写回必须是同一个
     * 公式，否则「seeder 认为已是预设、别处认为不是」这类分歧只能在重置后的行数上暴露。逐格口径与
     * {@code s2-seed-equivalence.sh} 的 SQL 推导仍互为交叉验证——两边都由注册表派生，绑定关系一改，
     * 两边同时红。</p>
     *
     * <p>只统计租户里<b>存在</b>的内置角色：低于当前版本的老租户可能少几个内置角色，
     * {@link #seedTenant(long)} 也不会凭空补造它们，把缺失角色的 168 格算成差异只会让每次重置都退回
     * 整表重写（正是本方法要消掉的写放大）。反方向的多余行（自定义角色、{@code role_id} 已不存在的
     * 孤儿行）计入差异——它们同样不属于预设，且重置的整表口径正是要清掉它们。</p>
     */
    public long driftCells(long tenantId) {
        Map<String, Long> roleIds = new LinkedHashMap<>();
        for (Object[] row : db.rows("SELECT code, id FROM sys_role WHERE tenant_id = ? ORDER BY id",
                tenantId)) {
            roleIds.put(SysDb.asString(row[0]), SysDb.asLong(row[1]));
        }
        Map<String, String> expected = new LinkedHashMap<>();
        for (String roleCode : PermissionMatrix.roles()) {
            Long roleId = roleIds.get(roleCode);
            if (roleId == null) {
                continue;
            }
            for (Row row : preset(roleCode)) {
                expected.put(cell(roleId, row.subjectType(), row.code()), row.access());
            }
        }
        long drift = 0;
        Set<String> stored = new HashSet<>();
        for (Object[] row : db.rows("SELECT role_id, subject_type, subject_code, access"
                + " FROM sys_role_permission WHERE tenant_id = ?", tenantId)) {
            String key = cell(SysDb.asLong(row[0]), SysDb.asString(row[1]), SysDb.asString(row[2]));
            stored.add(key);
            String want = expected.get(key);
            if (want == null || !want.equals(SysDb.asString(row[3]))) {
                drift++;
            }
        }
        for (String key : expected.keySet()) {
            if (!stored.contains(key)) {
                drift++;
            }
        }
        return drift;
    }

    /** 一格授权：角色 + 层 + 主体码唯一确定一行（与 {@code uk_rp} 同粒度）。 */
    private static String cell(long roleId, String subjectType, String code) {
        return roleId + "|" + subjectType + "|" + code;
    }

    private int insertGrants(long tenantId, long roleId, List<Row> rows) {
        int inserted = 0;
        for (int from = 0; from < rows.size(); from += CHUNK) {
            List<Row> chunk = rows.subList(from, Math.min(from + CHUNK, rows.size()));
            StringBuilder sql = new StringBuilder(
                    "INSERT IGNORE INTO sys_role_permission"
                            + " (tenant_id, role_id, subject_type, subject_code, access) VALUES ");
            List<Object> params = new ArrayList<>(chunk.size() * 5);
            for (int i = 0; i < chunk.size(); i++) {
                Row row = chunk.get(i);
                sql.append(i == 0 ? "" : ", ").append("(?, ?, ?, ?, ?)");
                params.add(tenantId);
                params.add(roleId);
                params.add(row.subjectType());
                params.add(row.code());
                params.add(row.access());
            }
            inserted += db.update(sql.toString(), params.toArray());
        }
        return inserted;
    }

    /** One grant row: subject type, subject code and the level the preset gives it. */
    public record Row(String subjectType, String code, String access) {
    }
}
