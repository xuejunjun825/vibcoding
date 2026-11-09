package com.example.deliver.quality.plan;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Round endpoints (AC-2): one RUNNING round per plan, scope pinned at open time, close frozen.
 */
@RestController
@RequestMapping("/test-plans")
public class RoundController {

    private final RoundService rounds;

    public RoundController(RoundService rounds) {
        this.rounds = rounds;
    }

    @GetMapping("/{id}/rounds")
    public List<PlanViews.RoundView> list(@PathVariable long id) {
        return rounds.list(id);
    }

    @PostMapping("/{id}/rounds")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanViews.RoundView open(@PathVariable long id,
                                    @RequestBody(required = false) PlanViews.OpenRound cmd) {
        return rounds.open(id, cmd);
    }

    @GetMapping("/{id}/rounds/{roundId}")
    public PlanViews.RoundDetail detail(@PathVariable long id, @PathVariable long roundId) {
        return rounds.detail(id, roundId);
    }

    @PostMapping("/{id}/rounds/{roundId}/close")
    public PlanViews.RoundDetail close(@PathVariable long id, @PathVariable long roundId,
                                       @RequestBody(required = false) PlanViews.CloseRound cmd) {
        return rounds.close(id, roundId, cmd);
    }
}
