package com.example.req.sys;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.platform.common.scope.PageResponse;
import com.example.platform.common.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** In-app notifications for the signed-in user. */
@RestController
@RequestMapping("/notifications")
@Transactional
public class NotificationController {

    public record NotificationRow(long id, String type, String title, String content,
                                  String bizType, Long bizId, boolean readFlag, String createdAt) {
    }

    private final SysDb db;

    public NotificationController(SysDb db) {
        this.db = db;
    }

    @GetMapping
    public PageResponse<NotificationRow> list(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        var scope = TenantContext.require();
        long tenantId = scope.tenantId();
        long userId = scope.userId();
        int pageNo = SysDb.pageNo(page);
        int pageSize = SysDb.pageSize(size);
        String where = " FROM notification WHERE tenant_id = ? AND user_id = ?"
                + (unreadOnly ? " AND read_flag = 0" : "");
        long total = db.count("SELECT COUNT(*)" + where, tenantId, userId);
        List<NotificationRow> items = new ArrayList<>();
        for (Object[] row : db.rows("SELECT id, type, title, content, biz_type, biz_id, read_flag,"
                + " created_at" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                tenantId, userId, pageSize, (pageNo - 1) * pageSize)) {
            items.add(new NotificationRow(SysDb.asLong(row[0]), SysDb.asString(row[1]),
                    SysDb.asString(row[2]), SysDb.asString(row[3]), SysDb.asString(row[4]),
                    SysDb.asNullableLong(row[5]), SysDb.asBoolean(row[6]), SysDb.asIso(row[7])));
        }
        return new PageResponse<>(items, total, pageNo, pageSize);
    }

    @PostMapping("/{id}/read")
    public Map<String, Object> markRead(@PathVariable long id) {
        var scope = TenantContext.require();
        int updated = db.update("UPDATE notification SET read_flag = 1"
                        + " WHERE tenant_id = ? AND user_id = ? AND id = ?",
                scope.tenantId(), scope.userId(), id);
        return Map.of("id", id, "updated", updated);
    }

    @PostMapping("/read-all")
    public Map<String, Object> markAllRead() {
        var scope = TenantContext.require();
        int updated = db.update("UPDATE notification SET read_flag = 1"
                        + " WHERE tenant_id = ? AND user_id = ? AND read_flag = 0",
                scope.tenantId(), scope.userId());
        return Map.of("updated", updated);
    }
}
