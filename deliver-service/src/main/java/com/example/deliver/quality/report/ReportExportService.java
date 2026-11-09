package com.example.deliver.quality.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.example.deliver.support.Rows;
import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfGState;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Report export: XLSX (8 sheets) and PDF (CID Chinese font, 45° watermark) straight to {@code byte[]}.
 *
 * <p>Both formats render the frozen {@code snapshot_json} of one version, never live data — a copy
 * downloaded today and the copy downloaded after the next test run show the same numbers. The one
 * live input is the signoff rows, because a version can be exported before it is fully signed: the
 * watermark then carries {@code 未生效} so an unsigned draft cannot be mistaken for an approved one.</p>
 *
 * <p>Nothing is written to disk. The PDF uses {@code STSong-Light} with the {@code UniGB-UCS2-H}
 * encoding and {@link BaseFont#NOT_EMBEDDED}, so no font binary enters the repository and Chinese
 * still renders on any reader that ships the standard CJK font (Acrobat, Preview, WPS).</p>
 */
@Service
public class ReportExportService {

    private static final Logger log = LoggerFactory.getLogger(ReportExportService.class);

    private static final String FONT_NAME = "STSong-Light";
    private static final String FONT_ENCODING = "UniGB-UCS2-H";

    private final ReportService reports;
    private final AuditService audit;

    public ReportExportService(ReportService reports, AuditService audit) {
        this.reports = reports;
        this.audit = audit;
    }

    /** One downloadable artefact. */
    public record Export(byte[] bytes, String fileName, String contentType) {
    }

    @Transactional
    public Export export(long reportId, Integer requestedVersion, String requestedFormat) {
        DataScope scope = TenantContext.require();
        String format = normaliseFormat(requestedFormat);
        Map<String, Object> report = reports.requireReport(reportId);
        int versionNo = requestedVersion == null ? reports.currentVersion(report) : requestedVersion;
        ReportViews.ReportVersionDetail detail = reports.version(reportId, versionNo);

        String watermark = watermark(detail, TenantContext.tenantName());
        byte[] bytes = "pdf".equals(format) ? pdf(detail, watermark) : xlsx(detail, watermark);

        String code = detail.code() == null || detail.code().isBlank() ? "RPT" : detail.code();
        String fileName = code + "-v" + versionNo + ("pdf".equals(format) ? ".pdf" : ".xlsx");

        Map<String, Object> auditDetail = new LinkedHashMap<>();
        auditDetail.put("format", format);
        auditDetail.put("versionNo", versionNo);
        auditDetail.put("bytes", bytes.length);
        auditDetail.put("signed", detail.effective());
        auditDetail.put("watermark", watermark);
        audit.record("report.export", "test_report", reportId, auditDetail);
        log.debug("report exported: report={} version={} format={} bytes={}",
                reportId, versionNo, format, bytes.length);

        return new Export(bytes, fileName,
                "pdf".equals(format) ? "application/pdf"
                        : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    // ================================================================== watermark

    private static String watermark(ReportViews.ReportVersionDetail detail, String tenantName) {
        String prefix = detail.effective() ? "内部资料" : "内部资料 · 未生效";
        return prefix + " · " + (tenantName == null || tenantName.isBlank() ? "未知租户" : tenantName)
                + " · " + detail.code() + " · v" + detail.versionNo();
    }

    // ================================================================== XLSX

    byte[] xlsx(ReportViews.ReportVersionDetail detail, String watermark) {
        SXSSFWorkbook workbook = new SXSSFWorkbook(500);
        workbook.setCompressTempFiles(true);
        try {
            CellStyle title = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            title.setFont(titleFont);
            CellStyle head = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font headFont = workbook.createFont();
            headFont.setBold(true);
            head.setFont(headFont);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setBorderBottom(BorderStyle.THIN);
            head.setVerticalAlignment(VerticalAlignment.CENTER);
            CellStyle watermarkStyle = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font watermarkFont = workbook.createFont();
            watermarkFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            watermarkFont.setFontHeightInPoints((short) 9);
            watermarkStyle.setFont(watermarkFont);
            watermarkStyle.setAlignment(HorizontalAlignment.RIGHT);
            CellStyle number = workbook.createCellStyle();
            number.setAlignment(HorizontalAlignment.LEFT);

            List<SheetDef> sheets = sections(detail, watermark);
            for (int index = 0; index < sheets.size(); index++) {
                Sheet sheet = workbook.createSheet(sheets.get(index).name());
                sheet.getFooter().setCenter(watermark);
                int rowIndex = 0;

                Row mark = sheet.createRow(rowIndex++);
                Cell markCell = mark.createCell(0);
                markCell.setCellValue(watermark);
                markCell.setCellStyle(watermarkStyle);
                sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));

                Row titleRow = sheet.createRow(rowIndex++);
                Cell titleCell = titleRow.createCell(0);
                titleCell.setCellValue(detail.code() + " v" + detail.versionNo() + " · "
                        + sheets.get(index).title());
                titleCell.setCellStyle(title);

                rowIndex = writeRows(sheet, sheets.get(index).rows(), rowIndex, head, number);

                Row footer = sheet.createRow(rowIndex + 1);
                Cell footerCell = footer.createCell(0);
                footerCell.setCellValue(watermark);
                footerCell.setCellStyle(watermarkStyle);
                sheet.addMergedRegion(new CellRangeAddress(rowIndex + 1, rowIndex + 1, 0, 3));

                for (int column = 0; column < 5; column++) {
                    sheet.setColumnWidth(column, (column == 0 ? 26 : 18) * 256);
                }
                sheet.createFreezePane(0, 3);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("cannot build the report workbook", ex);
        } finally {
            try {
                workbook.dispose();
                workbook.close();
            } catch (IOException ignored) {
                // temp file cleanup only
            }
        }
    }

    private static int writeRows(Sheet sheet, List<SheetRow> rows, int rowIndex, CellStyle head,
                                 CellStyle number) {
        for (SheetRow source : rows) {
            Row target = sheet.createRow(rowIndex++);
            for (int column = 0; column < source.cells().size(); column++) {
                Object value = source.cells().get(column);
                Cell cell = target.createCell(column);
                if (value == null) {
                    continue;
                }
                if (value instanceof Number numeric) {
                    cell.setCellValue(numeric.doubleValue());
                    cell.setCellStyle(number);
                } else {
                    cell.setCellValue(value.toString());
                }
                if (source.header()) {
                    cell.setCellStyle(head);
                }
            }
        }
        return rowIndex;
    }

    // ================================================================== PDF

    byte[] pdf(ReportViews.ReportVersionDetail detail, String watermark) {
        Document document = new Document(PageSize.A4, 46, 46, 54, 46);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            BaseFont baseFont = BaseFont.createFont(FONT_NAME, FONT_ENCODING, BaseFont.NOT_EMBEDDED);
            writer.setPageEvent(new WatermarkEvent(baseFont, watermark));
            document.open();

            Font title = new Font(baseFont, 16, Font.BOLD);
            Font section = new Font(baseFont, 12, Font.BOLD);
            Font body = new Font(baseFont, 9.5f, Font.NORMAL);
            Font small = new Font(baseFont, 8, Font.NORMAL);

            Paragraph heading = new Paragraph("测试报告 " + detail.code() + " · v" + detail.versionNo(), title);
            heading.setAlignment(Element.ALIGN_CENTER);
            document.add(heading);
            Paragraph meta = new Paragraph(
                    "范围 " + ("PLAN".equals(detail.scope()) ? "单计划" : "版本") + " · 结论 "
                            + safe(detail.conclusion()) + " · 签署状态 "
                            + (detail.effective() ? "已生效" : "未生效")
                            + " · 生成时间 " + safe(detail.generatedAt()), small);
            meta.setAlignment(Element.ALIGN_CENTER);
            document.add(meta);
            document.add(new Paragraph(" ", body));

            for (SheetDef sheet : sections(detail, watermark)) {
                Paragraph heading2 = new Paragraph(sheet.title(), section);
                heading2.setSpacingBefore(8);
                heading2.setSpacingAfter(3);
                document.add(heading2);
                document.add(table(sheet.rows(), baseFont, body));
            }
            document.close();
            return out.toByteArray();
        } catch (DocumentException | IOException ex) {
            throw new IllegalStateException("cannot build the report PDF", ex);
        } finally {
            if (document.isOpen()) {
                document.close();
            }
        }
    }

    private static PdfPTable table(List<SheetRow> rows, BaseFont baseFont, Font body) throws DocumentException {
        int columns = 1;
        for (SheetRow row : rows) {
            columns = Math.max(columns, row.cells().size());
        }
        PdfPTable table = new PdfPTable(Math.min(columns, 8));
        table.setWidthPercentage(100);
        table.setSpacingAfter(6);
        Font head = new Font(baseFont, 9, Font.BOLD);
        for (SheetRow row : rows) {
            for (int column = 0; column < Math.min(columns, 8); column++) {
                Object value = column < row.cells().size() ? row.cells().get(column) : "";
                Phrase phrase = new Phrase(format(value), row.header() ? head : body);
                PdfPCell cell = new PdfPCell(phrase);
                cell.setPadding(3);
                if (row.header()) {
                    cell.setBackgroundColor(new java.awt.Color(238, 238, 238));
                }
                table.addCell(cell);
            }
        }
        return table;
    }

    /** 45° translucent watermark on every page, drawn under the content. */
    private static final class WatermarkEvent extends PdfPageEventHelper {
        private final BaseFont baseFont;
        private final String text;
        private final PdfGState state = new PdfGState();

        private WatermarkEvent(BaseFont baseFont, String text) {
            this.baseFont = baseFont;
            this.text = text;
            this.state.setFillOpacity(0.10f);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte canvas = writer.getDirectContentUnder();
            canvas.saveState();
            canvas.setGState(state);
            canvas.beginText();
            canvas.setFontAndSize(baseFont, 40);
            canvas.showTextAligned(Element.ALIGN_CENTER, text,
                    document.getPageSize().getWidth() / 2,
                    document.getPageSize().getHeight() / 2, 45);
            canvas.endText();
            canvas.restoreState();
        }
    }

    // ================================================================== the 8 sections

    /** One sheet: a name, the section heading and the rows (first row may carry {@code header}). */
    record SheetDef(String name, String title, List<SheetRow> rows) {
    }

    /** A row of cells; numbers stay numbers so a spreadsheet can sum them. */
    record SheetRow(List<Object> cells, boolean header) {
        static SheetRow of(Object... cells) {
            // Arrays.asList, not List.of: a section legitimately has an empty cell.
            return new SheetRow(Arrays.asList(cells), false);
        }

        static SheetRow head(Object... cells) {
            return new SheetRow(Arrays.asList(cells), true);
        }
    }

    private static String signoffLabel(String status) {
        return switch (status == null ? "PENDING" : status) {
            case "SIGNED" -> "已签署";
            case "REJECTED" -> "已驳回";
            default -> "待签署";
        };
    }

    private List<SheetDef> sections(ReportViews.ReportVersionDetail detail, String watermark) {
        // The API face keeps the reader's header flat; the frozen 8 sections sit under "sections".
        Map<String, Object> wrapped = detail.snapshot() == null ? Map.of() : detail.snapshot();
        Map<String, Object> snapshot = section(wrapped, "sections");
        Map<String, Object> scope = section(snapshot, "scope");
        Map<String, Object> execution = section(snapshot, "execution");
        Map<String, Object> rates = section(snapshot, "rates");
        Map<String, Object> defects = section(snapshot, "defects");
        Map<String, Object> residual = section(snapshot, "residual");
        Map<String, Object> env = section(snapshot, "env");
        Map<String, Object> risk = section(snapshot, "riskConclusion");
        Map<String, Object> signoff = section(snapshot, "signoff");

        List<SheetDef> sheets = new ArrayList<>(8);

        List<SheetRow> scopeRows = new ArrayList<>();
        scopeRows.add(SheetRow.head("项目", "值"));
        scopeRows.add(SheetRow.of("报告编号", detail.code()));
        scopeRows.add(SheetRow.of("版本号", detail.versionNo()));
        scopeRows.add(SheetRow.of("范围", safe(detail.scope())));
        scopeRows.add(SheetRow.of("版本", safe(detail.releaseName())));
        scopeRows.add(SheetRow.of("计划", safe(detail.planName())));
        scopeRows.add(SheetRow.of("数据截止", safe(scope.get("dataCutoff"))));
        scopeRows.add(SheetRow.of("生成时间", safe(detail.generatedAt())));
        scopeRows.add(SheetRow.of("生成人", safe(detail.generatedByName())));
        scopeRows.add(SheetRow.of("用例结果摘要", safe(detail.resultDigest())));
        scopeRows.add(SheetRow.of("快照指纹", safe(detail.hash())));
        scopeRows.add(SheetRow.of("计算结论", safe(detail.conclusion())));
        scopeRows.add(SheetRow.head("纳入计划", "状态", "修订", "用例数", "已执行", "通过", "失败", "通过率"));
        for (Map<String, Object> plan : list(scope.get("plans"))) {
            scopeRows.add(SheetRow.of(safe(plan.get("name")), safe(plan.get("status")),
                    number(plan.get("revision")), number(plan.get("total")),
                    number(plan.get("executed")), number(plan.get("passed")),
                    number(plan.get("failed")), number(plan.get("passRate"))));
        }
        scopeRows.add(SheetRow.head("未纳入计划", "状态", "原因"));
        for (Map<String, Object> plan : list(scope.get("excludedPlans"))) {
            scopeRows.add(SheetRow.of(safe(plan.get("name")), safe(plan.get("status")), "未批准"));
        }
        sheets.add(new SheetDef("01 范围与版本快照", "01 范围与版本快照", scopeRows));

        List<SheetRow> execRows = new ArrayList<>();
        Map<String, Object> totals = map(execution.get("totals"));
        execRows.add(SheetRow.head("结果", "数量"));
        for (String key : List.of("planned", "executed", "passed", "failed", "blocked", "skipped", "notRun")) {
            execRows.add(SheetRow.of(label(key), number(totals.get(key))));
        }
        execRows.add(SheetRow.head("模块", "通过", "失败", "阻塞", "跳过", "未执行"));
        for (Map<String, Object> item : list(execution.get("byModule"))) {
            execRows.add(SheetRow.of(safe(item.get("moduleName")), number(item.get("PASS")),
                    number(item.get("FAIL")), number(item.get("BLOCKED")),
                    number(item.get("SKIP")), number(item.get("NOT_RUN"))));
        }
        execRows.add(SheetRow.head("执行人", "通过", "失败", "阻塞", "跳过", "未执行"));
        for (Map<String, Object> item : list(execution.get("byExecutor"))) {
            execRows.add(SheetRow.of(safe(item.get("executorName")), number(item.get("PASS")),
                    number(item.get("FAIL")), number(item.get("BLOCKED")),
                    number(item.get("SKIP")), number(item.get("NOT_RUN"))));
        }
        sheets.add(new SheetDef("02 用例执行分布", "02 用例执行分布（结果 × 模块 × 执行人）", execRows));

        List<SheetRow> rateRows = new ArrayList<>();
        rateRows.add(SheetRow.head("指标", "值"));
        rateRows.add(SheetRow.of("通过率（%）", number(rates.get("passRate"))));
        rateRows.add(SheetRow.of("执行覆盖率（%）", number(rates.get("coverage"))));
        rateRows.add(SheetRow.of("已执行通过率（%）", number(rates.get("executedPassRate"))));
        rateRows.add(SheetRow.of("计划用例数", number(rates.get("planned"))));
        rateRows.add(SheetRow.of("已执行", number(rates.get("executed"))));
        rateRows.add(SheetRow.of("通过", number(rates.get("passed"))));
        rateRows.add(SheetRow.of("失败", number(rates.get("failed"))));
        rateRows.add(SheetRow.of("阻塞", number(rates.get("blocked"))));
        rateRows.add(SheetRow.of("跳过", number(rates.get("skipped"))));
        rateRows.add(SheetRow.of("未执行", number(rates.get("notRun"))));
        rateRows.add(SheetRow.of("通过率口径", safe(rates.get("passRateFormula"))));
        rateRows.add(SheetRow.of("覆盖率口径", safe(rates.get("coverageFormula"))));
        sheets.add(new SheetDef("03 通过率与覆盖率", "03 通过率与覆盖率", rateRows));

        List<SheetRow> defectRows = new ArrayList<>();
        defectRows.add(SheetRow.head("指标", "值"));
        defectRows.add(SheetRow.of("缺陷总数", number(defects.get("total"))));
        defectRows.add(SheetRow.of("未关闭", number(defects.get("open"))));
        defectRows.add(SheetRow.of("未关闭 P0/P1", number(defects.get("openP0P1"))));
        defectRows.add(SheetRow.of("P0", number(defects.get("p0"))));
        defectRows.add(SheetRow.of("P1", number(defects.get("p1"))));
        defectRows.add(SheetRow.of("生产逃逸", number(defects.get("escaped"))));
        defectRows.add(SheetRow.of("逃逸率（%）", number(defects.get("escapeRate"))));
        defectRows.add(SheetRow.head("严重级", "数量"));
        for (Map<String, Object> item : list(defects.get("bySeverity"))) {
            defectRows.add(SheetRow.of(safe(item.get("key")), number(item.get("count"))));
        }
        defectRows.add(SheetRow.head("状态", "数量"));
        for (Map<String, Object> item : list(defects.get("byStatus"))) {
            defectRows.add(SheetRow.of(safe(item.get("key")), number(item.get("count"))));
        }
        defectRows.add(SheetRow.head("来源", "数量"));
        for (Map<String, Object> item : list(defects.get("bySource"))) {
            defectRows.add(SheetRow.of(safe(item.get("key")), number(item.get("count"))));
        }
        defectRows.add(SheetRow.head("发现日期", "数量"));
        for (Map<String, Object> item : list(defects.get("byDay"))) {
            defectRows.add(SheetRow.of(safe(item.get("key")), number(item.get("count"))));
        }
        sheets.add(new SheetDef("04 缺陷统计与趋势", "04 缺陷统计与趋势（按日 / 严重级 / 来源）", defectRows));

        List<SheetRow> residualRows = new ArrayList<>();
        residualRows.add(SheetRow.head("未关闭缺陷", "标题", "严重级", "状态", "来源"));
        for (Map<String, Object> item : list(residual.get("openDefects"))) {
            residualRows.add(SheetRow.of(safe(item.get("code")), safe(item.get("title")),
                    safe(item.get("severity")), safe(item.get("status")), safe(item.get("source"))));
        }
        residualRows.add(SheetRow.head("豁免规则", "状态", "原因", "影响范围", "到期时间"));
        for (Map<String, Object> item : list(residual.get("waivers"))) {
            residualRows.add(SheetRow.of(safe(item.get("ruleCode")), safe(item.get("status")),
                    safe(item.get("reason")), safe(item.get("impactScope")),
                    safe(item.get("expiryAt"))));
        }
        sheets.add(new SheetDef("05 遗留缺陷与豁免清单", "05 遗留缺陷与豁免清单", residualRows));

        List<SheetRow> envRows = new ArrayList<>();
        envRows.add(SheetRow.head("环境", "类型", "地址", "状态"));
        for (Map<String, Object> item : list(env.get("envs"))) {
            envRows.add(SheetRow.of(safe(item.get("name")), safe(item.get("envType")),
                    safe(item.get("baseUrl")), safe(item.get("status"))));
        }
        sheets.add(new SheetDef("06 环境与配置", "06 环境与配置", envRows));

        List<SheetRow> riskRows = new ArrayList<>();
        riskRows.add(SheetRow.head("项目", "值"));
        riskRows.add(SheetRow.of("建议结论", safe(risk.get("suggested"))));
        riskRows.add(SheetRow.of("报告结论", safe(detail.conclusion())));
        riskRows.add(SheetRow.of("通过率阈值（%）", number(risk.get("passRateThreshold"))));
        riskRows.add(SheetRow.of("未关闭 P0/P1", number(risk.get("openP0P1"))));
        riskRows.add(SheetRow.of("未关闭缺陷", number(risk.get("openDefects"))));
        riskRows.add(SheetRow.of("有效豁免", number(risk.get("waiverCount"))));
        riskRows.add(SheetRow.head("风险项", "等级", "说明"));
        for (Map<String, Object> item : list(risk.get("risks"))) {
            riskRows.add(SheetRow.of(safe(item.get("text")), safe(item.get("level")), safe(item.get("code"))));
        }
        sheets.add(new SheetDef("07 风险与结论", "07 风险与结论", riskRows));

        List<SheetRow> signRows = new ArrayList<>();
        signRows.add(SheetRow.head("顺序", "角色", "状态", "签署人", "签署时间", "意见"));
        for (ReportViews.SignoffStep step : detail.signoff().steps()) {
            signRows.add(SheetRow.of(step.orderNo(), step.roleName(), signoffLabel(step.status()),
                    safe(step.signerName()), safe(step.signedAt()), safe(step.comment())));
        }
        signRows.add(SheetRow.of("", "", "", "", "", ""));
        signRows.add(SheetRow.of("未签完的版本不得作为质量门依据", "", "", "", "", ""));
        signRows.add(SheetRow.of("签署进度", detail.signoff().approved() + "/" + detail.signoff().total(),
                "", "", "", watermark));
        sheets.add(new SheetDef("08 会签页", "08 会签页", signRows));
        return sheets;
    }

    // ================================================================== helpers

    private static String normaliseFormat(String raw) {
        String value = raw == null || raw.isBlank() ? "xlsx" : raw.trim().toLowerCase(Locale.ROOT);
        if (!"xlsx".equals(value) && !"pdf".equals(value)) {
            throw new ApiException(400, "VALIDATION_FAILED", "format 必须为 xlsx 或 pdf",
                    Map.of("field", "format", "value", raw));
        }
        return value;
    }

    private static Map<String, Object> section(Map<String, Object> snapshot, String name) {
        return map(snapshot.get(name));
    }

    private static Map<String, Object> map(Object raw) {
        if (!(raw instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }

    private static List<Map<String, Object>> list(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List<?> items) {
            for (Object item : items) {
                out.add(map(item));
            }
        }
        return out;
    }

    private static Object number(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        if (raw instanceof Number) {
            return raw;
        }
        try {
            return new BigDecimal(raw.toString()).doubleValue();
        } catch (NumberFormatException ex) {
            return raw.toString();
        }
    }

    private static String format(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Double numeric) {
            if (numeric == Math.rint(numeric)) {
                return String.valueOf(numeric.longValue());
            }
            return BigDecimal.valueOf(numeric).stripTrailingZeros().toPlainString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        return value.toString();
    }

    private static String safe(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String label(String key) {
        return switch (key) {
            case "planned" -> "计划用例数";
            case "executed" -> "已执行";
            case "passed" -> "通过";
            case "failed" -> "失败";
            case "blocked" -> "阻塞";
            case "skipped" -> "跳过";
            case "notRun" -> "未执行";
            default -> key;
        };
    }
}
