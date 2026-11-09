package com.example.req.integration.release;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /releases/{id}/integration-settings} 的 HTTP 面（TECHNICAL §4.4）。
 *
 * <p>单独一个控制器而不是往 {@code ReleaseController} 里加：版本域的既有控制器由别的
 * 演进线维护，集成设置属于 M7 集成域，同前缀多控制器在 Spring 里是合法的，路由不冲突。</p>
 */
@RestController
@RequestMapping("/releases")
public class ReleaseIntegrationController {

    private final ReleaseIntegrationSettingsService settingsService;

    public ReleaseIntegrationController(ReleaseIntegrationSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping("/{id}/integration-settings")
    public ReleaseIntegrationViews.Settings get(@PathVariable long id) {
        return settingsService.get(id);
    }

    @PutMapping("/{id}/integration-settings")
    public ReleaseIntegrationViews.Settings update(@PathVariable long id,
                                                   @RequestBody(required = false) ReleaseIntegrationViews.SettingsCmd cmd) {
        return settingsService.update(id, cmd);
    }
}
