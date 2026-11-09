package com.example.req.integration;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 需求详情「集成」tab 的单次读取（TECHNICAL §4.4 / DESIGN I4）。
 *
 * <p>与 {@code MrController}、{@code SonarController} 同挂 {@code /requirements} 前缀：
 * 前端打开 tab 只发这一条请求，避免三个卡片各自首屏取数、各自失败各自重试。</p>
 */
@RestController
@RequestMapping("/requirements")
public class RequirementIntegrationController {

    private final RequirementIntegrationService service;

    public RequirementIntegrationController(RequirementIntegrationService service) {
        this.service = service;
    }

    /** 代码卡 + Sonar 卡 + 最近 10 条同步记录；恒 200，全部读本地事实。 */
    @GetMapping("/{id}/integration")
    public RequirementIntegrationViews integration(@PathVariable long id) {
        return service.load(id);
    }
}
