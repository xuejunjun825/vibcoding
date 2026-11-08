package com.example.req.requirement;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.tenant.TenantContext;
import com.example.req.requirement.RequirementDtos.AssignVendorRequest;
import com.example.req.requirement.RequirementDtos.CreateCommand;
import com.example.req.requirement.RequirementDtos.CreateRequest;
import com.example.req.requirement.RequirementDtos.DeliverRequest;
import com.example.req.requirement.RequirementDtos.DecomposeRequest;
import com.example.req.requirement.RequirementDtos.QuickTestRequest;
import com.example.req.requirement.RequirementDtos.UpdateRequest;
import com.example.req.requirement.RequirementViews.Created;
import com.example.req.requirement.RequirementViews.Detail;
import com.example.req.requirement.RequirementViews.Page;
import com.example.req.requirement.RequirementViews.QuickTest;
import com.example.req.requirement.RequirementViews.TransitionResult;
import com.example.req.requirement.RequirementViews.View;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 需求接口（TECHNICAL §4.3 上半部分）。 */
@RestController
@RequestMapping("/requirements")
public class RequirementController {

    private final RequirementService requirementService;
    private final RequirementPoolQuery poolQuery;
    private final RequirementExport export;
    private final AuditService auditService;

    public RequirementController(RequirementService requirementService,
                                 RequirementPoolQuery poolQuery,
                                 RequirementExport export,
                                 AuditService auditService) {
        this.requirementService = requirementService;
        this.poolQuery = poolQuery;
        this.export = export;
        this.auditService = auditService;
    }

