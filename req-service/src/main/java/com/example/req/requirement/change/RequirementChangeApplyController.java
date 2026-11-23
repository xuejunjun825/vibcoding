package com.example.req.requirement.change;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应用变更单：{@code POST /changes/{id}/apply}（AC-6）。
 *
 * <p>为什么与 {@link RequirementChangeController} 分开：鉴权是声明式的
 * {@code PermissionRouteTable} 按「方法 + 路径正则」首匹配，{@code /changes/{id}/apply} 不
 * 以 {@code /requirements} 开头，必须有一条独立的路由规则（动作 {@code req.action.change_apply}）。
 * 放在同一个控制器里也不会影响路由表，但分开后「哪个写端点需要哪个动作码」一眼可见。</p>
 *
 * <p>不新增 {@code @RequiresAction}：本仓库的写端点统一走路由表声明（零裸路径），注解是死代码。</p>
 */
@RestController
@RequestMapping("/changes")
public class RequirementChangeApplyController {

    private final RequirementChangeApplyService applyService;

    public RequirementChangeApplyController(RequirementChangeApplyService applyService) {
        this.applyService = applyService;
    }

    @PostMapping("/{id}/apply")
    public RequirementChangeViews.Detail apply(@PathVariable("id") long id) {
        return applyService.apply(id);
    }
}
