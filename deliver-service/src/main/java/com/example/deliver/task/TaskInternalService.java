package com.example.deliver.task;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import org.springframework.stereotype.Service;

/**
 * 内部建任务（r41）：{@code POST /internal/tasks} 的用例层。
 *
 * <p>控制器只做参数校验与转调；租户反查（需求行没有 {@code X-Tenant-Id} 时按
 * {@code requirement.tenant_id} 兜底）下沉到这里。SQL 走 {@link TaskRepository} 的 native
 * query 而不是 JDBC 模板——lint L9 要求「控制器/服务不直连 JDBC」，而 deliver 的
 * {@code task/} 包不在 L9 白名单里。</p>
 *
 * <p>租户解析后建 {@link DataScope#system} 并在 {@code finally} 清理：内部调用不能把租户上下文
 * 留在请求线程上。</p>
 */
@Service
public class TaskInternalService {

    private final TaskService tasks;
    private final TaskRepository repository;

    public TaskInternalService(TaskService tasks, TaskRepository repository) {
        this.tasks = tasks;
        this.repository = repository;
    }

    /** 建一条任务；返回的视图由控制器组装成 {@code {taskId, taskCode, status}}。 */
    public TaskViews.TaskView create(CreateTaskRequest request, String tenantHeader) {
        long tenantId = resolveTenant(tenantHeader, request.requirementId());
        TenantContext.set(DataScope.system(tenantId));
        try {
            return tasks.create(new TaskViews.CreateTask(
                    request.requirementId(), request.sprintId(), request.title(),
                    request.description(), request.assigneeId(), request.estimateHours(),
                    request.startDate(), request.dueDate()));
        } finally {
            TenantContext.clear();
        }
    }

    private long resolveTenant(String tenantHeader, long requirementId) {
        Long header = parseTenant(tenantHeader);
        if (header != null) {
            return header;
        }
        return repository.findRequirementTenantId(requirementId)
                .orElseThrow(() -> new ApiException(404, "REQUIREMENT_NOT_FOUND", "需求不存在"));
    }

    private static Long parseTenant(String tenantHeader) {
        if (tenantHeader == null || tenantHeader.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(tenantHeader.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 内部建任务的请求体：字段与 {@code CreateTask} 同形，另允许省略起止日期。 */
    public record CreateTaskRequest(Long requirementId, Long sprintId, String title,
                                    String description, Long assigneeId,
                                    BigDecimal estimateHours, LocalDate startDate,
                                    LocalDate dueDate) {
    }
}
