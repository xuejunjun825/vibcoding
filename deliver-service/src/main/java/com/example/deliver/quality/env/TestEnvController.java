package com.example.deliver.quality.env;

import com.example.platform.common.scope.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Environment ledger endpoints (AC-9). */
@RestController
@RequestMapping("/test-envs")
public class TestEnvController {

    private final TestEnvService envs;

    public TestEnvController(TestEnvService envs) {
        this.envs = envs;
    }

    @GetMapping
    public PageResponse<TestEnvService.EnvRow> page(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return envs.page(status, keyword, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TestEnvService.EnvRow create(@RequestBody TestEnvService.EnvBody body) {
        return envs.create(body);
    }

    @GetMapping("/{id}")
    public TestEnvService.EnvRow detail(@PathVariable long id) {
        return envs.detail(id);
    }

    @PutMapping("/{id}")
    public TestEnvService.EnvRow update(@PathVariable long id,
                                        @RequestBody TestEnvService.EnvBody body) {
        return envs.update(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        envs.delete(id);
    }
}
