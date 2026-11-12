package com.example.deliver.support;

import java.io.ByteArrayInputStream;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.deliver.analytics.AnalyticsViews;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The XLSX export is read back with POI: the watermark has to be in the sheet (not only in the
 * response headers), because a downloaded file outlives the session that produced it (AC-10).
 */
class WatermarkSheetTest {

    @Test
    @DisplayName("首行是合并的水印行，底色 #EAF1FE，末行再声明一次水印")
    void watermarkOnFirstAndLastRow() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes()))) {
            Sheet sheet = workbook.getSheetAt(0);
            Cell title = sheet.getRow(0).getCell(0);
            String text = title.getStringCellValue();
            assertTrue(text.startsWith("报表：需求周报 · 数据集：需求 · 生成时间："), text);
            assertTrue(text.endsWith(WatermarkSheet.WATERMARK), text);

            boolean merged = false;
            for (CellRangeAddress region : sheet.getMergedRegions()) {
                merged |= region.getFirstRow() == 0 && region.getLastRow() == 0
                        && region.getFirstColumn() == 0 && region.getLastColumn() == 2;
            }
            assertTrue(merged, "first row must be merged across the table");

            XSSFCellStyle style = (XSSFCellStyle) title.getCellStyle();
            assertNotNull(style.getFillForegroundColorColor());
            assertTrue(style.getFillForegroundColorColor().getARGBHex().endsWith("EAF1FE"),
                    style.getFillForegroundColorColor().getARGBHex());

            Row footer = sheet.getRow(sheet.getLastRowNum());
            assertTrue(footer.getCell(0).getStringCellValue().contains("水印："),
                    footer.getCell(0).getStringCellValue());
        }
    }

    @Test
    @DisplayName("表头 1 行 + 明细 3 行 + 小计 + 总计，数值列可被 Excel 识别为数字")
    void rowsAndHeader() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes()))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals("分组", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("需求数", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("平均交付周期", sheet.getRow(1).getCell(2).getStringCellValue());

            // rows 2..6 are 3 details + 小计 + 总计, row 7 is the footer watermark
            assertEquals(7, sheet.getLastRowNum());
            assertEquals("P0", sheet.getRow(2).getCell(0).getStringCellValue());
            assertEquals(40d, sheet.getRow(2).getCell(1).getNumericCellValue());
            assertEquals("小计", sheet.getRow(5).getCell(0).getStringCellValue());
            assertEquals("总计", sheet.getRow(6).getCell(0).getStringCellValue());
        }
    }

    @Test
    @DisplayName("首列写中文 rowHeaders，而不是原始 code（导出与页面同源）")
    void firstColumnUsesChineseRowHeaders() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(labeledBytes()))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals("紧急", sheet.getRow(2).getCell(0).getStringCellValue());
            assertEquals("高", sheet.getRow(3).getCell(0).getStringCellValue());
            assertEquals("中", sheet.getRow(4).getCell(0).getStringCellValue());
            assertEquals("小计", sheet.getRow(5).getCell(0).getStringCellValue());
            assertEquals("总计", sheet.getRow(6).getCell(0).getStringCellValue());
        }
    }

    private static byte[] labeledBytes() {
        AnalyticsViews.CrossTab tab = new AnalyticsViews.CrossTab(
                List.of("紧急", "高", "中"),
                List.of("需求数", "平均交付周期"),
                List.of(new AnalyticsViews.CrossRow("P0", List.of(40.0, 3.0), List.of(40L, 40L)),
                        new AnalyticsViews.CrossRow("P1", List.of(20.0, 4.0), List.of(20L, 20L)),
                        new AnalyticsViews.CrossRow("P2", List.of(8.0, 4.0), List.of(8L, 8L))),
                new AnalyticsViews.CrossRow("小计", List.of(68.0, 3.5), List.of(68L, 68L)),
                new AnalyticsViews.CrossRow("总计", List.of(68.0, 3.5), List.of(68L, 68L)),
                3, false, "2026-11-10 10:00", "MISS");
        return WatermarkSheet.xlsx(tab, new WatermarkSheet.Meta("需求周报", "需求",
                "2026-11-10T10:00:00", "2026-11-10 10:00"));
    }

    private static byte[] bytes() {
        return WatermarkSheet.xlsx(tab(), new WatermarkSheet.Meta("需求周报", "需求",
                "2026-11-10T10:00:00", "2026-11-10 10:00"));
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
}
