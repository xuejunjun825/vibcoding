package com.example.req.sys;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.platform.common.scope.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tenant administration: list and provision (AC-1). */
@RestController
@RequestMapping("/admin/tenants")
public class AdminTenantController {

    private final SysDb db;
    private final TenantProvisioner provisioner;

    public AdminTenantController(SysDb db, TenantProvisioner provisioner) {
        this.db = db;
        this.provisioner = provisioner;
    }

    @GetMapping
    public PageResponse<SysViews.TenantRow> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int pageNo = SysDb.pageNo(page);
        int pageSize = SysDb.pageSize(size);
        long total = db.count("SELECT COUNT(*) FROM sys_tenant");
        List<SysViews.TenantRow> items = new ArrayList<>();
        for (Object[] row : db.rows("SELECT id, code, name, status, created_at FROM sys_tenant"
                + " ORDER BY id LIMIT ? OFFSET ?", pageSize, (pageNo - 1) * pageSize)) {
            items.add(new SysViews.TenantRow(SysDb.asLong(row[0]), SysDb.asString(row[1]),
                    SysDb.asString(row[2]), SysDb.asString(row[3]), SysDb.asIso(row[4])));
        }
        return new PageResponse<>(items, total, pageNo, pageSize);
    }

    @PostMapping
    public ResponseEntity<SysViews.CreatedTenant> create(@RequestBody Map<String, Object> body) {
        String code = text(body.get("code"));
        String name = text(body.get("name"));
        String adminUsername = text(body.get("adminUsername"));
        String adminPassword = text(body.get("adminPassword"));
        SysViews.CreatedTenant created = provisioner.provision(code, name, adminUsername,
                adminPassword);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
