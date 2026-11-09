package com.example.req.collab.policy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.example.platform.common.error.ApiException;
import com.example.req.integration.adapter.AdapterRegistry;
import com.example.req.integration.config.IntegrationConfigEntity;
import com.example.req.integration.config.IntegrationSystem;
import com.example.req.integration.gitlab.GitLabAdapter;
import com.example.req.integration.gitlab.MrViews;

/**
 * 分支策略核验：<b>全系统唯一</b>会为了「规则是否被遵守」而出站的地方（AC-11）。
 *
 * <p>判定只读三项 GitLab 事实：项目设置（合并方式/审批数）、保护分支、最近更新的分支。
 * 三态的产生方式写死在下面：</p>
 * <ul>
 *   <li>集成没启用 / 调用失败 / 应答不可信 → 落一条 <b>UNVERIFIED</b> 再抛 409。
 *       「先落库再抛」不是啰嗦：如果只在内存里返回失败，库里还留着上一次的 MATCH，
 *       规则列表就会继续显示绿色——这正是 AC-11 明令禁止的「假绿」。</li>
 *   <li>所有比对项都 {@code ok=true} → MATCH；任一项 {@code ok=false} → DRIFT，
 *       且差异项带着期望/实际值，QA 能直接指出是哪条分支/哪个字段。</li>
 * </ul>
 *
 * <p><b>不加 {@code @Transactional}</b>：失败路径必须先提交 UNVERIFIED 记录再抛异常，
 * 若整个 check 在一个事务里，抛异常会把那条记录一起回滚掉。</p>
 */
@Service
public class GitLabPolicyChecker {

    public static final String ACTION_CHECK = "CHECK";
    public static final String BIZ_TYPE = BranchPolicyService.BIZ_TYPE;

    /** 证据来源标签（写进 diff_json，QA 复现时按它去查 GitLab 接口）。 */
    private static final String SOURCE_PROJECT = "GET /api/v4/projects/:id";
    private static final String SOURCE_PROTECTED = "GET /api/v4/projects/:id/protected_branches";
    private static final String SOURCE_BRANCHES = "GET /api/v4/projects/:id/repository/branches";

    private static final String NOT_CREATED_NOTE =
            "规则模式暂无匹配分支（尚未创建），按一致处理";

    private final BranchPolicyService policyService;
    private final AdapterRegistry adapterRegistry;

    public GitLabPolicyChecker(BranchPolicyService policyService, AdapterRegistry adapterRegistry) {
        this.policyService = policyService;
        this.adapterRegistry = adapterRegistry;
    }

