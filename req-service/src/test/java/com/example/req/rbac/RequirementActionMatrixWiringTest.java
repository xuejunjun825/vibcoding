package com.example.req.rbac;

import com.example.platform.common.security.PermissionGrantsRepository;
import com.example.platform.common.security.PermissionService;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.ActionRegistry;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.MenuRegistry;
import com.example.platform.contract.PermissionMatrix;
import com.example.platform.contract.PermissionMatrix.Access;
import com.example.platform.contract.RequirementActionMatrix;
import com.example.platform.contract.RequirementActionMatrix.Caller;
import com.example.req.seed.RbacSeeder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * r22 wiring proof: {@link RequirementActionMatrix} driven by the <b>real</b>
 * {@link PermissionService} over a grid seeded by {@link RbacSeeder#preset(String)}.
 *
 * <p>{@code RequirementActionMatrixTest} stubs the resolver, so it proves the matrix asks the right
 * questions but not that the answers come from the seeded data. Two halves stay unproven there and
 * are proven here:</p>
 * <ol>
 *   <li><b>ACTION cells are the same data the FUNCTION cells come from.</b> The grid is built with
 *       the production formula ({@code preset} reads {@code ActionRegistry.functionCodes} and
 *       {@code PermissionMatrix.accessForAny}), and the expectation is re-derived from the same
 *       compile-time matrix — so "the action layer silently disagrees with the function layer"
 *       turns red, which no lambda stub can show.</li>
 *   <li><b>The values come from the request context.</b> Roles are read from
 *       {@link TenantContext#roles()} by the resolver, while {@code vendorId} is carried by
 *       {@link Caller}. A stub that judged {@code Caller.roles} would stay green while production
 *       answers from a different set of roles — the classic "unit green, 403 in production".</li>
 * </ol>
 *
 * <p>The baseline is a <b>frozen copy</b> of the r17 rule ({@link #legacyCan}). It is deliberately
 * not refactored to share code with the matrix: it is the artifact this change must not move. The
 * only permitted differences are the three documented in {@code TECH-CHANGE.md} §8 — own-row now
 * gated by the action cell (§8.3), delegation reading the {@code req.action.approve} cell (§8.1),
 * and the two corrected registry bindings (§8.2). Everything else being equal is the AC.</p>
 */
class RequirementActionMatrixWiringTest {

    private static final long TENANT = 42L;
    /** The requirement's reporter (biz1 in the demo fixtures). */
    private static final long REPORTER = 2L;
    /** A caller who is not the reporter. */
    private static final long OTHER_USER = 99L;
    private static final Long VENDOR_ID = 1L;

    private static final String APPROVE = RequirementActionMatrix.ACTION_PREFIX + "approve";

    private static final Set<String> SELF_SERVICE = Set.of("submit", "withdraw");

    private static final List<String> REVIEW_SIDE = List.of(
            "approve", "reject", "review_start", "review_pass", "review_reject", "accept", "close");

    /**
     * The r17 rule, frozen: action → the function code it required. A literal on purpose — reading
     * it back from the matrix (or from the registry) would make the comparison circular.
     */
    private static final Map<String, String> LEGACY_REQUIRED_FUNCTION = Map.ofEntries(
            Map.entry("submit", "req:review"),
            Map.entry("approve", "req:review"),
            Map.entry("reject", "req:review"),
            Map.entry("withdraw", "req:review"),
            Map.entry("review_start", "req:review"),
            Map.entry("review_pass", "req:review"),
            Map.entry("review_reject", "req:review"),
            Map.entry("accept", "req:review"),
            Map.entry("close", "req:review"),
            Map.entry("schedule", "release:plan"),
            Map.entry("start_dev", "req:deliver"),
            Map.entry("deliver", "req:deliver"),
            Map.entry("merge", "req:deliver"),
            Map.entry("finish", "req:deliver"),
            Map.entry("submit_test", "test:quick"),
            Map.entry("test_pass", "test:quick"));

    /** The two registry rows r22 corrected; the only cells whose function binding may differ. */
    private static final Map<String, List<String>> PRE_R22_BINDINGS = Map.of(
            "req.action.start_dev", List.of("req:split"),
            "req.action.submit_test", List.of("req:deliver"));

    /** The three permitted kinds of difference from r17 (TECH-CHANGE §8.1–8.3). */
    private static final String SELF_SERVICE_DELTA = "§8.3 本人提报行受动作档位管";
    private static final String DELEGATION_DELTA = "§8.1 代他人读 req.action.approve 格";
    private static final String REGISTRY_DELTA = "§8.2 注册表两条登记校正";

    private FakeRepository repository;
    private PermissionService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(DataScope.system(TENANT));
        repository = new FakeRepository();
        repository.seed();
        service = new PermissionService(repository, 45);
    }

    @AfterEach
    void tearDown() {
        service.clearRequestMemo();
        TenantContext.clear();
    }

    @Test
    @DisplayName("10 个内置角色 × 16 动作 × 本人/他人/厂商：与按种子公式推出的语义逐格一致")
    void matchesSeededGridForEachRole() {
        int cells = 0;
        for (String role : PermissionMatrix.roles()) {
            for (Relation relation : Relation.values()) {
                Caller caller = act(role, relation);
                for (String action : RequirementActionMatrix.actions()) {
                    assertThat(RequirementActionMatrix.can(action, REPORTER, caller, service))
                            .as("%s / %s / %s", role, action, relation)
                            .isEqualTo(expectedCan(action, REPORTER, caller));
                    cells++;
                }
            }
        }
        assertThat(cells)
                .as("每个角色、每种关系都必须逐格覆盖")
                .isEqualTo(PermissionMatrix.roles().size() * Relation.values().length
                        * RequirementActionMatrix.actions().size());
    }

    @Test
    @DisplayName("与 r17 旧规则逐格对比：差异只有 §8 那三条，本预设格上只剩 MGMT/VENDOR_PM 的本人提报")
    void differencesFromTheR17RuleAreTheThreeDocumentedOnes() {
        List<String> unexplained = new ArrayList<>();
        Set<String> selfService = new TreeSet<>();
        Set<String> delegation = new TreeSet<>();
        Set<String> registry = new TreeSet<>();

        for (String role : PermissionMatrix.roles()) {
            for (Relation relation : Relation.values()) {
                Caller caller = act(role, relation);
                for (String action : RequirementActionMatrix.actions()) {
                    boolean legacy = legacyCan(action, REPORTER, caller);
                    boolean actual = RequirementActionMatrix.can(action, REPORTER, caller, service);
                    if (legacy == actual) {
                        continue;
                    }
                    String cell = role + "|" + action + "|" + relation;
                    String bucket = bucketOf(action, REPORTER, caller, legacy, actual);
                    if (SELF_SERVICE_DELTA.equals(bucket)) {
                        selfService.add(cell);
                    } else if (DELEGATION_DELTA.equals(bucket)) {
                        delegation.add(cell);
                    } else if (REGISTRY_DELTA.equals(bucket)) {
                        registry.add(cell);
                    } else {
                        unexplained.add(cell + " (legacy=" + legacy + ", now=" + actual + ")");
                    }
                }
            }
        }

        // 任何一格解释不了的差异即失败：等价升级是本需求的判据，不是“大概一致”。
        assertThat(unexplained).as("r22 相对 r17 的差异必须能归到 §8 的三条里").isEmpty();

        // 预设格上真正会变的只有「本人提报」：MGMT 两个 req:report:* 都是 DENY，动作格跟着 DENY。
        // 这正是 TECH §4「变化 2（收紧，有意）」——而 BUSINESS/PRODUCT/PMO/DEV/QA 的本人提报不受影响。
        // r24 第 11 个角色 VENDOR_PM 同样缺 req:create/req:submit，落到同一条 OWN 自助路径
        // （它只读本厂商需求，自助提报被 DENY 收口，与 MGMT 同类，不是新语义）。
        assertThat(selfService).as("本人提报受档位管的实际落点").containsExactly(
                "MGMT|submit|OWN", "MGMT|withdraw|OWN",
                "VENDOR_PM|submit|OWN", "VENDOR_PM|withdraw|OWN");
        assertThat(delegation).as("预设格里 approve 全为 ALLOW 或 DENY，没有 RESTRICTED → 无差异"
                + "（收窄后的行为见 delegationReadsTheApproveCell）").isEmpty();
        assertThat(registry).as("注册表已校正 → 这两格不再与 r17 判定不同"
                + "（不校正的后果见 registryCorrectionKeepsProductAndQaInPlace）").isEmpty();
    }

    @Test
    @DisplayName("§8.1：代他人读 approve 格 —— 收窄到 RESTRICTED 就失去代提交（旧规则仍放行）")
    void delegationReadsTheApproveCell() {
        Caller pmo = act("PMO", Relation.OTHER);
        assertThat(RequirementActionMatrix.can("submit", REPORTER, pmo, service))
                .as("全量 approve 授权可代提交").isTrue();

        repository.put("PMO", PermissionService.Layer.ACTION, APPROVE, Access.RESTRICTED);
        service.invalidate(TENANT);

        assertThat(legacyCan("submit", REPORTER, pmo))
                .as("旧规则只看 req:review 非 DENY，RESTRICTED 也算").isTrue();
        assertThat(RequirementActionMatrix.can("submit", REPORTER, pmo, service))
                .as("代提交是提权动作，只认全量授权").isFalse();
        assertThat(RequirementActionMatrix.filter("BUS", "SUBMITTED", REPORTER, pmo, service))
                .as("按钮列表同步消失，不能出现“按钮在、接口 403”").isEmpty();

        // 厂商账号连全量授权都不行（vendorId 而非角色码区分）。
        Caller vendor = act("PMO", Relation.VENDOR_OTHER);
        assertThat(RequirementActionMatrix.can("submit", REPORTER, vendor, service)).isFalse();
    }

    @Test
    @DisplayName("§8.2：沿用 r17 前的绑定会让 PRODUCT 扩权、QA 不能提测")
    void registryCorrectionKeepsProductAndQaInPlace() {
        // 把这两格按 r17 之前的登记重播（其余格不动），模拟“忘了校正注册表”的库。
        PRE_R22_BINDINGS.forEach(repository::rebind);
        service.invalidate(TENANT);

        Caller product = act("PRODUCT", Relation.OTHER);
        assertThat(legacyCan("start_dev", REPORTER, product))
                .as("r17 判据：PRODUCT 无 req:deliver → 不能开始开发").isFalse();
        assertThat(RequirementActionMatrix.can("start_dev", REPORTER, product, service))
                .as("沿用 req:split（PRODUCT 是 RESTRICTED）→ 静默扩权").isTrue();

        Caller qa = act("QA", Relation.OTHER);
        assertThat(legacyCan("submit_test", REPORTER, qa))
                .as("r17 判据：QA 持 test:quick → 可以提测").isTrue();
        assertThat(RequirementActionMatrix.can("submit_test", REPORTER, qa, service))
                .as("沿用 req:deliver（QA 是 DENY）→ 提测人自己提不了测").isFalse();
    }

    @Test
    @DisplayName("页面格 DENY 连动作一起关（三序第一序）—— 桩测不出的另一半")
    void pageDenialClosesEveryRequirementAction() {
        Caller product = act("PRODUCT", Relation.OWN);
        assertThat(RequirementActionMatrix.can("review_start", REPORTER, product, service)).isTrue();

        repository.put("PRODUCT", PermissionService.Layer.MENU,
                RequirementActionMatrix.PAGE, Access.DENY);
        service.invalidate(TENANT);

        assertThat(RequirementActionMatrix.can("review_start", REPORTER, product, service))
                .as("页面 DENY 优先于动作档位").isFalse();
        assertThat(RequirementActionMatrix.can("submit", REPORTER, product, service))
                .as("自服务也一起关").isFalse();
        assertThat(RequirementActionMatrix.filter("BUS", "PENDING_REVIEW", REPORTER, product, service))
                .isEmpty();

        // 别的角色同页档位不受影响 —— 页面格是按角色判的，不是全局开关。
        Caller pmo = act("PMO", Relation.OWN);
        assertThat(RequirementActionMatrix.can("review_start", REPORTER, pmo, service)).isTrue();
    }

    @Test
    @DisplayName("角色来自 TenantContext，vendorId 来自 Caller —— 接错任何一半都是线上 403")
    void rolesComesFromTheRequestContextNotFromTheCaller() {
        act("QA", Relation.OWN);
        // 同一请求里伪造 Caller.roles 不能提权：判定读的是上下文（QA 没有 req:review）。
        Caller forged = new Caller(REPORTER, List.of("PLATFORM_ADMIN"), null);
        assertThat(RequirementActionMatrix.can("review_start", REPORTER, forged, service)).isFalse();

        act("PLATFORM_ADMIN", Relation.OWN);
        assertThat(RequirementActionMatrix.can("review_start", REPORTER,
                new Caller(REPORTER, List.of("PLATFORM_ADMIN"), null), service)).isTrue();

        // vendorId 反过来只认 Caller：同一 PMO 会话，厂商快照一律不得评审。
        act("PMO", Relation.VENDOR_OTHER);
        assertThat(RequirementActionMatrix.can("review_start", REPORTER,
                new Caller(OTHER_USER, List.of("PMO"), VENDOR_ID), service)).isFalse();
        assertThat(RequirementActionMatrix.can("review_start", REPORTER,
                new Caller(OTHER_USER, List.of("PMO"), null), service))
                .as("内部 PMO 可以评审 —— 上下文里的角色没变，变的只有 vendorId")
                .isTrue();
    }

    @Test
    @DisplayName("req.pool 对每个内置角色都是 ALLOW —— 所以矩阵的桩解析器可以把页面序当空操作")
    void requirementPageIsGrantedToEveryRole() {
        assertThat(MenuRegistry.page(RequirementActionMatrix.PAGE)).isPresent();
        assertThat(MenuRegistry.page(RequirementActionMatrix.PAGE).orElseThrow().functionCodes())
                .isEmpty();
        for (String role : PermissionMatrix.roles()) {
            assertThat(repository.cell(role, PermissionService.Layer.MENU, RequirementActionMatrix.PAGE))
                    .as("%s 的 req.pool 页面格", role)
                    .isEqualTo(Access.ALLOW);
        }
    }

    // ------------------------------------------------------------------ expectations

    /**
     * The r22 semantics, derived from the same compile-time matrix the seed derives from — never
     * from the service under test. Only the seed formula is reproduced ({@link #actionCell}); the
     * relationship rules are restated here so a change on either side turns red.
     */
    private static boolean expectedCan(String action, Long reporterId, Caller caller) {
        String code = RequirementActionMatrix.actionCode(action);
        if (code == null || actionCell(code, caller.roles()) == Access.DENY) {
            return false;
        }
        if (SELF_SERVICE.contains(action)) {
            if (reporterId != null && reporterId.longValue() == caller.userId()) {
                return true;
            }
            return caller.vendorId() == null
                    && actionCell(APPROVE, caller.roles()) == Access.ALLOW;
        }
        return !(REVIEW_SIDE.contains(action) && caller.vendorId() != null);
    }

    /** The r17 rule, frozen. Deliberately judges {@code Caller.roles}, as r17 did. */
    private static boolean legacyCan(String action, Long reporterId, Caller caller) {
        String function = LEGACY_REQUIRED_FUNCTION.get(action);
        if (function == null) {
            return false;
        }
        if (SELF_SERVICE.contains(action)) {
            if (reporterId != null && reporterId.longValue() == caller.userId()) {
                return true;
            }
            return caller.vendorId() == null
                    && PermissionMatrix.accessOf(function, caller.roles()) == Access.ALLOW;
        }
        if (RequirementActionMatrix.REVIEW.equals(function) && caller.vendorId() != null) {
            return false;
        }
        return PermissionMatrix.accessOf(function, caller.roles()) != Access.DENY;
    }

    /**
     * Which of the three permitted deltas explains this cell, or {@code null} when nothing does.
     *
     * <p>Each bucket also checks the direction and the cause, so a wiring bug cannot hide behind a
     * bucket name: a self-service diff must be caused by the action cell turning DENY, a delegation
     * diff by the approve cell being narrower than ALLOW, and a registry diff by one of the two
     * corrected rows.</p>
     */
    private static String bucketOf(String action, Long reporterId, Caller caller,
                                   boolean legacy, boolean actual) {
        if (SELF_SERVICE.contains(action)) {
            if (reporterId != null && reporterId.longValue() == caller.userId()) {
                boolean cellClosedIt =
                        actionCell(RequirementActionMatrix.actionCode(action), caller.roles())
                                == Access.DENY;
                return legacy && !actual && cellClosedIt ? SELF_SERVICE_DELTA : null;
            }
            boolean approveNarrowed = actionCell(APPROVE, caller.roles()) == Access.RESTRICTED;
            return legacy && !actual && approveNarrowed ? DELEGATION_DELTA : null;
        }
        String code = RequirementActionMatrix.actionCode(action);
        boolean correctedRow = PRE_R22_BINDINGS.containsKey(code);
        boolean bindingMoved = !ActionRegistry.require(code).functionCodes()
                .equals(PRE_R22_BINDINGS.getOrDefault(code, List.of()));
        return correctedRow && bindingMoved ? REGISTRY_DELTA : null;
    }

    /**
     * The ACTION cell for a role set, derived the way {@code RbacSeeder.preset} derives it:
     * {@code accessForAny(role, ActionRegistry.require(code).functionCodes())} per role, merged with
     * {@link PermissionService#union} ("any explicit DENY wins"). Seed formula only — no second
     * copy of the decision rule.
     */
    private static Access actionCell(String actionCode, List<String> roles) {
        if (actionCode == null || roles == null || roles.isEmpty()) {
            return Access.DENY;
        }
        List<String> functionCodes = ActionRegistry.require(actionCode).functionCodes();
        Access merged = null;
        for (String role : roles) {
            Access value = PermissionMatrix.accessForAny(role, functionCodes);
            merged = merged == null ? value : PermissionService.union(merged, value);
        }
        return merged == null ? Access.DENY : merged;
    }

    // ------------------------------------------------------------------ fixtures

    /** Establish one request: the principal (roles + vendor) the resolver reads, and its snapshot. */
    private Caller act(String role, Relation relation) {
        Long vendorId = relation.vendor ? VENDOR_ID : null;
        TenantContext.setPrincipal(new TenantContext.Principal(relation.callerId, role.toLowerCase(),
                role, "acme", "Acme", List.of(role), Set.of(), vendorId));
        // A new principal is a new request: the resolver's memo is per-request, so it must be
        // dropped or the previous role's effective grants would answer for this one.
        service.clearRequestMemo();
        return new Caller(relation.callerId, List.of(role), vendorId);
    }

    /** The three relations the AC names: own row, someone else's row, and a vendor account. */
    private enum Relation {
        OWN(REPORTER, false),
        OTHER(OTHER_USER, false),
        VENDOR_OTHER(OTHER_USER, true);

        private final long callerId;
        private final boolean vendor;

        Relation(long callerId, boolean vendor) {
            this.callerId = callerId;
            this.vendor = vendor;
        }
    }

    /**
     * A repository double holding the seeded 1680 rows: the real one needs a live
     * {@code sys_role_permission} table.
     *
     * <p>{@link #seed()} replays {@link RbacSeeder#preset(String)} row for row instead of
     * re-deriving the grid: if the seed formula changes, this fixture follows it automatically and
     * only the expectations (which read the same registries) can disagree.</p>
     */
    private static final class FakeRepository extends PermissionGrantsRepository {

        private final Map<String, Map<PermissionService.Layer, Map<String, Access>>> rows =
                new LinkedHashMap<>();

        FakeRepository() {
            super(new JdbcTemplate());
        }

        void seed() {
            for (String role : PermissionMatrix.roles()) {
                for (RbacSeeder.Row row : RbacSeeder.preset(role)) {
                    put(role, PermissionService.Layer.valueOf(row.subjectType()), row.code(),
                            Access.valueOf(row.access()));
                }
            }
        }

        void put(String role, PermissionService.Layer layer, String code, Access access) {
            rows.computeIfAbsent(role, key -> new LinkedHashMap<>())
                    .computeIfAbsent(layer, key -> new LinkedHashMap<>())
                    .put(code, access);
        }

        /** Re-derive one ACTION cell for every role, as a differently-bound registry would. */
        void rebind(String actionCode, List<String> functionCodes) {
            for (String role : PermissionMatrix.roles()) {
                put(role, PermissionService.Layer.ACTION, actionCode,
                        PermissionMatrix.accessForAny(role, functionCodes));
            }
        }

        Access cell(String role, PermissionService.Layer layer, String code) {
            Map<PermissionService.Layer, Map<String, Access>> layers = rows.get(role);
            return layers == null ? Access.DENY
                    : layers.getOrDefault(layer, Map.of()).getOrDefault(code, Access.DENY);
        }

        @Override
        public PermissionService.Snapshot load(long tenantId, long epoch) {
            Map<PermissionService.Layer, Map<String, Map<String, Access>>> byRole =
                    new LinkedHashMap<>();
            for (PermissionService.Layer layer : PermissionService.Layer.values()) {
                byRole.put(layer, new LinkedHashMap<>());
            }
            rows.forEach((role, layers) -> layers.forEach((layer, codes) ->
                    byRole.get(layer).put(role, new LinkedHashMap<>(codes))));
            return new PermissionService.Snapshot(tenantId, epoch, System.currentTimeMillis(),
                    byRole);
        }
    }
}
