package com.example.deliver.task;

import java.util.List;

import com.example.deliver.task.TaskViews.CreateTask;
import com.example.deliver.task.TaskViews.MoveTask;
import com.example.deliver.task.TaskViews.PatchTask;
import com.example.deliver.task.TaskViews.TaskProgress;
import com.example.deliver.task.TaskViews.TaskView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task endpoints. Created before the sprint domain existed, so the creation path is the plain one:
 * a task may be created unassigned to any sprint and pulled in later with a PATCH.
 */
@RestController
@RequestMapping("/")
public class TaskController {

    private final TaskService tasks;

    public TaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    /** Tasks plus the completion rollup for one requirement. */
    @GetMapping("/requirements/{requirementId}/tasks")
    public TaskProgress byRequirement(@PathVariable long requirementId) {
        return tasks.progress(requirementId);
    }

    @GetMapping("/tasks")
    public List<TaskView> list(@RequestParam long requirementId) {
        return tasks.list(requirementId);
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskView create(@RequestBody CreateTask cmd) {
        return tasks.create(cmd);
    }

    /** The sprint pull: {@code {"sprintId": N}} moves a task into an iteration. */
    @PatchMapping("/tasks/{id}")
    public TaskView patch(@PathVariable long id, @RequestBody PatchTask cmd) {
        return tasks.patch(id, cmd);
    }

    @PostMapping("/tasks/{id}/move")
    public TaskView move(@PathVariable long id, @RequestBody MoveTask cmd) {
        return tasks.move(id, cmd);
    }
}
