package com.example.req.deploy;

import com.example.req.requirement.RequirementViews;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 发布单接口（TECHNICAL §4.3）。网关已 StripPrefix {@code /api/req}，这里的路径是服务内路径。
 *
 * <p>提交返回 200 + {@code {status}}；准入不通过由 {@link DeployErrors.AdmissionFailedException}
 * 统一映射成 409 + {@code {code:"ADMISSION_FAILED", detail:{items:[...]}}}，前端据此渲染常驻清单。</p>
 */
@RestController
@RequestMapping("/deploy-orders")
public class DeployOrderController {

    private final DeployOrderService service;

    public DeployOrderController(DeployOrderService service) {
        this.service = service;
    }

    @GetMapping
    public RequirementViews.Page<DeployViews.OrderRow> page(
            @RequestParam(required = false) Long releaseId,
            @RequestParam(required = false) String env,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.page(releaseId, env, status, page, size);
    }

    @GetMapping("/stats")
    public DeployViews.DeployStats stats(
            @RequestParam(required = false) Long releaseId,
            @RequestParam(required = false) String env,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.stats(releaseId, env, from, to);
    }

    @PostMapping
    public ResponseEntity<DeployViews.OrderDetail> create(@RequestBody DeployOrderService.CreateOrder cmd) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(cmd));
    }

    @GetMapping("/{id}")
    public DeployViews.OrderDetail detail(@PathVariable long id) {
        return service.detail(id);
    }

    /** 只做判定与快照落库，不改状态——页面进详情时先渲染这张清单，再决定是否可点「提交」。 */
    @PostMapping("/{id}/evaluate")
    public DeployViews.AdmissionSnapshot evaluate(@PathVariable long id) {
        return service.evaluate(id);
    }

    @PostMapping("/{id}/submit")
    public DeployViews.SubmitResult submit(@PathVariable long id) {
        return service.submit(id);
    }

    /** 受控放行：reason 缺失/空白 → 400 REASON_REQUIRED（先于权限判断，页面据此提示"请填原因"）。 */
    @PostMapping("/{id}/force-release")
    public DeployViews.OrderDetail forceRelease(@PathVariable long id,
                                                @RequestBody(required = false) DeployOrderService.ForceReleaseCmd cmd) {
        return service.forceRelease(id, cmd == null ? null : cmd.reason());
    }

    @PostMapping("/{id}/execution")
    public DeployViews.OrderDetail execution(@PathVariable long id,
                                             @RequestBody DeployOrderService.ExecutionCmd cmd) {
        return service.recordExecution(id, cmd);
    }
}
