package com.example.req.collab.policy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.example.platform.common.error.ApiException;

/**
 * 分支策略的四类校验（AC-10 负向 + TECHNICAL §3.4）。
 *
 * <p><b>纯静态、无 Spring</b>：四类校验是纯函数，单测直接调它，不必起容器。
 * 校验与匹配语义也放在同一处——{@link #matches} 是「分支名是否符合规则」的唯一实现，
 * 核验器调它，而不是自己再写一遍正则。</p>
 *
 * <p>四类：</p>
 * <ol>
 *   <li>缺字段/越界 → 400 {@code POLICY_INVALID}</li>
 *   <li>模式编译不过 → 400 {@code POLICY_PATTERN_INVALID}</li>
 *   <li>规则自相矛盾（审批数&gt;0 却不保护、未知角色、未知合并方式、审批数&gt;5）
 *       → 400 {@code POLICY_CONTRADICTION}</li>
 *   <li>唯一键冲突（同租户 + 同项目 + 同模式）→ 409 {@code POLICY_DUPLICATE}，
 *       由 Service 捕获唯一键异常抛出（这里只做归一，不查库）</li>
 * </ol>
 */
public final class BranchPolicyValidator {

    private static final Set<String> METHODS = Set.of(
            BranchPolicyViews.METHOD_MERGE,
            BranchPolicyViews.METHOD_SQUASH,
            BranchPolicyViews.METHOD_FF_ONLY);

    private BranchPolicyValidator() {
    }

