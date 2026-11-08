package com.example.deliver.task;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.example.deliver.reqread.ReqReadModel;
import com.example.deliver.reqread.RequirementSnapshot;
import com.example.deliver.support.Visibility;
import com.example.deliver.task.TaskViews.CreateTask;
import com.example.deliver.task.TaskViews.MoveTask;
import com.example.deliver.task.TaskViews.PatchTask;
import com.example.deliver.task.TaskViews.TaskProgress;
import com.example.deliver.task.TaskViews.TaskView;
import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.MetricFormulas;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Task bookkeeping under a requirement: create, assign, move between board columns, and the
 * completion rollup the requirement page reads.
 *
 * <p>Status is a fixed four-value vocabulary, not a free string: {@code TODO}, {@code DOING},
 * {@code DONE}, {@code BLOCKED}. Anything else is rejected instead of silently creating a fifth
 * column nobody's board renders.</p>
 */
@Service
public class TaskService {

    private static final Set<String> STATUSES = Set.of("TODO", "DOING", "DONE", "BLOCKED");
    private static final String DONE = "DONE";

    private final TaskRepository tasks;
    private final ReqReadModel readModel;
    private final AuditService audit;

    public TaskService(TaskRepository tasks, ReqReadModel readModel, AuditService audit) {
        this.tasks = tasks;
        this.readModel = readModel;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<TaskView> list(long requirementId) {
        DataScope scope = TenantContext.require();
        return tasks.findByTenantIdAndRequirementIdOrderBySortNoAscIdAsc(scope.tenantId(), requirementId)
                .stream().filter(this::visible).map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public TaskProgress progress(long requirementId) {
        List<TaskView> items = list(requirementId);
        long todo = items.stream().filter(t -> "TODO".equals(t.status())).count();
        long doing = items.stream().filter(t -> "DOING".equals(t.status())).count();
        long done = items.stream().filter(t -> DONE.equals(t.status())).count();
        long blocked = items.stream().filter(t -> "BLOCKED".equals(t.status())).count();
        return new TaskProgress(requirementId, items.size(), todo, doing, done, blocked,
                MetricFormulas.passRate(done, items.size()), items);
    }

    @Transactional
    public TaskView create(CreateTask cmd) {
        DataScope scope = TenantContext.require();
        if (cmd == null || cmd.requirementId() == null) {
            throw new ApiException(400, "REQUIREMENT_REQUIRED", "任务必须挂在需求下", null);
        }
        if (cmd.title() == null || cmd.title().isBlank()) {
            throw new ApiException(400, "VALIDATION_FAILED", "任务标题不能为空", null);
        }
        RequirementSnapshot requirement = readModel.get(cmd.requirementId());
        if (requirement == null) {
            throw new ApiException(404, "REQUIREMENT_NOT_FOUND", "需求不存在或不在你的数据范围内", null);
        }
        if (cmd.startDate() != null && cmd.dueDate() != null
                && cmd.dueDate().isBefore(cmd.startDate())) {
            throw new ApiException(400, "VALIDATION_FAILED", "截止日期不能早于开始日期", null);
        }

        TaskEntity entity = new TaskEntity();
        entity.setTenantId(scope.tenantId());
        entity.setRequirementId(requirement.id());
        entity.setSprintId(cmd.sprintId());
        entity.setTitle(cmd.title().trim());
        entity.setAssigneeId(cmd.assigneeId());
        entity.setEstimateHours(cmd.estimateHours());
        entity.setStartDate(cmd.startDate());
        entity.setDueDate(cmd.dueDate());
        entity.setStatus("TODO");
        entity.setSortNo(nextSortNo(requirement.id()));
        // Derived like every other delivery row: the requirement owns domain and vendor.
        entity.setDomainId(requirement.domainId());
        entity.setVendorId(requirement.vendorId());
        entity.setCreatedBy(scope.userId());
        entity.setUpdatedBy(scope.userId());
        tasks.save(entity);
        audit.record("TASK_CREATE", "TASK", entity.getId(), java.util.Map.of(
                "requirementId", requirement.id(),
                "title", entity.getTitle()));
        return view(entity);
    }

    @Transactional
    public TaskView patch(long id, PatchTask cmd) {
        DataScope scope = TenantContext.require();
        TaskEntity entity = load(id);
        if (!visible(entity)) {
            throw new ApiException(403, "FORBIDDEN", "该任务不在你的数据范围内", null);
        }
        if (cmd == null) {
            return view(entity);
        }
        if (cmd.title() != null) {
            if (cmd.title().isBlank()) {
                throw new ApiException(400, "VALIDATION_FAILED", "任务标题不能为空", null);
            }
            entity.setTitle(cmd.title().trim());
        }
        if (cmd.sprintId() != null) {
            entity.setSprintId(cmd.sprintId());
        }
        if (cmd.assigneeId() != null) {
            entity.setAssigneeId(cmd.assigneeId());
        }
        if (cmd.estimateHours() != null) {
            entity.setEstimateHours(cmd.estimateHours());
        }
        if (cmd.startDate() != null) {
            entity.setStartDate(cmd.startDate());
        }
        if (cmd.dueDate() != null) {
            entity.setDueDate(cmd.dueDate());
        }
        if (cmd.status() != null) {
            entity.setStatus(requireStatus(cmd.status()));
        }
        if (cmd.sortNo() != null) {
            entity.setSortNo(cmd.sortNo());
        }
        entity.setUpdatedBy(scope.userId());
        tasks.save(entity);
        return view(entity);
    }

    /** The board's drag handler: status and position in one write. */
    @Transactional
    public TaskView move(long id, MoveTask cmd) {
        DataScope scope = TenantContext.require();
        TaskEntity entity = load(id);
        if (!visible(entity)) {
            throw new ApiException(403, "FORBIDDEN", "该任务不在你的数据范围内", null);
        }
        if (cmd == null || cmd.status() == null) {
            throw new ApiException(400, "VALIDATION_FAILED", "移动任务必须给出目标状态", null);
        }
        entity.setStatus(requireStatus(cmd.status()));
        if (cmd.sortNo() != null) {
            entity.setSortNo(cmd.sortNo());
        }
        entity.setUpdatedBy(scope.userId());
        tasks.save(entity);
        return view(entity);
    }

    /** Done/total per sprint, used by the sprint board header. */
    @Transactional(readOnly = true)
    public List<TaskView> bySprint(Long sprintId) {
        DataScope scope = TenantContext.require();
        return tasks.findByTenantIdAndSprintIdOrderBySortNoAscIdAsc(scope.tenantId(), sprintId)
                .stream().filter(this::visible).map(this::view).toList();
    }

    public long openTasks(long sprintId) {
        DataScope scope = TenantContext.require();
        return tasks.countByTenantIdAndSprintIdAndStatusNot(scope.tenantId(), sprintId, DONE);
    }

    private int nextSortNo(long requirementId) {
        List<TaskEntity> existing = tasks.findByTenantIdAndRequirementIdOrderBySortNoAscIdAsc(
                TenantContext.require().tenantId(), requirementId);
        return existing.stream().mapToInt(TaskEntity::getSortNo).max().orElse(0) + 10;
    }

    private TaskEntity load(long id) {
        DataScope scope = TenantContext.require();
        TaskEntity entity = tasks.findById(id)
                .orElseThrow(() -> new ApiException(404, "TASK_NOT_FOUND", "任务不存在", null));
        if (entity.getTenantId() != scope.tenantId()) {
            throw new ApiException(404, "TASK_NOT_FOUND", "任务不存在", null);
        }
        return entity;
    }

    private boolean visible(TaskEntity entity) {
        DataScope scope = TenantContext.require();
        return Visibility.canSee(scope, entity.getTenantId(), entity.getDomainId(), entity.getVendorId());
    }

    private String requireStatus(String status) {
        String upper = status.trim().toUpperCase();
        if (!STATUSES.contains(upper)) {
            throw new ApiException(400, "VALIDATION_FAILED", "任务状态只能是 TODO/DOING/DONE/BLOCKED",
                    java.util.Map.of("allowed", new ArrayList<>(STATUSES)));
        }
        return upper;
    }

    private TaskView view(TaskEntity entity) {
        return new TaskView(entity.getId(), entity.getRequirementId(), entity.getSprintId(),
                entity.getTitle(), entity.getAssigneeId(), entity.getEstimateHours(),
                entity.getStartDate(), entity.getDueDate(), entity.getStatus(), entity.getSortNo(),
                entity.getDomainId(), entity.getVendorId(), entity.getUpdatedAt());
    }
}
