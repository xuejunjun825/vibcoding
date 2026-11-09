package com.example.req.integration.sonar;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 代码质量的只读 HTTP 面（AC-13 / TECHNICAL §2.7）。
 *
 * <p>两个 GET 都<b>不</b>刷新快照：它们只读 {@code quality_gate_snapshot} 的最新行，
 * 因此打开页面时 Sonar 的桩计数必须保持为 0（s5 断言直接盯这一点）。</p>
 */
@RestController
public class CodeQualityController {

    private final CodeQualityService service;

    public CodeQualityController(CodeQualityService service) {
        this.service = service;
    }

    /** 需求详情：{@code {card, emptyReason}}；没有快照时 card=null，绝不返回一张全 0 的卡。 */
    @GetMapping("/requirements/{id}/code-quality")
    public CodeQualityViews.RequirementCard requirement(@PathVariable long id) {
        return service.requirement(id);
    }

    /** 版本页：按 {@code project_key} 去重后的项目行，外加整页空态原因。 */
    @GetMapping("/releases/{id}/code-quality")
    public CodeQualityViews.ReleaseCard release(@PathVariable long id) {
        return service.release(id);
    }
}
