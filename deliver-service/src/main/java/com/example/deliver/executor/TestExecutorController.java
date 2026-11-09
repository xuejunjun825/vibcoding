package com.example.deliver.executor;

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

/**
 * Executor ledger endpoints. There is deliberately no health column on the write side: health is
 * derived by {@link ExecutorHealth} on every read, so it can never be hand-set to "ONLINE".
 */
@RestController
@RequestMapping("/test-executors")
public class TestExecutorController {

    private final TestExecutorService service;

    public TestExecutorController(TestExecutorService service) {
        this.service = service;
    }

    @GetMapping
    public TestExecutorService.ExecutorList list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TestExecutorService.ExecutorRow create(
            @RequestBody TestExecutorService.SaveCmd cmd) {
        return service.save(new TestExecutorService.SaveCmd(null,
                cmd == null ? null : cmd.code(), cmd == null ? null : cmd.name(),
                cmd == null ? null : cmd.baseUrl(), cmd == null ? null : cmd.apiClientId(),
                cmd == null ? null : cmd.enabled()));
    }

    @PutMapping("/{id}")
    public TestExecutorService.ExecutorRow update(@PathVariable long id,
                                                  @RequestBody TestExecutorService.SaveCmd cmd) {
        return service.save(new TestExecutorService.SaveCmd(id,
                cmd == null ? null : cmd.code(), cmd == null ? null : cmd.name(),
                cmd == null ? null : cmd.baseUrl(), cmd == null ? null : cmd.apiClientId(),
                cmd == null ? null : cmd.enabled()));
    }

    @PostMapping("/{id}/disable")
    public TestExecutorService.ExecutorRow disable(@PathVariable long id) {
        return service.disable(id);
    }

    @GetMapping("/{id}/reports")
    public TestExecutorService.ExecutorReports reports(@PathVariable long id,
                                                       @RequestParam(defaultValue = "7") int days) {
        return service.reports(id, days);
    }
}
