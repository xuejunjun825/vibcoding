package com.example.deliver.support;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import com.example.deliver.analytics.AnalyticsViews;

/**
 * CSV export of one cross table: UTF-8 with a BOM, a {@code #} provenance line, then the data.
 *
 * <p>The BOM is not cosmetic — Excel on Windows opens a UTF-8 file without it as the ANSI code
 * page and every Chinese label turns into mojibake, which is exactly the failure a watermarked
 * export must not have. The first line starts with {@code #} so a reader (and Excel's importer)
 * can tell the provenance row from the header row.</p>
 *
 * <p>Written separately from {@link WatermarkSheet}: CSV needs its own escaping, line ending and
 * BOM rules, and mixing the two writers would push sheet concerns (merged cells, fill colours)
 * into a format that has no such concept.</p>
 */
public final class WatermarkCsv {

    /** UTF-8 byte-order mark, written before the first character. */
    public static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final String EOL = "\r\n";
    private static final String ROW_LABEL = "分组";

    private WatermarkCsv() {
    }

    /** Render the table to bytes; the result is complete before it leaves this method. */
    public static byte[] csv(AnalyticsViews.CrossTab tab, WatermarkSheet.Meta meta) {
        WatermarkSheet.Meta head = meta == null
                ? new WatermarkSheet.Meta(null, null, null, null) : meta;
        StringBuilder out = new StringBuilder(1024);
        int dataRows = tab.rows().size() + 2;
        out.append("# 报表：").append(head.reportName())
                .append(" | 数据集：").append(head.datasetLabel())
                .append(" | 生成时间：").append(head.generatedAt())
                .append(" | 行数：").append(dataRows)
                .append(" | 水印：").append(WatermarkSheet.WATERMARK)
                .append(EOL);

        out.append(quote(ROW_LABEL));
        for (String column : tab.colHeaders()) {
            out.append(',').append(quote(column));
        }
        out.append(EOL);

        for (AnalyticsViews.CrossRow row : tab.rows()) {
            appendRow(out, row, tab.colHeaders().size());
        }
        appendRow(out, tab.subtotal(), tab.colHeaders().size());
        appendRow(out, tab.total(), tab.colHeaders().size());

        out.append("# ").append(head.footer()).append(EOL);

        byte[] body = out.toString().getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, bytes, 0, BOM.length);
        System.arraycopy(body, 0, bytes, BOM.length, body.length);
        return bytes;
    }

    private static void appendRow(StringBuilder out, AnalyticsViews.CrossRow row, int columnCount) {
        out.append(quote(row == null ? "" : row.key()));
        for (int i = 0; i < columnCount; i++) {
            Double value = row == null || i >= row.values().size() ? null : row.values().get(i);
            out.append(',').append(value == null ? "" : number(value));
        }
        out.append(EOL);
    }

    private static String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** Quote when the value could break the row; a leading '#' is quoted so it cannot look like data. */
    private static String quote(String raw) {
        String value = raw == null ? "" : raw;
        boolean needsQuotes = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                || value.startsWith("#");
        if (!needsQuotes) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
