package com.example.req.requirement;

import java.time.LocalDate;
import java.util.Locale;

/**
 * 需求池筛选条件（AC-11 的 8 个筛选项 + 类型 Tab + 分页）。
 *
 * @param type      BUS/PDR/RD，null 表示全部
 * @param view      agg（父需求聚合）/ flat（平铺）；两者使用完全相同的 WHERE 与排序
 * @param overdue   true 只返回 plan_end_date 已过且未完成的需求
 */
public record PoolFilter(String type, String keyword, Long parentId, Long releaseId, Long ownerId,
                         String priority, String status, Long domainId, Long vendorId, Long orgId,
                         LocalDate dateFrom, LocalDate dateTo, String view, Boolean overdue,
                         int page, int size) {

    public static final int MAX_SIZE = 200;

    public PoolFilter {
        type = blankToNull(type);
        keyword = blankToNull(keyword);
        priority = blankToNull(priority);
        status = blankToNull(status);
        view = view == null || view.isBlank() ? "flat" : view.toLowerCase(Locale.ROOT);
        if (!"agg".equals(view) && !"flat".equals(view)) {
            view = "flat";
        }
        if (page < 1) {
            page = 1;
        }
        if (size < 1) {
            size = 20;
        }
        size = Math.min(size, MAX_SIZE);
    }

    public boolean aggregate() {
        return "agg".equals(view);
    }

    public int offset() {
        return (page - 1) * size;
    }

    public PoolFilter forExport() {
        return new PoolFilter(type, keyword, parentId, releaseId, ownerId, priority, status,
                domainId, vendorId, orgId, dateFrom, dateTo, view, overdue, 1, MAX_SIZE);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
