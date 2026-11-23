package com.example.req.automation.api;

import com.example.platform.common.scope.PageResponse;
import com.example.req.automation.api.AutomationDtos.EnableCmd;
import com.example.req.automation.api.AutomationDtos.MetaView;
import com.example.req.automation.api.AutomationDtos.RuleCmd;
import com.example.req.automation.api.AutomationDtos.RuleView;
import com.example.req.automation.rule.AutomationRuleService;
import com.example.req.automation.spec.AutomationErrors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /automation/rules} 与 {@code /automation/meta}。
 *
 * <p>没有 {@code DELETE}：停用即永久保留（历史执行日志要指着一条规则），
 * 也没有 {@code /automation/events}——事件只能由服务端在业务事务里产生（AC-4），
 * 前端去 POST 它只会得到 404。</p>
 *
 * <p>controller 不重复校验，只负责取参与转成 HTTP 形状；错误码由
 * {@link AutomationErrors} 一处产出，QA 断言的契约因此只有一个来源。</p>
 */
@RestController
@RequestMapping("/automation")
public class AutomationRuleController {

    private final AutomationRuleService service;

    public AutomationRuleController(AutomationRuleService service) {
        this.service = service;
    }

    @GetMapping("/rules")
    public PageResponse<RuleView> list(@RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String triggerType,
                                       @RequestParam(required = false) Boolean enabled,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return service.list(keyword, triggerType, enabled, page, size);
    }

    @GetMapping("/rules/{id}")
    public RuleView view(@PathVariable long id) {
        return service.view(id);
    }

    @PostMapping("/rules")
    public ResponseEntity<RuleView> create(@RequestBody RuleCmd cmd) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(cmd));
    }

    @PutMapping("/rules/{id}")
    public RuleView update(@PathVariable long id, @RequestBody RuleCmd cmd) {
        return service.update(id, cmd);
    }

    @PatchMapping("/rules/{id}/enabled")
    public RuleView setEnabled(@PathVariable long id, @RequestBody EnableCmd cmd) {
        if (cmd == null || cmd.enabled() == null) {
            throw AutomationErrors.schema("enabled", "必须给出 enabled");
        }
        if (cmd.version() == null) {
            throw AutomationErrors.schema("version", "启停必须带 version");
        }
        return service.setEnabled(id, cmd.enabled(), cmd.version());
    }

    @GetMapping("/meta")
    public MetaView meta() {
        return service.meta();
    }
}
