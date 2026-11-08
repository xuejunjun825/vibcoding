package com.example.req.sys;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.platform.common.scope.PageResponse;
import com.example.platform.common.tenant.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Audit trail of the current tenant (403s land here, written in their own transaction). */
@RestController
@RequestMapping("/admin/audit")
public class AdminAuditController {

    private final SysDb db;

    public AdminAuditController(SysDb db) {
        this.db = db;
    }

    @GetMapping
    public PageResponse<SysViews.AuditRow> list(
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) Long bizId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        long tenantId = TenantContext.require().tenantId();
        int pageNo = SysDb.pageNo(page);
        int pageSize = SysDb.pageSize(size);
        StringBuilder where = new StringBuilder(
                " FROM audit_log a LEFT JOIN sys_user u ON u.id = a.user_id"
                        + " AND u.tenant_id = a.tenant_id WHERE a.tenant_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        if (bizType != null && !bizType.isBlank()) {
            where.append(" AND a.biz_type = ?");
            params.add(bizType);
        }
        if (bizId != null) {
            where.append(" AND a.biz_id = ?");
            params.add(bizId);
        }
        if (userId != null) {
            where.append(" AND a.user_id = ?");
            params.add(userId);
        }
        if (action != null && !action.isBlank()) {
            where.append(" AND a.action = ?");
            params.add(action);
        }
        long total = db.count("SELECT COUNT(*)" + where, params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(pageSize);
        pageParams.add((pageNo - 1) * pageSize);
        List<SysViews.AuditRow> items = new ArrayList<>();
        for (Object[] row : db.rows("SELECT a.id, a.action, a.biz_type, a.biz_id, u.name,"
                + " a.detail_json, a.created_at" + where
                + " ORDER BY a.id DESC LIMIT ? OFFSET ?", pageParams.toArray())) {
            items.add(new SysViews.AuditRow(SysDb.asLong(row[0]), SysDb.asString(row[1]),
                    SysDb.asString(row[2]), SysDb.asNullableLong(row[3]), SysDb.asString(row[4]),
                    SysDb.asString(row[5]), SysDb.asIso(row[6])));
        }
        return new PageResponse<>(items, total, pageNo, pageSize);
    }
}
