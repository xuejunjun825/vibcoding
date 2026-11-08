package com.example.req.requirement;

import com.example.req.requirement.RequirementViews.View;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.Writer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 需求导出：首行水位线 + 表头 + 与列表同源的行。
 *
 * <p>行数必须等于同一筛选条件下的 {@code total}（AC-11）：调用方用 {@code page.total} 校验，
 * 本类不做任何二次过滤（权限过滤已发生在 ScopedJdbc 的 SQL 里）。</p>
 */
@Component
public class RequirementExport {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public String watermark(long tenantId, long total, PoolFilter filter) {
        return "# exporter=req-service tenant=" + tenantId + " total=" + total
                + " type=" + nvl(filter.type()) + " status=" + nvl(filter.status())
                + " exportedAt=" + LocalDateTime.now().format(TS);
    }

    public void writeHeader(PrintWriter out) {
        out.println("code,type,status,priority,title,parentCode,domainId,assigneeVendorId,ownerUserId,"
                + "estimateDays,actualDays,mrUrl,expectedDeliveryDate,planEndDate,updatedAt");
    }

    public void writeRows(Writer writer, List<View> rows) {
        PrintWriter out = writer instanceof PrintWriter printWriter ? printWriter : new PrintWriter(writer);
        for (View row : rows) {
            out.println(String.join(",",
                    csv(row.code()), csv(row.type()), csv(row.status()), csv(row.priority()),
                    csv(row.title()), csv(row.parentCode()), csv(row.domainId()),
                    csv(row.assigneeVendorId()), csv(row.ownerUserId()),
                    csv(row.estimateDays()), csv(row.actualDays()), csv(row.mrUrl()),
                    csv(row.expectedDeliveryDate()), csv(row.planEndDate()), csv(row.updatedAt())));
        }
        out.flush();
    }

    private static String csv(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }
}
