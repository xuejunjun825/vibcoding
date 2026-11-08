package com.example.deliver.sprint;

import java.util.List;

import com.example.deliver.sprint.SprintViews.CreateSprint;
import com.example.deliver.sprint.SprintViews.SprintBoard;
import com.example.deliver.sprint.SprintViews.SprintView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sprints")
public class SprintController {

    private final SprintService sprints;

    public SprintController(SprintService sprints) {
        this.sprints = sprints;
    }

    @GetMapping
    public List<SprintView> list() {
        return sprints.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SprintView create(@RequestBody CreateSprint cmd) {
        return sprints.create(cmd);
    }

    @GetMapping("/{id}")
    public SprintView detail(@PathVariable long id) {
        return sprints.detail(id);
    }

    @GetMapping("/{id}/board")
    public SprintBoard board(@PathVariable long id) {
        return sprints.board(id);
    }

    /** 409 {@code SPRINT_HAS_OPEN_TASKS} while any task is not DONE. */
    @PostMapping("/{id}/close")
    public SprintView close(@PathVariable long id) {
        return sprints.close(id);
    }
}
