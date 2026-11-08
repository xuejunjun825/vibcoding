package com.example.req.approval;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 流程定义管理接口：仅管理员/PMO 可写（权限判定在 {@link WorkflowAdminService} 内，控制器不重复判断）。
 *
 * <p>发布返回固化后的版本号，前端据此提示「v2 已发布，新单据将走 v2，在途单据仍走 v1」。
 */
@RestController
@RequestMapping("/admin/workflows")
public class WorkflowAdminController {

    private final WorkflowAdminService admin;

    public WorkflowAdminController(WorkflowAdminService admin) {
        this.admin = admin;
    }

    @GetMapping
    public List<WorkflowAdminService.WorkflowView> list(@RequestParam(required = false) String bizType) {
        return admin.list(bizType);
    }

    @GetMapping("/{id}")
    public WorkflowAdminService.WorkflowView get(@PathVariable long id) {
        return admin.get(id);
    }

    @PostMapping
    public ResponseEntity<WorkflowAdminService.WorkflowView> create(
            @RequestBody WorkflowAdminService.CreateWorkflow request) {
        // 契约为 201（TECHNICAL §4.3）：创建是新资源，不是幂等更新。
        return ResponseEntity.status(HttpStatus.CREATED).body(admin.create(request));
    }

    @PutMapping("/{id}")
    public WorkflowAdminService.WorkflowView update(@PathVariable long id,
                                                    @RequestBody WorkflowAdminService.UpdateWorkflow request) {
        return admin.update(id, request);
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<WorkflowAdminService.WorkflowView> publish(@PathVariable long id) {
        return ResponseEntity.ok(admin.publish(id));
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<WorkflowAdminService.WorkflowView> disable(@PathVariable long id) {
        return ResponseEntity.ok(admin.disable(id));
    }
}