    /**
     * 校验并抛 {@link ApiException}。调用方拿到的是一个已经归一（默认值已填）的命令，
     * 见 {@link #normalize(BranchPolicyViews.PolicyCmd)}——建议先归一后校验。
     */
    public static void validate(BranchPolicyViews.PolicyCmd cmd) {
        if (cmd == null) {
            throw invalid("body", "请求体不能为空");
        }
        if (isBlank(cmd.gitlabProject())) {
            throw invalid("gitlabProject", "必须填写 GitLab 项目（group/repo）");
        }
        if (cmd.gitlabProject().trim().length() > BranchPolicyViews.MAX_PROJECT) {
            throw invalid("gitlabProject", "项目名不能超过 200 字符");
        }
        if (isBlank(cmd.branchPattern())) {
            throw invalid("branchPattern", "必须填写分支匹配模式");
        }
        if (cmd.branchPattern().trim().length() > BranchPolicyViews.MAX_PATTERN) {
            throw invalid("branchPattern", "分支模式不能超过 200 字符");
        }
        String patternType = cmd.patternType() == null
                ? BranchPolicyViews.PATTERN_REGEX : cmd.patternType().trim().toUpperCase(Locale.ROOT);
        if (!BranchPolicyViews.PATTERN_REGEX.equals(patternType)
                && !BranchPolicyViews.PATTERN_GLOB.equals(patternType)) {
            throw patternInvalid(patternType, "patternType 只能是 REGEX 或 GLOB");
        }
        compile(cmd.branchPattern(), patternType);

        String mergeMethod = cmd.mergeMethod() == null
                ? BranchPolicyViews.METHOD_MERGE : cmd.mergeMethod().trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(mergeMethod)) {
            throw contradiction("mergeMethod", mergeMethod,
                    "合并方式只能是 MERGE / SQUASH / FF_ONLY（FF_ONLY 与 SQUASH 互斥）");
        }
        int approvals = cmd.approvalsRequired() == null ? 0 : cmd.approvalsRequired();
        if (approvals < 0 || approvals > BranchPolicyViews.MAX_APPROVALS) {
            throw contradiction("approvalsRequired", approvals, "审批人数必须在 0–5 之间");
        }
        boolean isProtected = cmd.protectedBranch() == null || cmd.protectedBranch();
        if (approvals > 0 && !isProtected) {
            throw contradiction("approvalsRequired", approvals,
                    "要求审批的分支必须是保护分支（GitLab 不会对非保护分支执行审批规则）");
        }
        requireKnownRoles(cmd.pushRoles(), "pushRoles");
        requireKnownRoles(cmd.mergeRoles(), "mergeRoles");
    }

    /**
     * 归一：补默认值、裁剪空白、角色去重排序、模式类型/合并方式转大写。
     * 归一后再校验，避免「空格导致唯一键冲突」这类脏数据。
     */
    public static BranchPolicyViews.PolicyCmd normalize(BranchPolicyViews.PolicyCmd cmd) {
        if (cmd == null) {
            return null;
        }
        return new BranchPolicyViews.PolicyCmd(
                cmd.id(),
                cmd.domainId(),
                trim(cmd.gitlabProject()),
                trim(cmd.branchPattern()),
                cmd.patternType() == null || cmd.patternType().isBlank()
                        ? BranchPolicyViews.PATTERN_REGEX
                        : cmd.patternType().trim().toUpperCase(Locale.ROOT),
                cmd.protectedBranch() == null ? Boolean.TRUE : cmd.protectedBranch(),
                cmd.mergeMethod() == null || cmd.mergeMethod().isBlank()
                        ? BranchPolicyViews.METHOD_MERGE
                        : cmd.mergeMethod().trim().toUpperCase(Locale.ROOT),
                cmd.approvalsRequired() == null ? Integer.valueOf(0) : cmd.approvalsRequired(),
                roles(cmd.pushRoles()),
                roles(cmd.mergeRoles()),
                cmd.enabled() == null ? Boolean.TRUE : cmd.enabled(),
                cmd.version());
    }

    /** 分支名是否匹配规则；REGEX 用 {@code matcher().matches()}（全串匹配，不是 find）。 */
    public static boolean matches(String pattern, String patternType, String branch) {
        if (pattern == null || branch == null) {
            return false;
        }
        String type = patternType == null
                ? BranchPolicyViews.PATTERN_REGEX : patternType.trim().toUpperCase(Locale.ROOT);
        try {
            if (BranchPolicyViews.PATTERN_GLOB.equals(type)) {
                return Pattern.compile(globToRegex(pattern)).matcher(branch).matches();
            }
            return Pattern.compile(pattern).matcher(branch).matches();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    /** glob → 正则：{@code *} 匹配任意段（含 {@code /}），{@code ?} 匹配单字符。 */
    static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\', '/', '-' ->
                        regex.append('\\').append(c);
                default -> regex.append(c);
            }
        }
        return regex.append('$').toString();
    }

    private static void compile(String pattern, String patternType) {
        String regex = BranchPolicyViews.PATTERN_GLOB.equals(patternType)
                ? globToRegex(pattern) : pattern;
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw patternInvalid(pattern, "模式无法编译：" + e.getDescription());
        }
    }

    private static void requireKnownRoles(List<String> roles, String field) {
        if (roles == null) {
            return;
        }
        for (String role : roles) {
            if (role == null || role.isBlank()) {
                continue;
            }
            String normalized = canonicalRole(role);
            if (normalized == null) {
                throw contradiction(field, role,
                        "未知角色码，可选值：" + String.join("/", BranchPolicyViews.ROLES));
            }
        }
    }

    /** 别名归一（maintainer/maintainers → Maintainer），大小写不敏感。 */
    public static String canonicalRole(String role) {
        if (role == null) {
            return null;
        }
        String value = role.trim();
        if (value.isEmpty()) {
            return null;
        }
        for (String known : BranchPolicyViews.ROLES) {
            if (known.equalsIgnoreCase(value) || (known + "s").equalsIgnoreCase(value)) {
                return known;
            }
        }
        return null;
    }

    private static List<String> roles(List<String> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String role : input) {
            if (role == null || role.isBlank()) {
                continue;
            }
            String canonical = canonicalRole(role);
            // 未知角色**保留原文**：剔掉会让校验器看不见它，等于静默放行（AC-10 要求 400）。
            unique.add(canonical == null ? role.trim() : canonical);
        }
        return List.copyOf(unique);
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(400, BranchPolicyViews.CODE_INVALID, message, detail(field, null));
    }

    private static ApiException patternInvalid(String value, String message) {
        return new ApiException(400, BranchPolicyViews.CODE_PATTERN_INVALID, message, detail("branchPattern", value));
    }

    private static ApiException contradiction(String field, Object value, String message) {
        return new ApiException(400, BranchPolicyViews.CODE_CONTRADICTION, message, detail(field, value));
    }

    private static Map<String, Object> detail(String field, Object value) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("field", field);
        if (value != null) {
            detail.put("value", value);
        }
        return detail;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    /** 供测试与前端提示：所有可选角色。 */
    public static List<String> roles() {
        return new ArrayList<>(BranchPolicyViews.ROLES);
    }
}
