package com.example.deliver.sprint;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.deliver.sprint.SprintViews.CreateSprint;
import com.example.deliver.sprint.SprintViews.SprintBoard;
import com.example.deliver.sprint.SprintViews.SprintView;
import com.example.deliver.task.TaskService;
import com.example.deliver.task.TaskViews.TaskView;
import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.MetricFormulas;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Iterations: create, list, show the board, close.
 *
 * <p>Closing is guarded: a sprint with work still open refuses to close (409
 * {@code SPRINT_HAS_OPEN_TASKS}) instead of silently losing the unfinished items or pushing them
 * into the next sprint behind the team's back.</p>
 */
@Service
public class SprintService {

    private static final String CLOSED = "CLOSED";
    static final List<String> COLUMNS = List.of("TODO", "DOING", "BLOCKED", "DONE");

    private final SprintRepository sprints;
    private final TaskService tasks;
    private final AuditService audit;

    public SprintService(SprintRepository sprints, TaskService tasks, AuditService audit) {
        this.sprints = sprints;
        this.tasks = tasks;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<SprintView> list() {
        DataScope scope = TenantContext.require();
        return sprints.findByTenantIdOrderByStartDateDescIdDesc(scope.tenantId())
                .stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public SprintView detail(long id) {
        return view(load(id));
    }

    @Transactional
    public SprintView create(CreateSprint cmd) {
        DataScope scope = TenantContext.require();
        if (cmd == null || cmd.name() == null || cmd.name().isBlank()) {
            throw new ApiException(400, "VALIDATION_FAILED", "迭代名称不能为空", null);
        }
        if (cmd.startDate() == null || cmd.endDate() == null) {
            throw new ApiException(400, "VALIDATION_FAILED", "迭代必须给出起止日期", null);
        }
        if (cmd.endDate().isBefore(cmd.startDate())) {
            throw new ApiException(400, "VALIDATION_FAILED", "迭代结束日期不能早于开始日期", null);
        }
        SprintEntity entity = new SprintEntity();
        entity.setTenantId(scope.tenantId());
        // releaseId is optional: an iteration may start before its release exists.
        entity.setReleaseId(cmd.releaseId());
        entity.setName(cmd.name().trim());
        entity.setGoal(cmd.goal());
        entity.setStartDate(cmd.startDate());
        entity.setEndDate(cmd.endDate());
        entity.setCapacityDays(cmd.capacityDays());
        entity.setStatus("PLANNING");
        entity.setCreatedBy(scope.userId());
        entity.setUpdatedBy(scope.userId());
        sprints.save(entity);
        audit.record("SPRINT_CREATE", "SPRINT", entity.getId(), Map.of("name", entity.getName()));
        return view(entity);
    }

    @Transactional(readOnly = true)
    public SprintBoard board(long id) {
        SprintEntity sprint = load(id);
        List<TaskView> items = tasks.bySprint(id);
        Map<String, List<TaskView>> columns = new LinkedHashMap<>();
        for (String column : COLUMNS) {
            columns.put(column, new ArrayList<>());
        }
        for (TaskView item : items) {
            columns.computeIfAbsent(item.status(), key -> new ArrayList<>()).add(item);
        }
        long open = items.stream().filter(t -> !"DONE".equals(t.status())).count();
        return new SprintBoard(view(sprint),
                columns, new ArrayList<>(columns.keySet()), open);
    }

    /** Close a finished iteration; refuses while any task is not DONE. */
    @Transactional
    public SprintView close(long id) {
        DataScope scope = TenantContext.require();
        SprintEntity sprint = load(id);
        if (CLOSED.equals(sprint.getStatus())) {
            return view(sprint);
        }
        List<TaskView> items = tasks.bySprint(id);
        List<TaskView> open = items.stream().filter(t -> !"DONE".equals(t.status())).toList();
        if (!open.isEmpty()) {
            throw new ApiException(409, "SPRINT_HAS_OPEN_TASKS",
                    "迭代还有 " + open.size() + " 个未完成任务，处理完或移出后再关闭",
                    Map.of("openTasks", open.size(),
                            "tasks", open.stream().map(TaskView::id).toList()));
        }
        sprint.setStatus(CLOSED);
        sprint.setUpdatedBy(scope.userId());
        sprints.save(sprint);
        audit.record("SPRINT_CLOSE", "SPRINT", sprint.getId(), Map.of("tasks", items.size()));
        return view(sprint);
    }

    private SprintEntity load(long id) {
        DataScope scope = TenantContext.require();
        SprintEntity entity = sprints.findById(id)
                .orElseThrow(() -> new ApiException(404, "SPRINT_NOT_FOUND", "迭代不存在", null));
        if (entity.getTenantId() != scope.tenantId()) {
            throw new ApiException(404, "SPRINT_NOT_FOUND", "迭代不存在", null);
        }
        return entity;
    }

    private SprintView view(SprintEntity entity) {
        List<TaskView> items = tasks.bySprint(entity.getId());
        long done = items.stream().filter(t -> "DONE".equals(t.status())).count();
        BigDecimal rate = MetricFormulas.passRate(done, items.size());
        return new SprintView(entity.getId(), entity.getReleaseId(), entity.getName(), entity.getGoal(),
                entity.getStartDate(), entity.getEndDate(), entity.getCapacityDays(), entity.getStatus(),
                items.size(), done, rate, entity.getUpdatedAt());
    }
}
