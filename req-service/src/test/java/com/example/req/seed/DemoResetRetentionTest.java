package com.example.req.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.example.req.seed.DemoFixture.TableReset;

/**
 * {@link DemoFixture#DELETE_ORDER} 的契约：删哪些表、留哪些行都由这一处决定。
 *
 * <p>为什么要有这组静态断言：重置只重建「12 需求小链路」，不会重放 V1/V9/V10 的迁移种子，
 * 所以「删了不重建」的表一定会让页面变空白（D-02 的故障形态：删掉了却没人报错）。
 * 但保留不等于「不进序列」——r15 起，迁移种子表（{@code test_executor}）进序列并带
 * {@code keepFilter}，只清掉门禁探针行；{@code api_client} 无任何种子，反而应当全清。
 * 运行时断言在 {@code scripts/platform-demo-reset.sh}（需起栈，不进自动门禁），这里把
 * 「必须删 / 必须留 / 留的怎么留」钉在 {@code make platform-unit} 里。</p>
 *
 * <p>{@code DELETE_ORDER} 的类型从 {@code List<String>} 收紧为 {@code List<TableReset>} 之后，
 * {@code contains("table")} 不再编译——这正是契约被收紧的信号：要问表名必须先经过
 * {@link #tables()}，保留语义必须显式读 {@code keepFilter}，不能顺手把表名当字符串比较。</p>
 */
class DemoResetRetentionTest {

    /**
     * 租户级定义/种子表：由迁移或 {@code SeedService} 种下，reset 从不重建。
     *
     * <p>{@code test_executor} 不在此列——它进序列，靠 {@code keepFilter} 保留两行种子；
     * 这就是「保留」与「不删」两种口径的区别，也是 r15 之前两套实现打架的地方。
     * {@code test_module} 同样不在此列（r15-fix）：它进序列，靠 {@code purgeRunTagResidue}
     * 只清门禁 RUN_TAG 探针行、种子模块树保留——「整表不进序列」挡不住按行命名的残留。</p>
     */
    private static final List<String> DEFINITION_TABLES = List.of(
            "branch_policy",       // V9 ⑤：每租户 1 条演示分支策略
            "wiki_page",           // V9：种子 wiki 页（探针页另案，本轮不清）
            "metric_definition",   // V1：指标定义
            "sys_user"             // V1：12 个演示账号
    );

    @Test
    void deletionListKeepsDefinitionTables() {
        for (String table : DEFINITION_TABLES) {
            assertFalse(tables().contains(table),
                    table + " is definition/seed data: reset deletes it but never replays the seed, "
                            + "so the demo page goes blank (D-02)");
        }
    }

    @Test
    void deletionListStillCoversBusinessInstances() {
        // 反向守卫：别为了修 D-02 把整个列表清空——业务实例表必须仍在删除序列里。
        for (String table : List.of(
                "requirement", "`release`", "test_case", "test_plan", "defect",
                "test_execution", "branch_policy_check", "quality_gate_snapshot")) {
            assertTrue(tables().contains(table),
                    table + " is business instance data and must be cleared by demo-reset");
        }
        // r15 补入的 4 张漏删表同样必须在序列里（其中 executor 靠过滤保留种子，见下一条测试）。
        for (String table : List.of(
                "report_definition", "portal_card_config", "test_executor", "api_client")) {
            assertTrue(tables().contains(table),
                    table + " polluted the demo pages and must be part of the reset sequence (r15)");
        }
        // r15-fix（QA D-01/D-02）：派生实例与门禁残留所在的表也必须在序列里，且必须是「带过滤保留」。
        for (String table : DemoFixture.RUN_TAG_RESIDUE_TABLES) {
            assertTrue(tables().contains(table),
                    table + " keeps gate RUN_TAG rows; leaving it out of the sequence is what D-02 reported");
        }
        assertTrue(tables().contains("test_strategy"),
                "test_strategy is derived from release (V5 backfill): once release is gone it is pure orphan");
    }

