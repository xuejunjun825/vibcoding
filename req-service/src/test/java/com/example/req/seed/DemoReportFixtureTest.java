package com.example.req.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.example.req.seed.DemoFixture.ReportFixture;

/**
 * r16 演示报表的静态契约：清表后回填哪两张、长什么样、怎么插。
 *
 * <p>为什么钉在单测里：{@code report_definition} 的整表 purge（{@link DemoFixture#DELETE_ORDER}）
 * 与回填（{@link DemoFixture#REPORTS} / {@link DemoFixture#reportRows}）现在同源，但两者的
 * 一致性没有编译期约束——名称改一个字节、度量 code 拼错、{@code scope} 从 PRIVATE 滑回 TENANT，
 * 都要到「起了栈跑重置脚本」才暴露。这里是唯一能在 {@code make platform-unit} 里就拦住它们的闸门。</p>
 *
 * <p>断言口径刻意是<b>字面量</b>：{@link DemoFixture#REPORTS} 是对外契约（脚本 {@code DEMO_REPORT_NAMES}
 * 与 deliver 的 run 接口都按这些字符串匹配），用例不能反过来「读常量再和自己比」——那样改坏常量
 * 测试仍然绿。JSON 同理：只校验「两次调用字节相等」挡不住键名从一开始就写错，所以同时钉住完整串。</p>
 *
 * <p>纯常量测试，不起 Spring、不连库：重置链路的运行时行为由
 * {@code scripts/platform-demo-reset.sh}（DB 计数 + 名称集合 + run/export）覆盖。</p>
 */
class DemoReportFixtureTest {

    /** {@link DemoFixture#reportRows} 的列下标，与 {@code DemoResetService} 的 INSERT 列顺序逐项对应。 */
    private static final int COL_TENANT = 0;
    private static final int COL_NAME = 1;
    private static final int COL_DATASET = 2;
    private static final int COL_DEFINITION = 3;
    private static final int COL_CHART = 4;
    private static final int COL_OWNER = 5;
    private static final int COL_OWNER_ROLE = 6;
    private static final int COL_SCOPE = 7;
    private static final int COL_SCOPE_REF = 8;
    private static final int COL_STATUS = 9;
    private static final int COL_CREATED_BY = 10;
    private static final int COL_UPDATED_BY = 11;
    private static final int COL_VERSION = 12;

    private static final long TENANT = 1L;
    private static final long ADMIN_ID = 42L;

    private static ReportFixture report(String name) {
        return DemoFixture.REPORTS.stream()
                .filter(it -> it.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("演示报表 '" + name + "' 不在 REPORTS 里"));
    }

    /** AC-1/AC-6：恰好两张、名称固定、集合与 {@link DemoFixture#REPORT_NAMES} 同源且无重复。 */
    @Test
    void twoReportsWithFixedNames() {
        assertEquals(2, DemoFixture.REPORT_COUNT,
                "重置后 report_definition 应为 2 张演示报表（AC-1）");
        assertEquals(2, DemoFixture.REPORTS.size(), "REPORTS 与 REPORT_COUNT 必须同源");
        assertEquals(List.of("需求状态分布", "厂商交付概览"),
                DemoFixture.REPORTS.stream().map(ReportFixture::name).toList(),
                "两张演示报表的名称与顺序是脚本/Deliver 匹配的契约，不能随手改");
        assertEquals(new TreeSet<>(List.of("需求状态分布", "厂商交付概览")),
                new TreeSet<>(DemoFixture.REPORT_NAMES),
                "REPORT_NAMES 由 REPORTS 派生，集合必须一致（排序无关：中文不可依赖 collation）");
        assertEquals(2, new TreeSet<>(DemoFixture.REPORT_NAMES).size(),
                "名称必须互不重复，否则 owner_id+name+active_flag 唯一键会撞");
        assertTrue(DemoFixture.REPORTS.stream().allMatch(it -> "TABLE".equals(it.chartType())),
                "两张都是交叉表：图表类型 TABLE 才走 run 的 total/rowCodes 通道（AC-3）");
    }