    @GetMapping
    public Page<View> list(@RequestParam(required = false) String type,
                           @RequestParam(required = false) String keyword,
                           @RequestParam(required = false) Long parentId,
                           @RequestParam(required = false) Long releaseId,
                           @RequestParam(required = false) Long ownerId,
                           @RequestParam(required = false) String priority,
                           @RequestParam(required = false) String status,
                           @RequestParam(required = false) Long domainId,
                           @RequestParam(required = false) Long vendorId,
                           @RequestParam(required = false) Long orgId,
                           @RequestParam(required = false) LocalDate dateFrom,
                           @RequestParam(required = false) LocalDate dateTo,
                           @RequestParam(required = false, defaultValue = "flat") String view,
                           @RequestParam(required = false) Boolean overdue,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "20") int size) {
        return poolQuery.page(new PoolFilter(type, keyword, parentId, releaseId, ownerId, priority,
                status, domainId, vendorId, orgId, dateFrom, dateTo, view, overdue, page, size));
    }

    @GetMapping("/search")
    public Page<View> search(@RequestParam(name = "q", required = false) String q,
                             @RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "20") int size) {
        return poolQuery.page(new PoolFilter(null, q, null, null, null, null, null, null, null,
                null, null, null, "flat", null, page, size));
    }

    @GetMapping("/{id}")
    public Detail detail(@PathVariable long id) {
        return requirementService.detail(id);
    }

    @GetMapping("/{id}/actions")
    public Map<String, Object> allowedActions(@PathVariable long id) {
        return Map.of("id", id, "allowedActions", requirementService.allowedActions(id));
    }

    @PostMapping
    public ResponseEntity<Created> create(@RequestBody CreateRequest request) {
        rejectStatus(request.extra());
        Created created = requirementService.create(new CreateCommand(request.type, request.title,
                request.description, request.domainIds, request.priority, request.expectedDeliveryDate,
                request.parentId, request.ownerUserId, request.estimateDays, request.actualDays));
        return ResponseEntity.status(201).body(created);
    }

    @PutMapping("/{id}")
    public View update(@PathVariable long id, @RequestBody UpdateRequest request) {
        return requirementService.update(id, request);
    }

    /**
     * PATCH 与 PUT 同一实现：AC-3 要求「直接 PATCH status 被拒绝」，
     * 这里必须有映射，否则 Spring 直接 405，脚本断言拿不到 400 STATUS_NOT_WRITABLE
     * （2026-11-09 T11 实测：实际 405 Method Not Allowed）。
     */
    @PatchMapping("/{id}")
    public View patch(@PathVariable long id, @RequestBody UpdateRequest request) {
        return requirementService.update(id, request);
    }

    @PostMapping("/{id}/actions")
    public TransitionResult action(@PathVariable long id,
                                   @RequestBody RequirementDtos.ActionRequest request) {
        if (request.action() == null || request.action().isBlank()) {
            throw RequirementErrors.validation("action 必填");
        }
        return requirementService.transition(id, request.action().trim(), request.comment(),
                "UI", request.definitionId());
    }

    @PostMapping("/{id}/decompose")
    public ResponseEntity<Created> decompose(@PathVariable long id,
                                             @RequestBody DecomposeRequest request) {
        rejectStatus(request.extra());
        return ResponseEntity.status(201).body(requirementService.decompose(id, request));
    }

    @PostMapping("/{id}/assign-vendor")
    public View assignVendor(@PathVariable long id, @RequestBody AssignVendorRequest request) {
        return requirementService.assignVendor(id, request);
    }

    @PostMapping("/{id}/deliver")
    public View deliver(@PathVariable long id, @RequestBody DeliverRequest request) {
        return requirementService.deliver(id, request);
    }

    @PostMapping("/{id}/quick-test")
    public ResponseEntity<QuickTest> quickTest(@PathVariable long id,
                                               @RequestBody QuickTestRequest request) {
        return ResponseEntity.status(201).body(requirementService.quickTest(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        throw RequirementErrors.forbidden("需求不支持物理删除，请使用关闭动作");
    }

    // produces 不能只写 text/csv：客户端带 Accept: application/json 时该映射会被内容协商拒绝，
    // 请求落到 /{id}（id="export.csv"）报 400。这里放开协商、响应头显式声明 CSV。
    @GetMapping(value = "/export.csv", produces = MediaType.ALL_VALUE)
    public void exportCsv(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long parentId,
            @RequestParam(required = false) Long releaseId,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long domainId,
            @RequestParam(required = false) Long vendorId,
            @RequestParam(required = false) Long orgId,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false, defaultValue = "flat") String view,
            @RequestParam(required = false) Boolean overdue,
            HttpServletResponse response) throws IOException {
        PoolFilter filter = new PoolFilter(type, keyword, parentId, releaseId, ownerId, priority,
                status, domainId, vendorId, orgId, dateFrom, dateTo, view, overdue, 1, 20);
        long tenantId = TenantContext.require().tenantId();
        long total = poolQuery.count(filter);

        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"requirements.csv\"");
        response.setHeader("X-Export-Total", Long.toString(total));

        // 同步流式写而不是先拼完整缓冲：10 万行拼串既占内存又慢（AC-11 曾 25s 超时）。
        // 每批一次 flush，keyset 分批直到取空——不设行数上限，否则 CSV 行数会小于
        // X-Export-Total（D-12：100077 行时静默少 77 行，AC-11 的等式不成立）。
        PrintWriter writer = response.getWriter();
        writer.println(export.watermark(tenantId, total, filter));
        export.writeHeader(writer);
        long afterId = 0;
        long written = 0;
        while (true) {
            List<View> batch = poolQuery.exportBatch(filter, afterId, EXPORT_BATCH);
            if (batch.isEmpty()) {
                break;
            }
            export.writeRows(writer, batch);
            afterId = batch.get(batch.size() - 1).id();
            written += batch.size();
            writer.flush();
            if (batch.size() < EXPORT_BATCH) {
                break;
            }
        }
        writer.flush();
        auditService.record("EXPORT", RequirementService.BIZ_TYPE, null,
                Map.of("total", total, "written", written, "type", String.valueOf(type)));
    }

    private static final int EXPORT_BATCH = 1000;

    private void rejectStatus(Map<String, Object> extra) {
        if (extra != null && extra.containsKey("status")) {
            throw RequirementErrors.statusNotWritable();
        }
    }
}