    /**
     * 核验一条规则。
     *
     * @throws ApiException 409 {@code INTEGRATION_DISABLED} / 409（适配器码）：已落 UNVERIFIED
     */
    public BranchPolicyViews.CheckResult check(long policyId) {
        BranchPolicyViews.PolicyView policy = policyService.require(policyId);
        Optional<IntegrationConfigEntity> maybeConfig =
                adapterRegistry.requireEnabled(IntegrationSystem.GITLAB);
        if (maybeConfig.isEmpty()) {
            LocalDateTime checkedAt = LocalDateTime.now();
            policyService.recordCheck(policyId, BranchPolicyViews.RESULT_UNVERIFIED,
                    BranchPolicyViews.REASON_DISABLED, List.of(),
                    "GitLab 集成未启用，无法核验（保持未核验，不沿用上次结果）");
            throw new ApiException(409, AdapterRegistry.CODE_DISABLED,
                    "GitLab 集成未启用，无法核验分支策略",
                    failureDetail(policyId, BranchPolicyViews.REASON_DISABLED, checkedAt, List.of()));
        }
        IntegrationConfigEntity config = maybeConfig.get();
        GitLabAdapter adapter = (GitLabAdapter) adapterRegistry.adapter(IntegrationSystem.GITLAB);

        MrViews.ProjectSettings settings;
        List<MrViews.ProtectedBranch> protectedBranches;
        List<String> branches;
        try {
            settings = adapter.fetchProjectSettings(config, policy.gitlabProject());
            protectedBranches = adapter.protectedBranches(config, policy.gitlabProject());
            branches = adapter.recentBranches(config, policy.gitlabProject(), 50);
        } catch (ApiException e) {
            String reason = reasonOf(e.code());
            LocalDateTime checkedAt = LocalDateTime.now();
            policyService.recordCheck(policyId, BranchPolicyViews.RESULT_UNVERIFIED, reason, List.of(),
                    e.getMessage());
            throw new ApiException(409, e.code(), e.getMessage(),
                    failureDetail(policyId, reason, checkedAt, List.of()));
        }

        List<BranchPolicyViews.CheckItem> items = new ArrayList<>();
        List<String> matched = branches.stream()
                .filter(branch -> BranchPolicyValidator.matches(
                        policy.branchPattern(), policy.patternType(), branch))
                .toList();

        items.add(new BranchPolicyViews.CheckItem(
                "分支是否存在",
                policy.branchPattern() + " (" + policy.patternType() + ")",
                matched.isEmpty() ? "尚无匹配分支" : String.join(", ", matched),
                true,
                SOURCE_BRANCHES));

        boolean protectedOk = true;
        String actualProtection = "—";
        List<String> actualPushRoles = List.of();
        if (!matched.isEmpty()) {
            List<String> unprotected = new ArrayList<>();
            List<String> covering = new ArrayList<>();
            for (String branch : matched) {
                Optional<MrViews.ProtectedBranch> hit = protectedBranches.stream()
                        .filter(b -> covers(b.name(), branch))
                        .findFirst();
                if (hit.isEmpty()) {
                    unprotected.add(branch);
                } else {
                    covering.add(hit.get().name());
                    actualPushRoles = hit.get().pushAccessLevels();
                }
            }
            actualProtection = unprotected.isEmpty()
                    ? "已保护（" + String.join(", ", covering) + "）"
                    : "未保护（" + String.join(", ", unprotected) + "）";
            if (policy.protectedBranch()) {
                protectedOk = unprotected.isEmpty();
            }
        }
        items.add(new BranchPolicyViews.CheckItem(
                "保护分支",
                policy.protectedBranch() ? "需要保护" : "不要求",
                actualProtection,
                protectedOk,
                SOURCE_PROTECTED));

        String expectedMethod = policy.mergeMethod();
        String actualMethod = settings.mergeMethod();
        items.add(new BranchPolicyViews.CheckItem(
                "合并方式",
                expectedMethod,
                actualMethod == null ? "GitLab 未返回" : actualMethod,
                actualMethod != null && expectedMethod != null
                        && expectedMethod.equalsIgnoreCase(actualMethod),
                SOURCE_PROJECT));

        int required = policy.approvalsRequired();
        Integer actualApprovals = settings.approvalsBeforeMerge();
        items.add(new BranchPolicyViews.CheckItem(
                "审批人数",
                String.valueOf(required),
                actualApprovals == null ? "GitLab 未返回" : String.valueOf(actualApprovals),
                actualApprovals != null ? actualApprovals == required : required == 0,
                SOURCE_PROJECT));

        List<String> requiredRoles = policy.pushRoles();
        boolean rolesOk = requiredRoles.isEmpty() || actualPushRoles.containsAll(requiredRoles);
        items.add(new BranchPolicyViews.CheckItem(
                "推送角色",
                requiredRoles.isEmpty() ? "不限制" : String.join(", ", requiredRoles),
                actualPushRoles.isEmpty() ? "—" : String.join(", ", actualPushRoles),
                rolesOk,
                SOURCE_PROTECTED));

        boolean drift = items.stream().anyMatch(item -> !item.ok());
        String result = drift ? BranchPolicyViews.RESULT_DRIFT : BranchPolicyViews.RESULT_MATCH;
        String note = matched.isEmpty() ? NOT_CREATED_NOTE : null;
        LocalDateTime checkedAt = LocalDateTime.now();
        policyService.recordCheck(policyId, result, null, items, note);
        return new BranchPolicyViews.CheckResult(
                policyId,
                result,
                null,
                drift ? "发现差异，按下列比对项修正 GitLab 设置或规则" : "规则与 GitLab 实际一致",
                items,
                checkedAt);
    }

    /** 保护分支名可能是通配（{@code release/*}），所以反向也用 GLOB 匹配一次。 */
    private static boolean covers(String protectedName, String branch) {
        if (protectedName == null) {
            return false;
        }
        return protectedName.equals(branch)
                || BranchPolicyValidator.matches(protectedName, BranchPolicyViews.PATTERN_GLOB, branch);
    }

    /** 适配器失败码 → reason 的 5 值枚举。不可达归 TIMEOUT（对用户都是「这次没问到」）。 */
    static String reasonOf(String adapterCode) {
        if (GitLabAdapter.CODE_AUTH_FAILED.equals(adapterCode)) {
            return BranchPolicyViews.REASON_AUTH_FAILED;
        }
        if (GitLabAdapter.CODE_PERMISSION.equals(adapterCode)) {
            return BranchPolicyViews.REASON_PERMISSION_INSUFFICIENT;
        }
        if (GitLabAdapter.CODE_UNSUPPORTED.equals(adapterCode)) {
            return BranchPolicyViews.REASON_API_UNSUPPORTED;
        }
        return BranchPolicyViews.REASON_TIMEOUT;
    }

    private static Map<String, Object> failureDetail(long policyId, String reason,
                                                     LocalDateTime checkedAt,
                                                     List<BranchPolicyViews.CheckItem> items) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("policyId", policyId);
        detail.put("result", BranchPolicyViews.RESULT_UNVERIFIED);
        detail.put("reason", reason);
        detail.put("checkedAt", checkedAt.toString());
        detail.put("items", items);
        return detail;
    }
}