    /** AC-2：A 表是 域 × 状态 的二维交叉表，两个度量（需求数 + 平均交付周期）。 */
    @Test
    void statusReportShape() {
        ReportFixture fixture = report("需求状态分布");
        assertEquals("requirement", fixture.dataset(),
                "数据源必须是 requirement：分析域四页共享这一套数据集 code");
        assertEquals(List.of("domain"), fixture.rows(), "行维度只有域（域×厂商隔离的域）");
        assertEquals(List.of("status"), fixture.columns(), "列维度是状态——A 是二维交叉表");
        assertEquals(List.of("req.total", "cycle.avg"), fixture.measures(),
                "需求数 + 平均交付周期：演示时打开就能出交叉表与图表");
        assertEquals("TABLE", fixture.chartType(), "AC-3 的 run 断言按 TABLE 取 total.values");
    }

    /** AC-2：B 表是单维三度量，人天类度量只在 requirement 数据集注册（不复刻 deliver 白名单）。 */
    @Test
    void vendorReportShape() {
        ReportFixture fixture = report("厂商交付概览");
        assertEquals("requirement", fixture.dataset(),
                "人天类度量只在 deliver 的 AnalyticsDatasets:177-187 注册，B 必须挂 requirement");
        assertEquals(List.of("vendor"), fixture.rows(), "行维度是厂商");
        assertEquals(List.of(), fixture.columns(),
                "B 是单维表：空 columns，不是写成 [\"\"] 或 null（codec 会当成未知维度）");
        assertEquals(List.of("req.total", "effort.estimate", "effort.deviation"), fixture.measures(),
                "需求数 + 预估人天 + 人天偏差——人天偏差只有 0 值是已知取舍（AC-3 只要求非全空）");
        assertEquals("TABLE", fixture.chartType(), "与 A 同走 TABLE 通道");
    }

    /**
     * AC-6：{@code definition_json} 固定键序、{@code filters} 恒为 {@code []}、两次调用逐字节相等。
     *
     * <p>键名写错不会静默：deliver 的 {@code ReportDefinitionCodec.read} 会以 400
     * （{@code REPORT_UNKNOWN_FIELD}）拒绝，脚本的 run 断言就是它的探针。但字节稳定必须在这里钉——
     * 「两次重置的 JSON 相等」是幂等性的可观测形态之一。</p>
     */
    @Test
    void definitionJsonShapeAndStableBytes() {
        for (ReportFixture fixture : DemoFixture.REPORTS) {
            String first = fixture.definitionJson();
            String second = fixture.definitionJson();
            assertEquals(first, second,
                    "'" + fixture.name() + "' 两次序列化必须逐字节相等（固定键序、无时间戳/随机量）");

            // 5 个键按固定顺序出现，且 filters 永远是空数组。
            int datasetAt = first.indexOf("\"datasetId\":");
            int rowsAt = first.indexOf("\"rows\":");
            int columnsAt = first.indexOf("\"columns\":");
            int measuresAt = first.indexOf("\"measures\":");
            int filtersAt = first.indexOf("\"filters\":");
            assertTrue(first.startsWith("{\"datasetId\":"),
                    "'" + fixture.name() + "' 的 JSON 必须以 datasetId 开头：" + first);
            assertTrue(datasetAt < rowsAt && rowsAt < columnsAt && columnsAt < measuresAt
                            && measuresAt < filtersAt,
                    "'" + fixture.name() + "' 的键序必须是 datasetId,rows,columns,measures,filters：" + first);
            assertTrue(first.endsWith("\"filters\":[]}"),
                    "'" + fixture.name() + "' 的 filters 必须是空数组（不吃任何预设筛选）：" + first);
            assertFalse(first.contains(" "),
                    "'" + fixture.name() + "' 不输出空白字符，字节稳定不依赖格式化：" + first);
        }

        assertEquals("{\"datasetId\":\"requirement\",\"rows\":[\"domain\"],\"columns\":[\"status\"],"
                        + "\"measures\":[\"req.total\",\"cycle.avg\"],\"filters\":[]}",
                report("需求状态分布").definitionJson(),
                "A 表的完整 JSON 是 deliver codec 的输入契约，字面量钉死");
        assertEquals("{\"datasetId\":\"requirement\",\"rows\":[\"vendor\"],\"columns\":[],"
                        + "\"measures\":[\"req.total\",\"effort.estimate\",\"effort.deviation\"],"
                        + "\"filters\":[]}",
                report("厂商交付概览").definitionJson(),
                "B 表的完整 JSON：空 columns 序列化为 []，不能省成 null");
    }

