package com.example.deliver.testcase;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Module tree endpoints. The tree is returned flat, ordered by path: the UI builds the indent from
 * {@code level}, and a flat list keeps one module from appearing twice when a parent is filtered.
 */
@RestController
@RequestMapping("/test-modules")
public class TestModuleController {

    private final TestCaseService service;

    public TestModuleController(TestCaseService service) {
        this.service = service;
    }

    @GetMapping
    public List<TestCaseViews.ModuleView> tree() {
        return service.moduleTree();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TestCaseViews.ModuleView create(@RequestBody TestCaseViews.CreateModule cmd) {
        return service.createModule(cmd);
    }

    @PutMapping("/{id}")
    public TestCaseViews.ModuleView update(@PathVariable long id,
                                           @RequestBody TestCaseViews.SaveModule cmd) {
        return service.updateModule(id, cmd);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.deleteModule(id);
    }
}
