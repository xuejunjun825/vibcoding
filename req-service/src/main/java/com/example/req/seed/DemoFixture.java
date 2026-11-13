package com.example.req.seed;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 演示数据集的代码常量与行定义。
 *
 * <p>重置算法（{@link DemoResetService}）只从这里取「删什么、插什么」，数据与算法分离：
 * 改数据集不动回滚/不变量逻辑；改删除顺序只改 {@link #DELETE_ORDER} 一处——顺序散落在两处
 * 时，外键拓扑一变必漏一处，而 MySQL 的这些表没有 FK 约束，漏删只会静默留下孤儿行。</p>
 *
 * <p>规模由 PRD AC-13 钉死：需求 12 条（8 条 po1 组织 / 3 条 VEN-A / 1 条 VEN-C）、版本 1、
 * 迭代 1、用例 12、缺陷 3、测试计划 1（挂 10 条用例）、任务 8、通知 4。请求的隔离口径
 * （{@code po1=8}、{@code vendor1=3}、{@code vendor2=0}）由 {@link DemoResetService} 按真实的
 * 数据范围规则自检，不在数据里写死结论。</p>
 *
 * <p>日期是「相对今天」的：{@code -12} 表示 12 天前。演示数据集因此永远不过期（甘特图上
 * 没有逾期红条），而计数与幂等性不受影响——同一天连跑两次插入的行完全一致。</p>
 */
public final class DemoFixture {

    private DemoFixture() {
    }

    /** 演示租户：与 {@link SeedService} 同一套种子（12 账号 / 7 域 / 3 厂商）。 */
    public static final String TENANT_CODE = "t1";

    public static final String REQUIREMENT_CODE_PREFIX = "DEMO-R-";
    public static final String CASE_CODE_PREFIX = "DEMO-C-";
    public static final String DEFECT_CODE_PREFIX = "DEMO-D-";
    public static final String RELEASE_CODE = "DEMO-REL-1";
    public static final String SPRINT_NAME = "演示迭代-1";
    public static final String PLAN_NAME = "演示回归计划";

    /** 期望计数（AC-13）：服务自检与脚本渲染计数表共用同一组数字。 */
    public static final long REQUIREMENT_COUNT = 12;
    public static final long RELEASE_COUNT = 1;
    public static final long SPRINT_COUNT = 1;
    public static final long TASK_COUNT = 8;
    public static final long MILESTONE_COUNT = 1;
    public static final long CASE_COUNT = 12;
    public static final long PLAN_COUNT = 1;
    public static final long PLAN_CASE_COUNT = 10;
    public static final long DEFECT_COUNT = 3;
    public static final long NOTIFICATION_COUNT = 4;
    /** r15-fix：派生实例数据没有种子，重置后必须为 0（否则是 release 已删的孤儿）。 */
    public static final long TEST_STRATEGY_COUNT = 0;
    /** r15-fix：种子环境行（SeedService 种下、重置不重放）——门禁残留清掉后应恰好剩这一行。 */
    public static final long TEST_ENV_SEED_COUNT = 1;
    /** r15-fix：门禁 RUN_TAG 残留，三张种子表合计的唯一合法值。 */
    public static final long GATE_RESIDUE_COUNT = 0;
    public static final long PO1_VISIBLE = 8;
    public static final long VENDOR_A_VISIBLE = 3;
    public static final long VENDOR_B_VISIBLE = 0;

    /**
     * 保留行的基线状态：{@code keepFilter} 只保证「行还在」，不保证「值还是种子值」。
     *
     * <p>V9 ⑤ 种下的两行执行器表达的是两条事实：{@code STUB-LOCAL} 启用但从未上报（→ NEVER）、
     * {@code LEGACY-OFF} 停用（→ DISABLED）。但 {@code last_report_at} / {@code report_count}
     * 是可写字段：s7 门禁会用 {@code UPDATE ... last_report_at = NOW() - INTERVAL 1/30/100 HOUR}
     * 验三档边界，最后再通过开放 API 上报一次（→ NOW）。行被 keepFilter 保住之后，这些改动
     * 会跨重置存活，演示台账就再也回不到 NEVER——下一次 {@code make test} 的 s7 会因为
     * 「种子里同时有 NEVER 与 DISABLED」而红。所以重置必须把保留行也还原到种子基线：
     * 删表和还原字段是同一个「回到可演示的固定夹具」判断，仍由本文件一处拥有。</p>
     */
    public static final String RESTORE_KEPT_EXECUTOR_BASELINE =
            "UPDATE test_executor SET last_report_at = NULL, report_count = 0,"
                    + " enabled = CASE code WHEN 'STUB-LOCAL' THEN 1 ELSE 0 END"
                    + " WHERE tenant_id = ? AND code IN ('STUB-LOCAL','LEGACY-OFF')";

    /**
     * 门禁按 RUN_TAG 命名的探针行：{@code platform-verify.sh} / {@code platform-test-negative.sh} / q7
     * 每跑一次，就用「前缀 + hhmmss」在种子表里留下一行（{@code verify-wf-090925}、{@code neg-L1-190647}、
     * {@code 验证模块L1-190239}、{@code QA环境-1109190528}）。
     *
     * <p>它们是<b>行</b>而不是表——所以「整表不进序列」挡不住它们：重置后流程定义页、用例模块树、
     * 环境台账仍然挂着上一轮门禁的痕迹（r15 实测 253 / 204 / 19 行）。这三张表的种子由 req-service
     * 的 {@code SeedService} 种下（V1/V5 迁移只有 CREATE、没有 INSERT），删了没人重放，因此只能删
     * 命中本谓词的行，不能整表清空。</p>
     *
     * <p>这四个前缀是门禁命名约定的唯一一份拷贝（门禁脚本是跨进程的另一份，无法共享常量）；门禁若
     * 换了命名，必须同步这里——{@code qa-r15-table-coverage.sh} 会直接对库断言残留为 0。</p>
     */
    public static final String GATE_RUN_TAG_RESIDUE =
            "name LIKE 'verify-%' OR name LIKE 'neg-%' OR name LIKE '验证模块%' OR name LIKE 'QA环境-%'";

    /** 需要清理 {@link #GATE_RUN_TAG_RESIDUE} 的种子表：删除序列与 {@code counts()} 共用这一份。 */
    public static final List<String> RUN_TAG_RESIDUE_TABLES =
            List.of("test_env", "wf_definition", "test_module");

    /**
     * 一条删除指令：{@code table} 必填，{@code keepFilter} 为可选的保留谓词（{@code null} = 整表清空）。
     *
     * <p>为什么把「保留」做成数据而不是第二份白名单：删哪些表、留哪些行是同一个领域判断，
     * 分散到两处（序列一份 + bash 快照一份）必然给出互相矛盾的结论。谓词里不要再写
     * {@code tenant_id}——外层 {@code WHERE tenant_id = ?} 已经收口，重复写只会让人以为可以跨租户。</p>
     *
     * <p>表名与谓词都只来自本文件的字面量，没有任何请求参数参与拼接，因此
     * {@code DELETE FROM <table> WHERE tenant_id = ? AND NOT (<keepFilter>)} 不存在注入面。</p>
     */
    public record TableReset(String table, String keepFilter) {

        /** 整表清空：本租户该表没有需要跨重置存活的行。 */
        public static TableReset purge(String table) {
            return new TableReset(table, null);
        }

        /** 清空但保留谓词命中的行（迁移种子等「删了没人重放」的数据）。 */
        public static TableReset purgeExcept(String table, String keepFilter) {
            return new TableReset(table, keepFilter);
        }

        /**
         * 只清门禁 RUN_TAG 探针行，种子行与人工建的行都保留（谓词见 {@link #GATE_RUN_TAG_RESIDUE}）。
         *
         * <p>{@code keepFilter} 的语义是「要保住的<b>保留</b>谓词」，所以传下去的是残留模式的
         * <b>取反</b>：{@code DELETE ... WHERE tenant_id = ? AND NOT (NOT (<残留模式>))}，
         * 即只删命中残留的行。删除范围因此仍由本文件一处决定，不在 bash 里再维护第二份前缀清单。</p>
         */
        public static TableReset purgeRunTagResidue(String table) {
            return new TableReset(table, "NOT (" + GATE_RUN_TAG_RESIDUE + ")");
        }
    }

    /**
     * 删除顺序：子表 → 父表，一次一条 {@code DELETE ... WHERE tenant_id = ?}。
     *
     * <p>删业务实例数据；账号/角色/域/厂商/组织/指标定义/字典/集成配置等定义数据，以及
     * {@code test_module}/{@code wf_definition}/{@code test_env} 的<b>种子行</b>保留（AC-13「保留」清单）。
     * {@code audit_log} 也不删：它是只追加的审计轨迹，
     * 删掉既毁证据链，也会和「按时间窗对比审计增量」的既有断言拧着来。列表按 V1–V10 的引用拓扑排：
     * 先清评审、报告、门禁、计划轮次，再清用例/缺陷/审批，最后才动需求与版本。</p>
     *
     * <p>末尾四张是 r15 补入的漏删表（V10 的报表/卡配置、V9 的执行器、V1 的开放客户端）。迁移里
     * 一张 FOREIGN KEY 都没有，所以顺序写错不会报错、只会静默留下孤儿行——顺序因此仍由这张清单
     * 单点拥有：{@code portal_card_config} 的卡片 key 以 {@code report:<id>} 引用报表，先于
     * {@code report_definition}；{@code test_executor.api_client_id} 引用 {@code api_client}，
     * 先于 {@code api_client}。</p>
     *
     * <p>保留改由 {@code keepFilter} 表达，不再靠「不进序列」：{@code test_executor} 的两行
     * （STUB-LOCAL / LEGACY-OFF）来自 V9 ⑤ 迁移种子，而重置不重放迁移，故带
     * {@code code IN (...)} 过滤保留，只清门禁留下的 {@code S7-*} 探针行；{@code api_client}
     * 恰恰相反——V1 建表但没有任何种子，现存行全来自 CI/手工，故无过滤全清。
     * {@code branch_policy}（V9 ⑤ 种子 1 行）仍不进序列：同样是迁移种子，删了没人重放，
     * 而 /code-policies 要靠它渲染。</p>
     *
     * <p>r15-fix（QA D-01/D-02）补入两类运行时残留：{@code test_strategy} 是<b>派生</b>实例数据——
     * V5 只按「当时已有的 release」回填一版，重置删光 release 后不会重跑，留一行就是一行孤儿
     * （实测 209 行 release_id 全指向已删版本），故整表清空且排在 {@code release} <b>之前</b>；
     * 末尾三张则用 {@link TableReset#purgeRunTagResidue} 只清门禁 RUN_TAG 探针行。它们必须留在
     * 序列里，否则 {@code counts()} 的残留计数与这里的删除范围会各说一套。</p>
     */
    public static final List<TableReset> DELETE_ORDER = List.of(
            TableReset.purge("test_case_review"),
            TableReset.purge("report_signoff"),
            TableReset.purge("test_report_version"),
            TableReset.purge("test_report"),
            TableReset.purge("quality_gate_waiver"),
            TableReset.purge("quality_gate_snapshot"),
            TableReset.purge("quality_gate_rule"),
            TableReset.purge("quality_gate"),
            TableReset.purge("release_review"),
            TableReset.purge("plan_change_log"),
            TableReset.purge("plan_assignment"),
            TableReset.purge("test_plan_round"),
            TableReset.purge("test_execution"),
            TableReset.purge("test_plan_case"),
            TableReset.purge("test_plan"),
            TableReset.purge("mr_comment"),
            TableReset.purge("mr_sync"),
            TableReset.purge("build_record"),
            TableReset.purge("artifact_record"),
            TableReset.purge("integration_sync_log"),
            TableReset.purge("api_call_log"),
            TableReset.purge("branch_policy_check"),
            TableReset.purge("test_case_step"),
            TableReset.purge("test_case_version"),
            TableReset.purge("test_case"),
            TableReset.purge("defect_history"),
            TableReset.purge("defect"),
            TableReset.purge("wf_cc"),
            TableReset.purge("wf_history"),
            TableReset.purge("wf_task"),
            TableReset.purge("wf_instance"),
            TableReset.purge("deploy_order_requirement"),
            TableReset.purge("deploy_order"),
            TableReset.purge("release_requirement"),
            TableReset.purge("milestone"),
            TableReset.purge("task"),
            TableReset.purge("sprint"),
            TableReset.purge("attachment"),
            TableReset.purge("requirement_domain_rel"),
            TableReset.purge("requirement"),
            TableReset.purge("test_strategy"),
            TableReset.purge("`release`"),
            TableReset.purge("notification"),
            TableReset.purge("portal_card_config"),
            TableReset.purge("report_definition"),
            TableReset.purgeExcept("test_executor", "code IN ('STUB-LOCAL','LEGACY-OFF')"),
            TableReset.purge("api_client"),
            TableReset.purgeRunTagResidue("test_env"),
            TableReset.purgeRunTagResidue("wf_definition"),
            TableReset.purgeRunTagResidue("test_module"));

    // ---------------------------------------------------------------- 报表（r16）

    /**
     * 一张演示报表的定义：{@code report_definition} 的一行去掉租户与属主后的部分。
     *
     * <p>定义放代码常量而不是 JSON 文件/配置：AC-8 不允许第二套目录，「删什么、留什么、回填什么」
     * 继续单点收敛在本文件——服务端与脚本都不复制这张表清单。</p>
     *
     * <p>{@link #definitionJson()} 用固定键序字符串拼接、不引 ObjectMapper：字段顺序与缩进不能成为
     * 「两次重置是否逐字节相等」的隐变量（AC-6 按字段规范化比较，字节稳定最省事）。形状必须能被
     * deliver 的 {@code ReportDefinitionCodec.read} 反序列化；字段名写错不会静默，会在 run 上以
     * 400（{@code REPORT_UNKNOWN_FIELD}）立刻暴露，脚本的 run 断言就是它的探针。</p>
     */
    public record ReportFixture(String name, String dataset, List<String> rows,
                                List<String> columns, List<String> measures, String chartType) {

        /** 固定键序 {@code datasetId, rows, columns, measures, filters}；{@code filters} 恒为 {@code []}。 */
        public String definitionJson() {
            return "{\"datasetId\":" + jsonString(dataset)
                    + ",\"rows\":" + jsonArray(rows)
                    + ",\"columns\":" + jsonArray(columns)
                    + ",\"measures\":" + jsonArray(measures)
                    + ",\"filters\":[]}";
        }
    }

    /**
     * 两张演示报表，顺序即 {@link #REPORT_NAMES} 与 {@link #reportRows} 的顺序。
     *
     * <p>A 二维交叉表（域 × 状态，需求数 + 平均交付周期）；B 单维三度量（厂商，需求数 + 预估人天 +
     * 人天偏差）。B 必须用 {@code requirement}——人天类度量只在 deliver 的 {@code AnalyticsDatasets}
     * 注册（{@code :177-187}），本侧不复刻那份白名单，漂移由 run 的 400 与脚本断言暴露。</p>
     *
     * <p>演示数据现成够出数：域 ≥ 1 行、需求合计 12 &gt; 0；厂商列有 {@code VEN-A}×3 与 {@code VEN-C}×1，
     * 预估人天合计 62 &gt; 0。</p>
     */
    public static final List<ReportFixture> REPORTS = List.of(
            new ReportFixture("需求状态分布", "requirement", List.of("domain"), List.of("status"),
                    List.of("req.total", "cycle.avg"), "TABLE"),
            new ReportFixture("厂商交付概览", "requirement", List.of("vendor"), List.of(),
                    List.of("req.total", "effort.estimate", "effort.deviation"), "TABLE"));

    /** 名称集合的唯一定义源：不变量按「已排序集合」比较（中文排序不可依赖 MySQL collation）。 */
    public static final List<String> REPORT_NAMES =
            REPORTS.stream().map(ReportFixture::name).toList();

    /** 重置后 {@code report_definition} 应有的行数：与 {@link #REPORTS} 同源，不写第二份数字。 */
    public static final int REPORT_COUNT = REPORTS.size();

    /**
     * {@code report_definition} 的插入行，列顺序：
     * {@code tenant_id, name, dataset, definition_json, chart_type, owner_id, owner_role, scope,
     * scope_ref, status, created_by, updated_by, version}。
     *
     * <p>两张表都固定 {@code PRIVATE} + 属主 admin、{@code scope_ref} 留空（不 pin 门户）：演示账号
     * 自己建的表就是这个形态，vendor 账号看不到也改不了。缺 {@code user:admin} 时由 {@link #ref}
     * 抛错，而不是插出一行空属主（{@code owner_id NOT NULL} 会炸成 500）。</p>
     */
    public static List<Object[]> reportRows(long tenantId, Map<String, Long> refs) {
        Long ownerId = ref(refs, "user:admin");
        List<Object[]> rows = new ArrayList<>(REPORTS.size());
        for (ReportFixture report : REPORTS) {
            rows.add(new Object[]{
                    tenantId,
                    report.name(),
                    report.dataset(),
                    report.definitionJson(),
                    report.chartType(),
                    ownerId,
                    "admin",
                    "PRIVATE",
                    null,
                    "ACTIVE",
                    ownerId,
                    ownerId,
                    1});
        }
        return rows;
    }

    private static String jsonArray(List<String> values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(jsonString(values.get(i)));
        }
        return out.append(']').toString();
    }

    /** 值只来自本文件的字面量（列/度量 code），既无引号也无反斜杠，故不做转义。 */
    private static String jsonString(String value) {
        return '"' + value + '"';
    }

    // ---------------------------------------------------------------- 需求

    /**
     * {@link #requirementRows} 产出的一行：[tenantId, code, type, parentCode, domainId, orgId,
     * reporterId, ownerUserId, ownerVendorId, title, priority, status, estimateDays, planStart,
     * planEnd, inRelease]。
     */
    public static final int R_TENANT = 0;
    public static final int R_CODE = 1;
    public static final int R_TYPE = 2;
    public static final int R_PARENT_CODE = 3;
    public static final int R_DOMAIN_ID = 4;
    public static final int R_ORG_ID = 5;
    public static final int R_REPORTER_ID = 6;
    public static final int R_OWNER_ID = 7;
    public static final int R_VENDOR_ID = 8;
    public static final int R_TITLE = 9;
    public static final int R_PRIORITY = 10;
    public static final int R_STATUS = 11;
    public static final int R_ESTIMATE = 12;
    public static final int R_PLAN_START = 13;
    public static final int R_PLAN_END = 14;
    public static final int R_IN_RELEASE = 15;

    /**
     * code, type, parentCode, domain, org, reporter, owner, vendor, title, priority, status,
     * estimateDays, startOffset, endOffset, inRelease。
     *
     * <p>前 8 条 origin 在 po1 所在的 SUPPLY 部门 → po1/biz1/suite1（BIZ 档按 origin 子树过滤）
     * 恰好看到 8 条；后 4 条 origin 在 IT 部门（不在 po1 子树内），其中 3 条承接厂商为 VEN-A
     * → vendor1（VENDOR 档按 owner/assignee 厂商过滤）看到 3 条，VEN-C 那条对 vendor2 不可见。
     * 域都在种子那 7 个域内 → 域档账号（pm/dev/qa/tl）看到全部 12 条。</p>
     */
    private static final String[][] REQUIREMENTS = {
            {"DEMO-R-01", "BUS", "", "ORD", "supplyOrg", "po1", "", "", "采购订单协同（业务提报）",
                    "P1", "POOLED", "20", "-12", "8", "1"},
            {"DEMO-R-02", "PDR", "DEMO-R-01", "ORD", "supplyOrg", "pm", "po1", "",
                    "采购订单移动审批", "P1", "SCHEDULED", "8", "-10", "4", "1"},
            {"DEMO-R-03", "RD", "DEMO-R-02", "ORD", "supplyOrg", "pm", "dev1", "",
                    "订单审批接口", "P1", "IN_DEV", "3", "-6", "3", "1"},
            {"DEMO-R-04", "RD", "DEMO-R-02", "ORD", "supplyOrg", "pm", "dev2", "",
                    "订单审批页面", "P2", "PENDING_TEST", "2", "-5", "2", "1"},
            {"DEMO-R-05", "PDR", "DEMO-R-01", "INV", "supplyOrg", "pm", "po1", "",
                    "库存预警规则配置", "P2", "IN_DEV", "5", "-8", "6", "1"},
            {"DEMO-R-06", "RD", "DEMO-R-05", "INV", "supplyOrg", "pm", "dev1", "",
                    "库存预警定时任务", "P2", "IN_DEV", "4", "-4", "7", "1"},
            {"DEMO-R-07", "PDR", "DEMO-R-01", "LOG", "supplyOrg", "pm", "po1", "",
                    "到货签收无纸化", "P2", "DELIVERED", "6", "-14", "5", "1"},
            {"DEMO-R-08", "RD", "DEMO-R-07", "LOG", "supplyOrg", "pm", "dev2", "",
                    "签收回执生成", "P3", "DONE", "2", "-10", "-2", "1"},
            {"DEMO-R-09", "PDR", "", "ORD", "itOrg", "pm", "", "VEN-A",
                    "外协订单对账（VEN-A 承接）", "P2", "IN_DEV", "5", "-6", "9", "0"},
            {"DEMO-R-10", "RD", "DEMO-R-09", "ORD", "itOrg", "pm", "", "VEN-A",
                    "对账单导出", "P2", "PENDING_TEST", "3", "-3", "10", "0"},
            {"DEMO-R-11", "RD", "DEMO-R-09", "SUP", "itOrg", "pm", "", "VEN-A",
                    "供应商对账提醒", "P3", "IN_DEV", "2", "-2", "12", "0"},
            {"DEMO-R-12", "RD", "DEMO-R-09", "MDM", "itOrg", "pm", "", "VEN-C",
                    "主数据清洗脚本（VEN-C 承接）", "P3", "TODO", "2", "1", "14", "0"},
    };

    /**
     * 需求行；父需求用 code 表达（父行总在子行之前），由 {@link DemoResetService} 在插入时
     * 换成真实 id —— 既不引入第二套 id 常量，也不需要先插父后回填的第二次遍历。
     */
    public static List<Object[]> requirementRows(long tenantId, Map<String, Long> refs) {
        LocalDate today = LocalDate.now();
        List<Object[]> rows = new ArrayList<>(REQUIREMENTS.length);
        for (String[] spec : REQUIREMENTS) {
            Long domainId = ref(refs, "domain:" + spec[3]);
            Long orgId = ref(refs, spec[4]);
            Long reporterId = ref(refs, "user:" + spec[5]);
            Long ownerId = spec[6].isEmpty() ? null : ref(refs, "user:" + spec[6]);
            Long vendorId = spec[7].isEmpty() ? null : ref(refs, "vendor:" + spec[7]);
            rows.add(new Object[]{
                    tenantId,
                    spec[0],
                    spec[1],
                    spec[2],
                    domainId,
                    orgId,
                    reporterId,
                    ownerId,
                    vendorId,
                    spec[8],
                    spec[9],
                    spec[10],
                    Integer.valueOf(spec[11]),
                    today.plusDays(Integer.parseInt(spec[12])),
                    today.plusDays(Integer.parseInt(spec[13])),
                    "1".equals(spec[14])});
        }
        return rows;
    }

    // ---------------------------------------------------------------- 用例 / 缺陷 / 任务 / 通知

    /** code, requirementCode, title, type, priority。 */
    private static final String[][] CASES = {
            {"DEMO-C-01", "DEMO-R-03", "订单审批接口-通过", "API", "P1"},
            {"DEMO-C-02", "DEMO-R-03", "订单审批接口-驳回", "API", "P1"},
            {"DEMO-C-03", "DEMO-R-04", "审批页面-待办渲染", "UI", "P2"},
            {"DEMO-C-04", "DEMO-R-04", "审批页面-移动端适配", "UI", "P2"},
            {"DEMO-C-05", "DEMO-R-06", "库存预警-阈值触发", "FUNC", "P2"},
            {"DEMO-C-06", "DEMO-R-06", "库存预警-重复告警抑制", "FUNC", "P2"},
            {"DEMO-C-07", "DEMO-R-08", "签收回执-生成 PDF", "FUNC", "P3"},
            {"DEMO-C-08", "DEMO-R-08", "签收回执-补签", "FUNC", "P3"},
            {"DEMO-C-09", "DEMO-R-10", "对账单导出-字段完整", "API", "P2"},
            {"DEMO-C-10", "DEMO-R-10", "对账单导出-大数据量分页", "API", "P2"},
            {"DEMO-C-11", "DEMO-R-11", "对账提醒-供应商可见性", "FUNC", "P3"},
            {"DEMO-C-12", "DEMO-R-12", "主数据清洗-幂等重跑", "FUNC", "P3"},
    };

    public static List<Object[]> caseRows(Map<String, Long> refs) {
        List<Object[]> rows = new ArrayList<>(CASES.length);
        for (String[] spec : CASES) {
            rows.add(new Object[]{
                    spec[0], spec[1], spec[2], spec[3], spec[4],
                    ref(refs, "domain:ORD"), ref(refs, "module")});
        }
        return rows;
    }

    /** code, requirementCode, title, severity, priority, status, domain, handler。 */
    private static final String[][] DEFECTS = {
            {"DEMO-D-01", "DEMO-R-03", "审批通过后状态未流转", "P1", "P1", "IN_PROGRESS", "ORD", "dev1"},
            {"DEMO-D-02", "DEMO-R-10", "对账单金额精度丢失", "P2", "P2", "NEW", "ORD", ""},
            {"DEMO-D-03", "DEMO-R-06", "预警阈值边界漏判", "P2", "P2", "CLOSED", "INV", "dev2"},
    };

    public static List<Object[]> defectRows(Map<String, Long> refs) {
        List<Object[]> rows = new ArrayList<>(DEFECTS.length);
        for (String[] spec : DEFECTS) {
            rows.add(new Object[]{
                    spec[0], spec[1], spec[2], spec[3], spec[4], spec[5],
                    ref(refs, "domain:" + spec[6]),
                    spec[7].isEmpty() ? null : ref(refs, "user:" + spec[7])});
        }
        return rows;
    }

    /** requirementCode, title, assignee, estimateHours, startOffset, dueOffset, status。 */
    private static final String[][] TASKS = {
            {"DEMO-R-01", "订单协同拆分与排期", "pm", "8", "-12", "4", "DONE"},
            {"DEMO-R-02", "审批流程节点配置", "po1", "6", "-10", "5", "DOING"},
            {"DEMO-R-03", "审批接口联调", "dev1", "16", "-6", "6", "DOING"},
            {"DEMO-R-04", "审批页面自测", "dev2", "12", "-5", "7", "TODO"},
            {"DEMO-R-05", "预警规则建模", "po1", "8", "-8", "8", "TODO"},
            {"DEMO-R-06", "定时任务调度接入", "dev1", "16", "-4", "9", "TODO"},
            {"DEMO-R-07", "签收单模板评审", "po1", "4", "-14", "10", "TODO"},
            {"DEMO-R-08", "回执生成脚本", "dev2", "10", "-10", "11", "DONE"},
    };

    public static List<Object[]> taskRows(Map<String, Long> refs) {
        LocalDate today = LocalDate.now();
        List<Object[]> rows = new ArrayList<>(TASKS.length);
        for (String[] spec : TASKS) {
            rows.add(new Object[]{
                    spec[0], spec[1], ref(refs, "user:" + spec[2]),
                    Integer.valueOf(spec[3]),
                    today.plusDays(Integer.parseInt(spec[4])),
                    today.plusDays(Integer.parseInt(spec[5])),
                    spec[6]});
        }
        return rows;
    }

    /** username, type, title, content, bizType。 */
    private static final String[][] NOTIFICATIONS = {
            {"pm", "SYSTEM", "演示数据已重建", "需求 12 条 / 版本 1 个 / 用例 12 条，可用于演示。", "DEMO"},
            {"po1", "REQUIREMENT", "本企业 8 条需求已就绪", "需求池只显示本企业提报的 8 条，厂商承接的 4 条不可见。", "REQUIREMENT"},
            {"qa1", "TEST_PLAN", "演示回归计划待执行", "计划已挂 10 条用例，可演示轮次与准出。", "TEST_PLAN"},
            {"dev1", "DEFECT", "2 个缺陷待处理", "DEMO-D-01 处理中、DEMO-D-02 待认领。", "DEFECT"},
    };

    public static List<Object[]> notificationRows(Map<String, Long> refs) {
        List<Object[]> rows = new ArrayList<>(NOTIFICATIONS.length);
        for (String[] spec : NOTIFICATIONS) {
            rows.add(new Object[]{ref(refs, "user:" + spec[0]), spec[1], spec[2], spec[3], spec[4]});
        }
        return rows;
    }

    private static Long ref(Map<String, Long> refs, String key) {
        Long value = refs.get(key);
        if (value == null) {
            throw new IllegalStateException("演示数据集缺少引用：" + key + "（先执行 platform-seed）");
        }
        return value;
    }
}