    /**
     * AC-2/AC-4：回填行是 PRIVATE + 属主 admin，{@code scope_ref} 留空（不 pin 门户）。
     *
     * <p>为什么必须是 admin 的私有表：演示账号自己新建的表就是这个形态；PRIVATE 保证别的演示账号
     * 看不到、改不了，重置后列表里不会混进别人的表。</p>
     */
    @Test
    void reportRowsCarryAdminOwnerAndPrivateScope() {
        List<Object[]> rows = DemoFixture.reportRows(TENANT, Map.of("user:admin", ADMIN_ID));

        assertEquals(DemoFixture.REPORT_COUNT, rows.size(),
                "回填行数必须等于 REPORT_COUNT，脚本的 reports 计数断言与之对齐");
        for (int i = 0; i < rows.size(); i++) {
            Object[] row = rows.get(i);
            ReportFixture expected = DemoFixture.REPORTS.get(i);
            assertEquals(COL_VERSION + 1, row.length,
                    "INSERT 列数必须与 DemoResetService 的 13 个占位符一致：" + expected.name());
            assertEquals(TENANT, row[COL_TENANT], "租户必须显式写入，多租户隔离的前提");
            assertEquals(expected.name(), row[COL_NAME], "名称与 REPORTS 同序");
            assertEquals(expected.dataset(), row[COL_DATASET], "数据集与 REPORTS 同序");
            assertEquals(expected.definitionJson(), row[COL_DEFINITION],
                    "definition_json 必须走 definitionJson()，不能在插入处另拼一份");
            assertEquals(expected.chartType(), row[COL_CHART], "图表类型与 REPORTS 同序");
            assertEquals(ADMIN_ID, row[COL_OWNER], "属主必须是 user:admin（AC-2）");
            assertEquals("admin", row[COL_OWNER_ROLE], "owner_role 与 owner 一致，否则前端按角色过滤会漏");
            assertEquals("PRIVATE", row[COL_SCOPE], "两张都是私有表：不 pin 门户、别人看不到（AC-2）");
            assertNull(row[COL_SCOPE_REF], "PRIVATE 表不 pin 门户，scope_ref 必须为 NULL");
            assertEquals("ACTIVE", row[COL_STATUS], "status=ACTIVE 才会出现在报表列表里");
            assertEquals(ADMIN_ID, row[COL_CREATED_BY], "created_by 记 admin");
            assertEquals(ADMIN_ID, row[COL_UPDATED_BY], "updated_by 记 admin");
            assertEquals(1, row[COL_VERSION], "version 从 1 起，与迁移种下的行同口径");
        }

        Set<String> names = new TreeSet<>();
        rows.forEach(row -> names.add((String) row[COL_NAME]));
        assertEquals(new TreeSet<>(DemoFixture.REPORT_NAMES), names,
                "回填名称集合必须与不变量断言的 REPORT_NAMES 完全一致");
    }

    /**
     * AC-6 的失败面：缺 {@code user:admin} 时抛 {@link IllegalStateException}，而不是插出空属主。
     *
     * <p>{@code owner_id} 是 NOT NULL，静默插入会炸成 500；{@code DemoResetService.references()}
     * 已把 {@code user:admin} 列为必需引用，缺种子时先给 409。这里守住第二道：即使有人绕过引用自检
     * 直接调 {@code reportRows}，也必须得到一条能定位到根因的消息。</p>
     */
    @Test
    void reportRowsFailWithoutAdminRef() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> DemoFixture.reportRows(TENANT, Map.of("user:po1", 1L)),
                "缺少 user:admin 时必须显式失败，不能插出空属主");
        assertTrue(error.getMessage().contains("user:admin"),
                "异常消息要指出缺哪个引用，否则只看到 500 无法定位：" + error.getMessage());
    }
}
