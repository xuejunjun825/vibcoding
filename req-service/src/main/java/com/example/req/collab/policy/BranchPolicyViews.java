package com.example.req.collab.policy;

import java.time.LocalDateTime;
import java.util.List;

import com.example.req.integration.gitlab.MrViews;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 分支策略域的视图与命令契约（AC-10 / AC-11 / TECHNICAL §2.5 §3.4）。
 *
 * <p>三态只在这一个类里定义：{@link #RESULT_MATCH}（规则与 GitLab 实际一致）、
 * {@link #RESULT_DRIFT}（有差异）、{@link #RESULT_UNVERIFIED}（没核验成功）。
 * 前端徽标、合规率、报告都读这三个值；<b>没有任何一条路径可以从 UNVERIFIED 变成
 * MATCH</b>——那需要一次真实的外呼核验。</p>
 *
 * <p>失败原因 {@link #reasonOf} 的 5 值是封闭枚举：库列 {@code reason VARCHAR(48)}、
 * 前端筛选器、QA 断言共用。新增原因必须同时改这三处，否则筛选器会静默漏掉。</p>
 */
public final class BranchPolicyViews {

    public static final String RESULT_MATCH = "MATCH";
    public static final String RESULT_DRIFT = "DRIFT";
    public static final String RESULT_UNVERIFIED = "UNVERIFIED";

    public static final String REASON_DISABLED = "INTEGRATION_DISABLED";
    public static final String REASON_AUTH_FAILED = "AUTH_FAILED";
    public static final String REASON_TIMEOUT = "TIMEOUT";
    public static final String REASON_API_UNSUPPORTED = "API_UNSUPPORTED";
    public static final String REASON_PERMISSION_INSUFFICIENT = "PERMISSION_INSUFFICIENT";

    /** 请求体本身缺字段/越界（与「规则自相矛盾」区分开，前端提示文案不同）。 */
    public static final String CODE_INVALID = "POLICY_INVALID";
    public static final String CODE_CONTRADICTION = "POLICY_CONTRADICTION";
    public static final String CODE_PATTERN_INVALID = "POLICY_PATTERN_INVALID";
    public static final String CODE_DUPLICATE = "POLICY_DUPLICATE";
    public static final String CODE_NOT_FOUND = "POLICY_NOT_FOUND";
    public static final String CODE_VERSION_CONFLICT = "POLICY_VERSION_CONFLICT";

    public static final String PATTERN_REGEX = "REGEX";
    public static final String PATTERN_GLOB = "GLOB";

    public static final String METHOD_MERGE = MrViews.MERGE_METHOD_MERGE;
    public static final String METHOD_SQUASH = MrViews.MERGE_METHOD_SQUASH;
    public static final String METHOD_FF_ONLY = MrViews.MERGE_METHOD_FF_ONLY;

    /** GitLab 的角色阶梯；规则里的 push/merge 角色只能从这里取（校验器据此报 400）。 */
    public static final List<String> ROLES =
            List.of("Guest", "Reporter", "Developer", "Maintainer", "Owner");

    /** AC-10：审批数 0–5。 */
    public static final int MAX_APPROVALS = 5;

    /** 规则名/分支模式/项目名的列宽，供校验器与截断共用。 */
    public static final int MAX_PROJECT = 200;
    public static final int MAX_PATTERN = 200;

    private BranchPolicyViews() {
    }

    /**
     * 一条分支策略及其<b>派生</b>的核验态。
     *
     * <p>{@code checkResult}/{@code checkReason}/{@code checkedAt} 不是列，是从
     * {@code branch_policy_check} 最新一行派生的：没有记录就是 UNVERIFIED。规则表自己
     * 不存「是否合规」——否则核验失败时它会残留上一次的绿色（AC-11 的反面目标）。</p>
     */
    public record PolicyView(long id,
                             Long domainId,
                             String gitlabProject,
                             String branchPattern,
                             String patternType,
                             @JsonProperty("protected") boolean protectedBranch,
                             String mergeMethod,
                             int approvalsRequired,
                             List<String> pushRoles,
                             List<String> mergeRoles,
                             boolean enabled,
                             int version,
                             String checkResult,
                             String checkReason,
                             LocalDateTime checkedAt) {

        public PolicyView {
            pushRoles = pushRoles == null ? List.of() : List.copyOf(pushRoles);
            mergeRoles = mergeRoles == null ? List.of() : List.copyOf(mergeRoles);
        }
    }

    /**
     * 新建（{@code id == null}）或更新（{@code id != null}）规则。
     *
     * <p>用包装类型而不是基本类型：PUT 时 {@code version} 参与乐观锁（见
     * {@code BranchPolicyService#save}），缺省表示「不校验版本，直接覆盖」。</p>
     */
    public record PolicyCmd(Long id,
                            Long domainId,
                            String gitlabProject,
                            String branchPattern,
                            String patternType,
                            @JsonProperty("protected") Boolean protectedBranch,
                            String mergeMethod,
                            Integer approvalsRequired,
                            List<String> pushRoles,
                            List<String> mergeRoles,
                            Boolean enabled,
                            Integer version) {
    }

    /**
     * 一个比对项：期望（规则）/ 实际（GitLab）/ 是否一致 / 证据来源。
     *
     * <p>{@code source} 写的是「这条实际值从哪个只读接口来」，例如
     * {@code GET /projects/:id/protected_branches}；脱敏后的证据，便于 QA 复现。</p>
     */
    public record CheckItem(String item, String expected, String actual, boolean ok, String source) {
    }

    /** 一次核验的落库记录（{@code GET /code/policies/{id}/checks} 的行）。 */
    public record CheckRow(long id,
                           long policyId,
                           String result,
                           String reason,
                           List<CheckItem> items,
                           String note,
                           LocalDateTime checkedAt,
                           String checkedBy) {

        public CheckRow {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /** {@code POST /code/policies/{id}/check} 的成功应答。 */
    public record CheckResult(long policyId,
                              String result,
                              String reason,
                              String message,
                              List<CheckItem> items,
                              LocalDateTime checkedAt) {

        public CheckResult {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /** 判定原因是否属于封闭枚举（写库前用它兜底，避免脏值进 48 字符的列）。 */
    public static boolean knownReason(String reason) {
        return REASON_DISABLED.equals(reason)
                || REASON_AUTH_FAILED.equals(reason)
                || REASON_TIMEOUT.equals(reason)
                || REASON_API_UNSUPPORTED.equals(reason)
                || REASON_PERMISSION_INSUFFICIENT.equals(reason);
    }
}
