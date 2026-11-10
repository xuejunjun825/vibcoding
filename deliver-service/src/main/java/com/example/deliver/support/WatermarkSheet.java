package com.example.deliver.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.example.deliver.analytics.AnalyticsViews;

/**
 * XLSX export of one cross table, with the document watermark baked into the sheet itself.
 *
 * <p>A downloaded spread sheet outlives the session that produced it, so the provenance travels
 * <b>inside the artefact</b> rather than only in the response headers: the first row states which
 * report, which dataset and when, and the last row repeats the internal-material watermark. Both
 * rows are merged across the table, so inserting or deleting data rows cannot separate a number
 * from the report it belongs to.</p>
 *
 * <p>Deliberately a separate writer from {@code quality.report.ReportExportService}: that one
 * renders a frozen test-report snapshot with 8 sheets and a signoff watermark. Sharing a writer
 * would couple two unrelated export contracts, and a change for one would silently regress the
 * other.</p>
 */
public final class WatermarkSheet {

    /** The internal-material marker, identical in the sheet and in the CSV header line. */
    public static final String WATERMARK = "内部资料（水印）";

    /** What the watermark states about the artefact; the same record feeds {@link WatermarkCsv}. */
    public record Meta(String reportName, String datasetLabel, String generatedAt, String dataAsOf) {

        public Meta {
            reportName = reportName == null || reportName.isBlank() ? "未命名报表" : reportName;
            datasetLabel = datasetLabel == null || datasetLabel.isBlank() ? "未知数据集" : datasetLabel;
            generatedAt = generatedAt == null || generatedAt.isBlank()
                    ? LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    : generatedAt;
            dataAsOf = dataAsOf == null ? "" : dataAsOf;
        }

        /** The first-row statement: report, dataset, generation time, watermark. */
        public String header() {
            return "报表：" + reportName + " · 数据集：" + datasetLabel
                    + " · 生成时间：" + generatedAt + " · " + WATERMARK;
        }

        /** The last-row statement; {@code dataAsOf} is what the numbers are current to. */
        public String footer() {
            String asOf = dataAsOf.isBlank() ? "" : " · 数据截至：" + dataAsOf;
            return "水印：" + WATERMARK + asOf + " · 请勿外传";
        }
    }

    private static final int FREEZE_ROWS = 2;
    private static final int MAX_SHEET_NAME = 31;

    private WatermarkSheet() {
    }

