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
    public static final long PO1_VISIBLE = 8;
    public static final long VENDOR_A_VISIBLE = 3;
    public static final long VENDOR_B_VISIBLE = 0;

    /**
     * 删除顺序：子表 → 父表，一次一条 {@code DELETE ... WHERE tenant_id = ?}。
     *
     * <p>只删业务实例数据；账号/角色/域/厂商/组织/流程定义/指标定义/字典/测试策略/环境台账/
     * 集成配置等定义数据保留（AC-13「保留」清单）。{@code audit_log} 也不删：它是只追加的审计轨迹，
     * 删掉既毁证据链，也会和「按时间窗对比审计增量」的既有断言拧着来。列表按 V1–V9 的引用拓扑排：
     * 先清评审、报告、门禁、计划轮次，再清用例/缺陷/审批，最后才动需求与版本。</p>
     *
     * <p>租户级定义/配置表不进这张表——{@code branch_policy}（V9 ⑤ 种子 1 行）、
     * {@code test_executor}（V9 ⑤ 种子 2 行：STUB-LOCAL / LEGACY-OFF）、{@code api_client}
     * （集成配置）只被 {@code SeedService} / 迁移种下，本夹具不会重建它们：一旦删掉又没人重放，
     * 演示页（/code-policies、/test-executors）就是空白，而重置脚本还在说「可直接演示」（D-02）。
     * 它们只引用租户/域，不引用本夹具删掉的版本或需求，保留不会留下悬空引用。
     * {@code branch_policy_check} 是核验记录（实例），照常清。</p>
     */
    public static final List<String> DELETE_ORDER = List.of(
            "test_case_review",
            "report_signoff",
            "test_report_version",
            "test_report",
            "quality_gate_waiver",
            "quality_gate_snapshot",
            "quality_gate_rule",
            "quality_gate",
            "release_review",
            "plan_change_log",
            "plan_assignment",
            "test_plan_round",
            "test_execution",
            "test_plan_case",
            "test_plan",
            "mr_comment",
            "mr_sync",
            "build_record",
            "artifact_record",
            "integration_sync_log",
            "api_call_log",
            "branch_policy_check",
            "test_case_step",
            "test_case_version",
            "test_case",
            "defect_history",
            "defect",
            "wf_cc",
            "wf_history",
            "wf_task",
            "wf_instance",
            "deploy_order_requirement",
            "deploy_order",
            "release_requirement",
            "milestone",
            "task",
            "sprint",
            "attachment",
            "requirement_domain_rel",
            "requirement",
            "`release`",
            "notification");

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
