package com.example.deliver.quality.strategy;

import com.example.platform.common.scope.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Versioned test strategy endpoints (AC-9). */
@RestController
@RequestMapping("/test-strategies")
public class TestStrategyController {

    private final TestStrategyService strategies;

    public TestStrategyController(TestStrategyService strategies) {
        this.strategies = strategies;
    }

    @GetMapping
    public PageResponse<TestStrategyService.StrategyRow> page(
            @RequestParam(required = false) Long releaseId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return strategies.page(releaseId, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TestStrategyService.StrategyRow create(
            @RequestParam long releaseId,
            @RequestBody(required = false) TestStrategyService.StrategyBody body) {
        return strategies.create(releaseId, body);
    }

    @GetMapping("/{id}")
    public TestStrategyService.StrategyRow detail(@PathVariable long id) {
        return strategies.detail(id);
    }

    @PutMapping("/{id}")
    public TestStrategyService.StrategyRow update(
            @PathVariable long id,
            @RequestBody(required = false) TestStrategyService.StrategyBody body) {
        return strategies.update(id, body);
    }

    @PostMapping("/{id}/activate")
    public TestStrategyService.StrategyRow activate(@PathVariable long id) {
        return strategies.activate(id);
    }
}