    /** Render the table to bytes; nothing touches the file system, so a failure leaves no residue. */
    public static byte[] xlsx(AnalyticsViews.CrossTab tab, Meta meta) {
        Meta head = meta == null ? new Meta(null, null, null, null) : meta;
        // Inline strings are SXSSF's default; a shared string table keeps the repeated header and
        // watermark text in one place, so the sheet reads the same in every reader that inspects
        // xl/sharedStrings.xml rather than the sheet body. The flag is constructor-only in POI 5.x.
        SXSSFWorkbook workbook = new SXSSFWorkbook(new XSSFWorkbook(), 200, false, true);
        try {
            Sheet sheet = workbook.createSheet(sheetName(head.datasetLabel()));
            int columns = Math.max(1, tab.colHeaders().size() + 1);
            Styles styles = new Styles(workbook);

            Row title = sheet.createRow(0);
            title.setHeightInPoints(22f);
            cell(title, 0, head.header(), styles.watermark());
            merge(sheet, 0, 0, columns);

            Row header = sheet.createRow(1);
            header.setHeightInPoints(18f);
            cell(header, 0, "分组", styles.header());
            for (int i = 0; i < tab.colHeaders().size(); i++) {
                cell(header, i + 1, tab.colHeaders().get(i), styles.header());
            }

            int rowIndex = FREEZE_ROWS;
            for (AnalyticsViews.CrossRow row : tab.rows()) {
                rowIndex = dataRow(sheet, rowIndex, row, styles, columns);
            }
            rowIndex = dataRow(sheet, rowIndex, tab.subtotal(), styles, columns);
            rowIndex = dataRow(sheet, rowIndex, tab.total(), styles, columns);

            Row footer = sheet.createRow(rowIndex);
            cell(footer, 0, head.footer(), styles.footer());
            merge(sheet, rowIndex, 0, columns);

            sheet.createFreezePane(0, FREEZE_ROWS);
            sheet.setColumnWidth(0, 28 * 256);
            for (int i = 1; i < columns; i++) {
                sheet.setColumnWidth(i, 18 * 256);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot render the report export", e);
        } finally {
            workbook.dispose();
            try {
                workbook.close();
            } catch (IOException ignored) {
                // the bytes are already in memory; a failed close must not mask a successful export
            }
        }
    }

    private static int dataRow(Sheet sheet, int rowIndex, AnalyticsViews.CrossRow row, Styles styles,
                               int columns) {
        Row target = sheet.createRow(rowIndex);
        String key = row == null || row.key() == null ? "" : row.key();
        cell(target, 0, key, styles.label());
        if (row != null) {
            for (int i = 0; i < row.values().size() && i + 1 < columns; i++) {
                Double value = row.values().get(i);
                if (value == null) {
                    continue;
                }
                Cell cell = target.createCell(i + 1);
                cell.setCellValue(value);
                cell.setCellStyle(styles.number());
            }
            for (int i = row.values().size() + 1; i < columns; i++) {
                target.createCell(i).setCellStyle(styles.number());
            }
        }
        return rowIndex + 1;
    }

    private static void cell(Row row, int index, String value, CellStyle style) {
        Cell cell = row.createCell(index);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private static void merge(Sheet sheet, int row, int firstColumn, int columnCount) {
        if (columnCount > 1) {
            sheet.addMergedRegion(new CellRangeAddress(row, row, firstColumn, columnCount - 1));
        }
    }

    private static String sheetName(String label) {
        String cleaned = label == null || label.isBlank() ? "报表" : label.trim();
        cleaned = cleaned.replaceAll("[\\\\/?*\\[\\]:]", "_");
        return cleaned.length() > MAX_SHEET_NAME ? cleaned.substring(0, MAX_SHEET_NAME) : cleaned;
    }

    /** Every style the writer needs, created once per workbook. */
    private record Styles(CellStyle watermark, CellStyle header, CellStyle label, CellStyle number,
                          CellStyle footer) {

        Styles(SXSSFWorkbook workbook) {
            this(watermarkStyle(workbook), headerStyle(workbook), labelStyle(workbook),
                    numberStyle(workbook), footerStyle(workbook));
        }

        private static CellStyle watermarkStyle(SXSSFWorkbook workbook) {
            CellStyle style = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            font.setFontHeightInPoints((short) 11);
            font.setColor(IndexedColors.DARK_BLUE.getIndex());
            style.setFont(font);
            if (style instanceof XSSFCellStyle xssf) {
                xssf.setFillForegroundColor(new XSSFColor(new byte[] {(byte) 0xEA, (byte) 0xF1,
                        (byte) 0xFE}, null));
            } else {
                style.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            }
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setAlignment(HorizontalAlignment.CENTER);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            return style;
        }

        private static CellStyle headerStyle(SXSSFWorkbook workbook) {
            CellStyle style = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            style.setFont(font);
            style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setAlignment(HorizontalAlignment.CENTER);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            style.setBorderBottom(BorderStyle.THIN);
            return style;
        }

        private static CellStyle labelStyle(SXSSFWorkbook workbook) {
            CellStyle style = workbook.createCellStyle();
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            style.setBorderBottom(BorderStyle.HAIR);
            return style;
        }

        private static CellStyle numberStyle(SXSSFWorkbook workbook) {
            CellStyle style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
            style.setAlignment(HorizontalAlignment.RIGHT);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            style.setBorderBottom(BorderStyle.HAIR);
            return style;
        }

        private static CellStyle footerStyle(SXSSFWorkbook workbook) {
            CellStyle style = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setItalic(true);
            font.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            style.setFont(font);
            style.setAlignment(HorizontalAlignment.CENTER);
            return style;
        }
    }
}
