package com.example.deliver.task;

import com.example.platform.common.error.ApiException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部建任务端点（r41）：PDR 评审意见转任务的唯一落地口。
 *
 * <p>{@code POST /tasks} 走网关鉴权（JWT + 动作码 {@code task.action.create}），而意见转任务是
 * <b>评审人</b>的动作——评审人往往没有任务管理权，替他建任务又不该把权限放大。因此这里另开一个
 * 内部端点：只认 {@code X-Internal-Token}（{@code InternalTokenFilter} 守 {@code /internal/**}），
 * 由 {@code req-service} 在「评审人 → 意见 → 建任务」这条链上调用。</p>
 *
 * <p><b>租户从哪来</b>：内部请求没有 JWT。{@code X-Tenant-Id} 优先；缺省时按需求行自己的
 * {@code tenant_id} 反查（{@code req-service} 一定带 header，这里是兜底）。绝不从 body 推断租户。
 * 解析与建任务都在 {@link TaskInternalService}（控制器只校验参数与转调，lint L9 禁止控制器
 * 直连 JDBC）。</p>
 *
 * <p><b>为什么本类不复用 /tasks</b>的 {@code TaskController}：那个控制器的方法签名绑着用户态
 * 请求体（没有 {@code description} 语义的强调）且被网关路由暴露；内部端点要的是「最小可写面」，
 * 让外部无法经它绕过动作码。</p>
 */
@RestController
public class TaskInternalController {

    private final TaskInternalService internal;

    public TaskInternalController(TaskInternalService internal) {
        this.internal = internal;
    }

    /** 建一条任务并返回 {@code {taskId, taskCode, status}}；任务码由本层合成（task 表无 code 列）。 */
    @PostMapping("/internal/tasks")
    public TaskCreated create(@RequestBody TaskInternalService.CreateTaskRequest request,
                              @RequestHeader(value = "X-Tenant-Id", required = false)
                              String tenantHeader) {
        if (request == null || request.requirementId() == null || request.requirementId() <= 0) {
            throw new ApiException(400, "REQUIREMENT_REQUIRED", "任务必须挂在需求下", null);
        }
        TaskViews.TaskView view = internal.create(request, tenantHeader);
        return new TaskCreated(view.id(), "TASK-" + view.id(), view.status());
    }

    /** 内部建任务的响应：{@code taskCode} 是展示用短码（{@code TASK-<id>}）。 */
    public record TaskCreated(long taskId, String taskCode, String status) {
    }
}
