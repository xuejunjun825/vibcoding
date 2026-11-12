package com.example.deliver.support;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.deliver.analytics.AnalyticsViews;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CSV export must survive Excel on Windows and carry the watermark, so the four things that
 * break it — a missing BOM, provenance mistaken for the header row, unescaped delimiters, a row
 * count that disagrees with the sheet — are asserted on the actual bytes (AC-10).
 */
class WatermarkCsvTest {

    @Test
    @DisplayName("首 3 字节是 UTF-8 BOM，首行以 # 开头")
    void startsWithBomAndComment() {
        byte[] bytes = WatermarkCsv.csv(tab(), meta());

        assertEquals((byte) 0xEF, bytes[0]);
        assertEquals((byte) 0xBB, bytes[1]);
        assertEquals((byte) 0xBF, bytes[2]);
        String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertEquals('#', text.charAt(0));
        assertTrue(text.startsWith("# 报表：需求周报 | 数据集：需求 | 生成时间："), text);
        assertTrue(text.contains("水印："), text);
    }

    @Test
    @DisplayName("数据行 = 明细行 + 小计 + 总计，表头只有一行")
    void dataRowsMatchTheSheet() {
        String text = decoded();
        int dataLines = 0;
        for (String line : text.split("\r\n")) {
            if (!line.startsWith("#")) {
                dataLines++;
            }
        }
        // 1 header + 3 detail rows + 小计 + 总计
        assertEquals(6, dataLines, text);
        assertTrue(text.contains("分组,需求数,平均交付周期"), text);
        assertTrue(text.contains("小计,68,3.5"), text);
        assertTrue(text.contains("总计,68,3.5"), text);
    }

    @Test
    @DisplayName("含逗号与引号的分组键被转义成单个 CSV 单元格")
    void escapesQuotesAndCommas() {
        AnalyticsViews.CrossTab tab = new AnalyticsViews.CrossTab(List.of("a,\"b\""), List.of("需求数"),
                List.of(new AnalyticsViews.CrossRow("a,\"b\"", List.of(1.0), List.of(1L))),
                new AnalyticsViews.CrossRow("小计", List.of(1.0), List.of(1L)),
                new AnalyticsViews.CrossRow("总计", List.of(1.0), List.of(1L)),
                1, false, "2026-11-10 10:00", "MISS");

        String text = new String(WatermarkCsv.csv(tab, meta()), StandardCharsets.UTF_8);
        assertTrue(text.contains("\"a,\"\"b\"\"\",1"), text);
    }

    @Test
    @DisplayName("首列写中文 rowHeaders，而不是原始 code（导出与页面同源）")
    void firstColumnUsesChineseRowHeaders() {
        String text = new String(WatermarkCsv.csv(labeledTab(), meta()), StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\r\n")) {
            if (!line.startsWith("#")) {
                lines.add(line);
            }
        }
        // 去注释后：0 = 表头，1..3 = 明细，4 = 小计，5 = 总计
        assertEquals("分组,需求数,平均交付周期", lines.get(0), text);
        assertEquals("紧急,40,3", lines.get(1), text);
        assertEquals("高,20,4", lines.get(2), text);
        assertEquals("中,8,4", lines.get(3), text);
        assertEquals("小计,68,3.5", lines.get(4), text);
        assertEquals("总计,68,3.5", lines.get(5), text);
    }

    private static AnalyticsViews.CrossTab labeledTab() {
        return new AnalyticsViews.CrossTab(
                List.of("紧急", "高", "中"),
                List.of("需求数", "平均交付周期"),
                List.of(new AnalyticsViews.CrossRow("P0", List.of(40.0, 3.0), List.of(40L, 40L)),
                        new AnalyticsViews.CrossRow("P1", List.of(20.0, 4.0), List.of(20L, 20L)),
                        new AnalyticsViews.CrossRow("P2", List.of(8.0, 4.0), List.of(8L, 8L))),
                new AnalyticsViews.CrossRow("小计", List.of(68.0, 3.5), List.of(68L, 68L)),
                new AnalyticsViews.CrossRow("总计", List.of(68.0, 3.5), List.of(68L, 68L)),
                3, false, "2026-11-10 10:00", "MISS");
    }

    private static AnalyticsViews.CrossTab tab() {
        return new AnalyticsViews.CrossTab(
                List.of("P0", "P1", "P2"),
                List.of("需求数", "平均交付周期"),
                List.of(new AnalyticsViews.CrossRow("P0", List.of(40.0, 3.0), List.of(40L, 40L)),
                        new AnalyticsViews.CrossRow("P1", List.of(20.0, 4.0), List.of(20L, 20L)),
                        new AnalyticsViews.CrossRow("P2", List.of(8.0, 4.0), List.of(8L, 8L))),
                new AnalyticsViews.CrossRow("小计", List.of(68.0, 3.5), List.of(68L, 68L)),
                new AnalyticsViews.CrossRow("总计", List.of(68.0, 3.5), List.of(68L, 68L)),
                3, false, "2026-11-10 10:00", "MISS");
    }

    private static WatermarkSheet.Meta meta() {
        return new WatermarkSheet.Meta("需求周报", "需求", "2026-11-10T10:00:00", "2026-11-10 10:00");
    }

    /** The decoded payload with the byte-order mark removed, as a reader would consume it. */
    private static String decoded() {
        String text = new String(WatermarkCsv.csv(tab(), meta()), StandardCharsets.UTF_8);
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }
}
