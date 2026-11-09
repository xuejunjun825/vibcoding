package com.example.req.integration.admission;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 准入项开关的 HTTP 面（TECHNICAL §4.2）：只做参数与状态码，业务全在
 * {@link AdmissionItemConfigService}（锁定项 409 也在那里判，控制器不复制一份）。
 *
 * <p>网关路由：{@code /api/req/integration/admission-items} → stripPrefix(2) → 本控制器；
 * 走 JWT 通道，配置类接口不允许匿名。</p>
 */
@RestController
@RequestMapping("/integration/admission-items")
public class AdmissionItemController {

    private final AdmissionItemConfigService service;

    public AdmissionItemController(AdmissionItemConfigService service) {
        this.service = service;
    }

    /** 恒 9 条：管理端据此渲染开关，不需要前端硬编码清单。 */
    @GetMapping
    public List<AdmissionViews.ItemView> list() {
        return service.list();
    }

    /** 逐项开关：整批生效或整批失败（400 校验 / 409 锁定）。 */
    @PutMapping
    public List<AdmissionViews.ItemView> update(
            @RequestBody(required = false) AdmissionViews.ToggleCmd cmd) {
        return service.update(cmd);
    }
}
