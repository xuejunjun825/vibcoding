package com.example.deliver.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * r14 AC-1 / AC-5 / AC-10：分析域 code→中文注册表的行为锁。
 *
 * <p>纯函数，不需要 JdbcTemplate：注册表是静态常量表，这里钉死两件最容易回退的事——
 * 兜底文案（未登记枚举码必须变「未知状态」而不是漏出裸码）与逐段翻译的拼接形状。</p>
 */
class AnalyticsLabelsTest {

    /** 与门禁脚本 i18n-live.mjs / i18n-labels-consistency.mjs 同一条判据。 */
    private static final Pattern ENUM_TOKEN = Pattern.compile("\\b[A-Z]{3,}(_[A-Z]+)*\\b");

    @Test
    @DisplayName("四数据集的每个枚举取值都有中文登记，且无重复键")
    void everyEnumValueIsRegistered() {
        String[][] expected = {
                // dataset, field, code（与 sys_dict 种子、dict.ts 的取值集合一致）
                {"requirement", "status", "SUBMITTED"},
                {"requirement", "status", "APPROVING"},
                {"requirement", "status", "PENDING_REVIEW"},
                {"requirement", "status", "POOLED"},
                {"requirement", "status", "PENDING_ANALYSIS"},
                {"requirement", "status", "SCHEDULED"},
                {"requirement", "status", "IN_DEV"},
                {"requirement", "status", "DELIVERED"},
                {"requirement", "status", "ACCEPTED_CLOSED"},
                {"requirement", "status", "TODO"},
                {"requirement", "status", "PENDING_TEST"},
                {"requirement", "status", "TEST_PASSED"},
                {"requirement", "status", "MERGED"},
                {"requirement", "status", "DONE"},
                {"requirement", "status", "CLOSED"},
                {"requirement", "status", "CANCELLED"},
                {"requirement", "type", "BUS"},
                {"requirement", "type", "PDR"},
                {"requirement", "type", "RD"},
                {"requirement", "priority", "P0"},
                {"requirement", "priority", "P1"},
                {"requirement", "priority", "P2"},
                {"requirement", "priority", "P3"},
                {"quality", "severity", "P0"},
                {"quality", "severity", "P1"},
                {"quality", "severity", "P2"},
                {"quality", "severity", "P3"},
                {"approval", "result", "PENDING"},
                {"approval", "result", "RUNNING"},
                {"approval", "result", "APPROVED"},
                {"approval", "result", "REJECTED"},
                {"approval", "result", "WITHDRAWN"},
                {"approval", "result", "CANCELLED"},
                {"approval", "result", "SKIPPED"},
                {"approval", "bizType", "REQ_SUBMIT"},
                {"approval", "bizType", "REQ_REVIEW"},
        };
        for (String[] row : expected) {
            String label = AnalyticsLabels.label(row[0], row[1], row[2]);
            assertNotNull(label, row[0] + '.' + row[1] + '=' + row[2] + " 未登记");
            assertTrue(AnalyticsLabels.isRegistered(row[0], row[1], row[2]));
            assertFalse(ENUM_TOKEN.matcher(label).find(),
                    row[0] + '.' + row[1] + '=' + row[2] + " 的中文文案里还有英文枚举：" + label);
        }
        // 交付/质量的 status 复用需求状态表（不复制三份），别让它漂移成未登记
        assertEquals("已提报", AnalyticsLabels.label("delivery", "status", "SUBMITTED"));
        assertEquals("开发中", AnalyticsLabels.label("quality", "status", "IN_DEV"));
        // 同码不同义：P0 在需求优先级是「紧急」，在缺陷等级是「致命」
        assertEquals("紧急", AnalyticsLabels.label("requirement", "priority", "P0"));
        assertEquals("致命", AnalyticsLabels.label("quality", "severity", "P0"));
    }

