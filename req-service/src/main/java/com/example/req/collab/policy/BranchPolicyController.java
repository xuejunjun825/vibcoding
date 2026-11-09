package com.example.req.collab.policy;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分支策略的 HTTP 面（TECHNICAL §2.5）。薄到只剩参数绑定：
 * 校验、判定、审计全在 {@link BranchPolicyService} / {@link GitLabPolicyChecker}。
 *
 * <p>权限不在方法上写注解：{@code PermissionRouteTable} 已为 {@code /code/policies}
 * 前缀登记了 {@code CCODE_POLICY_VIEW} / {@code CCODE_POLICY_EDIT}（首匹配即胜），
 * 这里再标一次就成了两个事实源。</p>
 */
@RestController
@RequestMapping("/code/policies")
public class BranchPolicyController {

    private final BranchPolicyService policyService;
    private final GitLabPolicyChecker checker;

    public BranchPolicyController(BranchPolicyService policyService, GitLabPolicyChecker checker) {
        this.policyService = policyService;
        this.checker = checker;
    }

    /** 规则列表。只读本地表，<b>零外呼</b>（AC-11：打开页面不触发核验）。 */
    @GetMapping
    public List<BranchPolicyViews.PolicyView> list(
            @RequestParam(name = "domainId", required = false) Long domainId,
            @RequestParam(name = "project", required = false) String project,
            @RequestParam(name = "enabled", required = false) Boolean enabled) {
        return policyService.list(domainId, project, enabled);
    }

    /** 新建规则。 */
    @PostMapping
    public BranchPolicyViews.PolicyView create(@RequestBody BranchPolicyViews.PolicyCmd cmd) {
        return policyService.save(cmd);
    }

    /** 更新规则（version 自增；带 version 则参与乐观锁）。 */
    @PutMapping("/{id}")
    public BranchPolicyViews.PolicyView update(@PathVariable("id") long id,
                                               @RequestBody BranchPolicyViews.PolicyCmd cmd) {
        return policyService.save(new BranchPolicyViews.PolicyCmd(
                id, cmd.domainId(), cmd.gitlabProject(), cmd.branchPattern(), cmd.patternType(),
                cmd.protectedBranch(), cmd.mergeMethod(), cmd.approvalsRequired(), cmd.pushRoles(),
                cmd.mergeRoles(), cmd.enabled(), cmd.version()));
    }

    /** 软删：{@code enabled=0}，核验历史保留。 */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable("id") long id) {
        policyService.delete(id);
    }

    /**
     * 核验（唯一外呼）。成功 200 + 三态之一；集成关闭/调用失败 409，
     * 且失败前已落一条 UNVERIFIED。
     */
    @PostMapping("/{id}/check")
    public BranchPolicyViews.CheckResult check(@PathVariable("id") long id) {
        return checker.check(id);
    }

    /** 核验历史（新→旧）。只读，零外呼。 */
    @GetMapping("/{id}/checks")
    public List<BranchPolicyViews.CheckRow> checks(
            @PathVariable("id") long id,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return policyService.checks(id, limit);
    }
}
