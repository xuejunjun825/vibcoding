package com.example.req.seed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link DemoFixture#DELETE_ORDER} 的边界：只删业务实例表，租户级定义/配置表一个都不许进。
 *
 * <p>为什么要有这条静态契约测试：重置只重建「12 需求小链路」，不会重放 V9 ⑤ 的协作域种子，
 * 所以把 {@code branch_policy} / {@code test_executor} / {@code api_client} 写进删除列表后，
 * 重置脚本照样打印「演示数据已重建」，但 /code-policies 与 /test-executors 已经是空页面
 * （D-02 的故障形态：删了不重建，页面空白却没人报错）。运行时断言在
 * {@code scripts/platform-demo-reset.sh}（需起栈，不进自动门禁），这里把「不能删」钉在
 * 单元测试里，{@code make platform-unit} 就会红。</p>
 *
 * <p>新增删除项时先问一句：夹具 {@code DemoResetService#insertFixture} 会重建它吗？
 * 不会 → 要么补重放，要么它本来就属定义数据、不该删。</p>
 */
class DemoResetRetentionTest {

    /** 租户级定义/配置表：由迁移或 SeedService 种下，reset 从不重建。 */
    private static final List<String> DEFINITION_TABLES = List.of(
            "branch_policy",      // V9 ⑤：每租户 1 条演示分支策略
            "test_executor",      // V9 ⑤：STUB-LOCAL / LEGACY-OFF 两条执行器
            "api_client"          // 集成配置（管理员创建，重置无权销毁）
    );

    @Test
    void deletionListKeepsDefinitionTables() {
        for (String table : DEFINITION_TABLES) {
            assertFalse(DemoFixture.DELETE_ORDER.contains(table),
                    table + " is definition/config data: reset deletes it but never replays the seed, "
                            + "so the demo page (/code-policies, /test-executors) goes blank (D-02)");
        }
    }

    @Test
    void deletionListStillCoversBusinessInstances() {
        // 反向守卫：别为了修 D-02 把整个列表清空——业务实例表必须仍在删除序列里。
        for (String table : List.of(
                "requirement", "`release`", "test_case", "test_plan", "defect",
                "test_execution", "branch_policy_check", "quality_gate_snapshot")) {
            assertTrue(DemoFixture.DELETE_ORDER.contains(table),
                    table + " is business instance data and must be cleared by demo-reset");
        }
    }
}