    @Test
    void runTagResidueIsTrimmedNotDropped() {
        // 「不进序列」是表的粒度，而门禁残留是行的粒度：platform-verify.sh 用 verify-wf-<hhmmss>
        // 在种子表里留一行，整表不进序列挡不住它。这里钉住三张表都带残留过滤，且过滤谓词与
        // counts() 统计残留用的是同一份常量（两处各写一套就会删一套、报另一套）。
        for (String table : DemoFixture.RUN_TAG_RESIDUE_TABLES) {
            TableReset reset = only(table);
            String filter = reset.keepFilter();
            assertNotNull(filter, table + " must trim gate RUN_TAG rows only (seed rows have to survive)");
            // keepFilter 是「保留」谓词，所以必须是残留模式的取反：NOT (residue)。
            // 直接传残留模式会把种子删掉、把探针留下（实测 409：残留 476 行仍在）。
            assertTrue(filter.startsWith("NOT (") && filter.contains(DemoFixture.GATE_RUN_TAG_RESIDUE),
                    table + " keepFilter must be the negation of the gate residue pattern, actual: " + filter);
        }
        String filter = DemoFixture.GATE_RUN_TAG_RESIDUE;
        for (String prefix : List.of("verify-%", "neg-%", "验证模块%", "QA环境-%")) {
            assertTrue(filter.contains(prefix),
                    "GATE_RUN_TAG_RESIDUE must cover the gate naming convention, missing: " + prefix);
        }
        // 派生表相反：test_strategy 没有种子行可按，必须无过滤整表清空。
        assertNull(only("test_strategy").keepFilter(),
                "test_strategy has no seed rows: a keepFilter would leave release orphans behind");
        // 子先父后：test_strategy 引用 release，必须排在它之前（没有 FK，顺序错只会静默留孤儿）。
        assertTrue(tables().indexOf("test_strategy") < tables().indexOf("`release`"),
                "test_strategy must be deleted before `release` (child before parent)");
    }

    @Test
    void deletionListCarriesKeepFilters() {
        // 迁移种子行必须留下：V9 ⑤ 的 STUB-LOCAL / LEGACY-OFF 删了没人重放，/test-executors 会空。
        TableReset executor = only("test_executor");
        assertNotNull(executor.keepFilter(),
                "test_executor rows are migration seeds, not fixture rows: drop them and the page "
                        + "stays blank after reset — it needs a keepFilter");
        assertTrue(executor.keepFilter().contains("STUB-LOCAL")
                        && executor.keepFilter().contains("LEGACY-OFF"),
                "test_executor keepFilter must name both V9 seed codes, actual: " + executor.keepFilter());

        // 反向：没有种子可保的表必须无过滤全清，否则残留会再次污染（api_client 的 60 条 CI 行）。
        for (String table : List.of("api_client", "report_definition", "portal_card_config")) {
            assertNull(only(table).keepFilter(),
                    table + " has no seed rows: a keepFilter would leave CI/manual residue behind (r15)");
        }

        // 一个表名只允许出现一次，否则两次 DELETE 的先后会让过滤语义变得不可推理。
        assertEquals(tables().size(), tables().stream().distinct().count(),
                "DELETE_ORDER contains duplicate tables");
    }

    @Test
    void keptExecutorBaselineIsRestored() {
        // keepFilter 只保住行、不保住字段：s7 会把 STUB-LOCAL 的 last_report_at 改成 NOW，
        // 行留下来但 NEVER 没了。重置必须把这几列也还原，否则演示台账永远停在上一轮上报之后。
        String sql = DemoFixture.RESTORE_KEPT_EXECUTOR_BASELINE;
        assertTrue(sql.contains("last_report_at = NULL"),
                "kept executors must go back to 'never reported', actual SQL: " + sql);
        assertTrue(sql.contains("report_count = 0"),
                "kept executors must drop the probe report counter, actual SQL: " + sql);
        assertTrue(sql.contains("'STUB-LOCAL'") && sql.contains("'LEGACY-OFF'"),
                "baseline restore must name both V9 seed codes, actual SQL: " + sql);
        assertTrue(sql.contains("tenant_id = ?"),
                "baseline restore must stay tenant-scoped like every other reset statement, actual SQL: " + sql);
    }

    /** 唯一的删除序列来源；测试不另建表清单。 */
    private static List<TableReset> resets() {
        return DemoFixture.DELETE_ORDER;
    }

    /** 表名视图——类型收紧后不再能直接 {@code contains("x")}，必须显式穿过这一层。 */
    private static List<String> tables() {
        return resets().stream().map(TableReset::table).collect(Collectors.toList());
    }

    private static TableReset only(String table) {
        List<TableReset> hits = resets().stream()
                .filter(reset -> table.equals(reset.table()))
                .toList();
        assertEquals(1, hits.size(), table + " must appear exactly once in DELETE_ORDER");
        return hits.get(0);
    }
}
