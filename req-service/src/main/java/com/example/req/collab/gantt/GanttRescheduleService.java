package com.example.req.collab.gantt;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 甘特改期的唯一写入口（AC-2）：范围校验 → 父级窗口 → 二次确认码 → {@code updated_at} 乐观锁 →
 * 同事务写审计。任何散在 controller 的 UPDATE 都会漏审计，所以这里之外不写计划期。
 *
 * <p>错误契约：400 GANTT_INVALID_RANGE、403 FORBIDDEN（无权限或非本域）、404 NOT_FOUND、
 * 409 GANTT_OUT_OF_PARENT_WINDOW / GANTT_CONFIRM_INVALID / GANTT_VERSION_CONFLICT。</p>
 */
@Service
public class GanttRescheduleService {

    public static final String ACTION = "GANTT_RESCHEDULE";
    public static final String FUNCTION = "GANTT_EDIT";

    private final GanttQuery query;
    private final GanttConfirmTokens tokens;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;

    public GanttRescheduleService(GanttQuery query,
                                  GanttConfirmTokens tokens,
                                  AuditService auditService,
                                  JdbcTemplate jdbc) {
        this.query = query;
        this.tokens = tokens;
        this.auditService = auditService;
        this.jdbc = jdbc;
    }

    @Transactional
    public GanttViews.RescheduleResult reschedule(String type, long id, GanttViews.RescheduleCmd command) {
        String rowType = normalizeType(type);
        if (rowType == null) {
            throw notFound("未知的甘特行类型：" + type);
        }
        if (!TenantContext.can(FUNCTION)) {
            throw new ApiException(403, "FORBIDDEN", "无甘特改期权限", Map.of());
        }

        GanttQuery.Item item = query.loadOne(rowType, id);
        if (item == null) {
            // 同租户内存在但不在我的域/厂商范围 → 403（AC-2 负向③）；真不存在才 404。
            if (query.existsInTenant(rowType, id)) {
                throw new ApiException(403, "FORBIDDEN", "该行不在你的数据范围内", Map.of());
            }
            throw notFound("甘特行不存在：" + id);
        }

        GanttViews.RescheduleCmd cmd = command == null
                ? new GanttViews.RescheduleCmd(null, null, null, null)
                : command;
        LocalDate start = cmd.planStart() != null ? cmd.planStart() : item.planStart();
        LocalDate end = cmd.planEnd() != null ? cmd.planEnd() : item.planEnd();
        if (start == null || end == null) {
            throw new ApiException(400, "GANTT_INVALID_RANGE", "改期需要同时给出开始与结束日期", Map.of());
        }
        if (start.isAfter(end)) {
            throw new ApiException(400, "GANTT_INVALID_RANGE", "开始日期不能晚于结束日期",
                    Map.of("planStart", start.toString(), "planEnd", end.toString()));
        }
        if (cmd.version() == null || cmd.version().isBlank() || !cmd.version().equals(item.version())) {
            throw versionConflict(item.version());
        }

        GanttQuery.Window window = query.parentWindow(rowType, item);
        boolean outOfWindow = window != null && !window.contains(start, end);
        boolean confirmed = false;
        if (outOfWindow) {
            if (cmd.confirmToken() == null || cmd.confirmToken().isBlank()) {
                String token = tokens.issue(rowType, id, start, end);
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("parentWindow", Map.of("start", text(window.start()), "end", text(window.end())));
                detail.put("violatingDays", window.violatingDays(start, end));
                detail.put("confirmToken", token);
                detail.put("expiresAt", tokens.expiresAt(token).toString());
                throw new ApiException(409, "GANTT_OUT_OF_PARENT_WINDOW",
                        "改期超出父级窗口，确认后可保存", detail);
            }
            tokens.verifyAndConsume(cmd.confirmToken(), rowType, id, start, end);
            confirmed = true;
        }

        LocalDateTime expected = GanttQuery.updatedAtOf(item.version());
        int updated = query.updatePlan(rowType, id, start, end, expected, currentUserId());
        if (updated == 0) {
            String current = query.currentVersion(rowType, id);
            if (current == null) {
                throw notFound("甘特行已被删除：" + id);
            }
            throw versionConflict(current);
        }

        auditService.record(ACTION, rowType, id, auditDetail(rowType, id, item, start, end, window, confirmed));
        String newVersion = query.currentVersion(rowType, id);
        return new GanttViews.RescheduleResult(id, rowType, start, end, lastAuditId(),
                newVersion == null ? item.version() : newVersion);
    }

    private Map<String, Object> auditDetail(String type, long id, GanttQuery.Item before,
                                            LocalDate start, LocalDate end,
                                            GanttQuery.Window window, boolean confirmed) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", type);
        detail.put("id", id);
        detail.put("before", Map.of("planStart", text(before.planStart()), "planEnd", text(before.planEnd())));
        detail.put("after", Map.of("planStart", start.toString(), "planEnd", end.toString()));
        if (window != null) {
            detail.put("parentWindow", Map.of("start", text(window.start()), "end", text(window.end())));
        }
        detail.put("confirmed", confirmed);
        detail.put("operator", operator());
        return detail;
    }

    private Map<String, Object> operator() {
        Map<String, Object> operator = new LinkedHashMap<>();
        TenantContext.Principal principal = TenantContext.principal();
        if (principal == null) {
            operator.put("userId", 0L);
            operator.put("name", "");
            return operator;
        }
        operator.put("userId", principal.userId());
        operator.put("name", principal.name() == null ? "" : principal.name());
        return operator;
    }

    private Long currentUserId() {
        TenantContext.Principal principal = TenantContext.principal();
        return principal == null ? null : principal.userId();
    }

    /**
     * 同事务里紧接审计插入读 {@code LAST_INSERT_ID()}：MySQL 的会话级函数，@Transactional 下与
     * AuditService 共用一条连接。拿不到就回 0——审计 id 只是回执，不能让保存因此失败。
     */
    private long lastAuditId() {
        try {
            Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            return id == null ? 0L : id;
        } catch (RuntimeException ex) {
            return 0L;
        }
    }

    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        String normalized = type.trim().toUpperCase();
        return GanttViews.TYPES.contains(normalized) ? normalized : null;
    }

    private static String text(LocalDate date) {
        return date == null ? "" : date.toString();
    }

    private static ApiException notFound(String message) {
        return new ApiException(404, "NOT_FOUND", message, Map.of());
    }

    private static ApiException versionConflict(String currentVersion) {
        return new ApiException(409, "GANTT_VERSION_CONFLICT", "数据已被他人修改，请刷新后重试",
                Map.of("currentVersion", currentVersion == null ? "0" : currentVersion));
    }
}
