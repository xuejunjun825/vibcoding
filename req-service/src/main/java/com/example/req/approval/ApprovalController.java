package com.example.req.approval;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审批中心接口（§5.2 端点表）。前端所有审批动作都走这里，不在页面里拼状态。
 *
 * <p>动作类接口一律返回 204 或结构化的 {@code {instanceStatus,nodeStatus}}：
 * 状态判断只发生在服务端，前端拿到的就是「下一步该渲染什么」。
 */
@RestController
@RequestMapping("/approvals")
public class ApprovalController {

    private final ApprovalEngine engine;
    private final ApprovalCenterService center;
    private final ApprovalStore store;

    public ApprovalController(ApprovalEngine engine, ApprovalCenterService center, ApprovalStore store) {
        this.engine = engine;
        this.center = center;
        this.store = store;
    }

    @GetMapping("/todo")
    public PageSlice<ApprovalViews.TaskRow> todo(@RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return center.todo(page, size);
    }

    @GetMapping("/done")
    public PageSlice<ApprovalViews.TaskRow> done(@RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return center.done(page, size);
    }

    @GetMapping("/mine")
    public PageSlice<ApprovalViews.MineRow> mine(@RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return center.mine(page, size);
    }

    @GetMapping("/instances/{id}")
    public ApprovalViews.InstanceDetail detail(@PathVariable long id) {
        return center.detail(id);
    }

    @GetMapping("/instances/{id}/history")
    public ApprovalViews.HistoryView history(@PathVariable long id) {
        return center.history(id);
    }

    /** 通过/驳回。响应里的 {@code nodeStatus} 让前端立刻知道节点是否已过（AC-7/AC-9 的可见证据）。 */
    @PostMapping("/tasks/{taskId}/actions")
    public Map<String, Object> act(@PathVariable long taskId, @RequestBody ActionRequest request) {
        String action = request.action() == null ? "" : request.action().trim().toUpperCase();
        ApprovalTask task = store.task(taskId)
                .orElseThrow(() -> ApprovalException.notFound("审批任务不存在：" + taskId));
        if ("APPROVE".equals(action)) {
            engine.approve(taskId, request.comment());
        } else if ("REJECT".equals(action)) {
            engine.reject(taskId, request.comment());
        } else {
            throw ApprovalException.badRequest(ApprovalException.VALIDATION_FAILED,
                    "action 必须是 APPROVE 或 REJECT，实际：" + request.action());
        }
        ApprovalInstance instance = store.instance(task.getInstanceId())
                .orElseThrow(() -> ApprovalException.notFound("审批实例不存在：" + task.getInstanceId()));
        boolean nodeOpen = store.pendingAt(instance.getId(), task.getNodeKey()).stream()
                .anyMatch(ApprovalTask::isPending);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("taskId", taskId);
        response.put("instanceStatus", instance.getStatus());
        response.put("nodeStatus", nodeOpen ? "PENDING" : "APPROVED");
        return response;
    }

    @PostMapping("/tasks/{taskId}/add-sign")
    public ResponseEntity<Void> addSign(@PathVariable long taskId, @RequestBody AddSignRequest request) {
        engine.addSign(taskId, request.type(), request.userIds(), request.comment());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/tasks/{taskId}/remove-sign")
    public ResponseEntity<Void> removeSign(@PathVariable long taskId, @RequestBody RemoveSignRequest request) {
        if (request.targetUserId() == null) {
            throw ApprovalException.badRequest(ApprovalException.VALIDATION_FAILED, "targetUserId 不能为空");
        }
        engine.removeSign(taskId, request.targetUserId(), request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/tasks/{taskId}/transfer")
    public ResponseEntity<Void> transfer(@PathVariable long taskId, @RequestBody TransferRequest request) {
        if (request.toUserId() == null) {
            throw ApprovalException.badRequest(ApprovalException.VALIDATION_FAILED, "toUserId 不能为空");
        }
        engine.transfer(taskId, request.toUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/tasks/{taskId}/urge")
    public ResponseEntity<Void> urge(@PathVariable long taskId) {
        engine.urge(taskId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/instances/{id}/withdraw")
    public ResponseEntity<Void> withdraw(@PathVariable long id,
                                         @RequestBody(required = false) WithdrawRequest request) {
        engine.withdraw(id);
        return ResponseEntity.noContent().build();
    }

    public record ActionRequest(String action, String comment) {
    }

    public record AddSignRequest(String type, List<Long> userIds, String comment) {
    }

    public record RemoveSignRequest(Long targetUserId, String reason) {
    }

    public record TransferRequest(Long toUserId, String comment) {
    }

    public record WithdrawRequest(String comment) {
    }
}