    @Test
    @DisplayName("未登记枚举码 → 未知状态；空值 → 空串；中文与时间桶原样透传")
    void displayFallsBackToUnknownWithoutChangingShape() {
        assertEquals(AnalyticsLabels.UNKNOWN,
                AnalyticsLabels.display("requirement", "status", "SOMETHING_NEW"));
        // 未登记但不是枚举形状（时间桶、占位文案、节点名）不能被误判成「未知状态」
        assertEquals("2026-11", AnalyticsLabels.display("requirement", "month", "2026-11"));
        assertEquals("未关联计划", AnalyticsLabels.display("quality", "plan", "未关联计划"));
        assertEquals("需求评审", AnalyticsLabels.display("approval", "node", "需求评审"));
        // 已经由 SQL CASE 译成中文的 bizType 必须透传，不能二次翻译
        assertEquals("评审入池", AnalyticsLabels.display("approval", "bizType", "评审入池"));
        // 空值保持 r13 行为：空串而不是占位符（不改列数、不新增行）
        assertEquals("", AnalyticsLabels.display("requirement", "status", null));
        assertEquals("", AnalyticsLabels.display("requirement", "status", "  "));
        assertNull(AnalyticsLabels.label("requirement", "status", "NO_SUCH_CODE"));
        assertFalse(AnalyticsLabels.isRegistered("requirement", "status", "NO_SUCH_CODE"));
    }

    @Test
    @DisplayName("joinDisplay 逐段翻译且与旧「 / 」拼接等价")
    void joinDisplayTranslatesEverySegment() {
        List<String> rowDims = List.of("domain", "vendor");
        // 域/厂商取值本来就是中文：未登记的中文原样保留，分隔符与旧实现逐字符一致
        String[] values = {"主数据管理", "某某科技"};
        assertEquals("主数据管理 / 某某科技",
                AnalyticsLabels.joinDisplay("requirement", rowDims, 0, 2, values));
        assertEquals("主数据管理", AnalyticsLabels.joinDisplay("requirement", rowDims, 0, 1, values));

        List<String> mixedDims = List.of("domain", "status");
        assertEquals("主数据管理 / 已交付", AnalyticsLabels.joinDisplay("requirement", mixedDims, 0, 2,
                new String[]{"主数据管理", "DELIVERED"}));
        // 列维只译自己的那一段：from 之前的值不参与（列 key 不许带行维）
        assertEquals("已交付", AnalyticsLabels.joinDisplay("requirement", mixedDims, 1, 2,
                new String[]{"主数据管理", "DELIVERED"}));
        // 未登记枚举码在拼接里也只出「未知状态」，不破形状
        assertEquals("主数据管理 / 未知状态", AnalyticsLabels.joinDisplay("requirement", mixedDims, 0, 2,
                new String[]{"主数据管理", "WHATEVER_CODE"}));
    }

    @Test
    @DisplayName("行头与 rows 同序：sortRows 按度量值重排之后才回查显示串，标签不会错位")
    void rowHeadersFollowSortedRows() {
        // sortRows 是「首个度量的值降序」，行序与 key 的字典序不同：这里刻意让 9 排在前、3 排在后
        java.util.List<AnalyticsViews.CrossRow> rows = List.of(
                new AnalyticsViews.CrossRow("DELIVERED", List.of(9d), List.of(9L)),
                new AnalyticsViews.CrossRow("IN_DEV", List.of(3d), List.of(3L)));
        java.util.Map<String, String> displays = java.util.Map.of(
                "DELIVERED", "已交付", "IN_DEV", "开发中");
        assertEquals(List.of("已交付", "开发中"), AnalyticsQueryService.rowHeadersOf(rows, displays));
        // 没有显示串的 key 原样返回（缓存降级/手工构造时不抛异常）
        assertEquals(List.of("DELIVERED", "IN_DEV"),
                AnalyticsQueryService.rowHeadersOf(rows, java.util.Map.of()));
    }

    @Test
    @DisplayName("度量口径文案没有裸英文枚举（AC-5：口径直接拼进 portal 卡片标题）")
    void calibersContainNoEnglishEnumToken() {
        for (String key : AnalyticsMetrics.keys()) {
            AnalyticsViews.MetricSpec spec = AnalyticsMetrics.spec(key);
            assertFalse(ENUM_TOKEN.matcher(spec.caliber()).find(),
                    key + " 的口径里还有英文枚举 token：" + spec.caliber());
            assertFalse(ENUM_TOKEN.matcher(spec.label()).find(),
                    key + " 的标题里还有英文枚举 token：" + spec.label());
        }
    }
}
