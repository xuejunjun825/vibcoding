package com.example.req.collab.policy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.platform.common.error.ApiException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分支策略的四类校验必须在进入数据库之前失败（AC-10）。
 *
 * <p>这里只测纯函数：{@link BranchPolicyValidator} 不依赖 Spring、不碰库，
 * 所以每条规则都能用一行断言钉住错误码——页面据此把 400 渲染成字段级提示。</p>
 */
class BranchPolicyValidatorTest {

    @Test
    void approvalsWithoutProtectionIsContradiction() {
        ApiException e = assertThrows(ApiException.class, () -> BranchPolicyValidator.validate(
                cmd("group/repo", "^release/.*$", "REGEX", false, "MERGE", 2)));
        assertEquals(400, e.status());
        assertEquals(BranchPolicyViews.CODE_CONTRADICTION, e.code());
    }

    @Test
    void ffOnlyAndSquashAreMutuallyExclusiveButBothValidAlone() {
        assertEquals(BranchPolicyViews.METHOD_FF_ONLY, BranchPolicyValidator.normalize(
                cmd("group/repo", "^release/.*$", "REGEX", true, "ff_only", 0)).mergeMethod());
        assertThrows(ApiException.class, () -> BranchPolicyValidator.validate(
                cmd("group/repo", "^release/.*$", "REGEX", true, "REBASE", 0)));
    }

    @Test
    void badRegexIsPatternInvalidNotContradiction() {
        ApiException e = assertThrows(ApiException.class, () -> BranchPolicyValidator.validate(
                cmd("group/repo", "release/[0-9", "REGEX", true, "MERGE", 0)));
        assertEquals(400, e.status());
        assertEquals(BranchPolicyViews.CODE_PATTERN_INVALID, e.code());
    }

    @Test
    void unknownRoleIsContradiction() {
        BranchPolicyViews.PolicyCmd cmd = new BranchPolicyViews.PolicyCmd(null, 1L,
                "group/repo", "^release/.*$", "REGEX", true, "MERGE", 0,
                List.of("Maintainer", "Root"), List.of("Maintainer"), true, null);
        ApiException e = assertThrows(ApiException.class, () -> BranchPolicyValidator.validate(cmd));
        assertEquals(BranchPolicyViews.CODE_CONTRADICTION, e.code());
    }

    @Test
    void unknownRoleSurvivesNormalizeSoItCannotBeSilentlyDropped() {
        BranchPolicyViews.PolicyCmd normalized = BranchPolicyValidator.normalize(
                new BranchPolicyViews.PolicyCmd(null, 1L, "group/repo", "^release/.*$", "REGEX",
                        true, "MERGE", 0, List.of("Maintainer", "Root"), List.of("maintainers"), true, null));
        assertTrue(normalized.pushRoles().contains("Root"), "未知角色不能在归一阶段被丢掉");
        assertEquals(List.of("Maintainer"), normalized.mergeRoles());
        ApiException e = assertThrows(ApiException.class, () -> BranchPolicyValidator.validate(normalized));
        assertEquals(BranchPolicyViews.CODE_CONTRADICTION, e.code());
    }

    @Test
    void approvalsOutOfRangeIsInvalid() {
        ApiException e = assertThrows(ApiException.class, () -> BranchPolicyValidator.validate(
                cmd("group/repo", "^release/.*$", "REGEX", true, "MERGE", 6)));
        assertEquals(BranchPolicyViews.CODE_CONTRADICTION, e.code());
    }

    @Test
    void matchesHonoursDeclaredPatternType() {
        assertTrue(BranchPolicyValidator.matches("^release/\\d+\\.\\d+$", "REGEX", "release/1.2"));
        assertFalse(BranchPolicyValidator.matches("^release/\\d+\\.\\d+$", "REGEX", "feature/1.2"));
        assertTrue(BranchPolicyValidator.matches("release/*", "GLOB", "release/1.2"));
        assertFalse(BranchPolicyValidator.matches("release/*", "GLOB", "hotfix/1.2"));
    }

    @Test
    void normalizeFillsDefaultsSoTheUiCanOmitThem() {
        BranchPolicyViews.PolicyCmd normalized = BranchPolicyValidator.normalize(
                new BranchPolicyViews.PolicyCmd(null, 1L, " group/repo ", "release/*", "glob",
                        null, null, null, null, null, null, null));
        assertEquals("group/repo", normalized.gitlabProject());
        assertEquals("GLOB", normalized.patternType());
        assertEquals("MERGE", normalized.mergeMethod());
        assertTrue(normalized.protectedBranch());
        assertEquals(0, normalized.approvalsRequired());
        assertTrue(normalized.enabled());
    }

    private static BranchPolicyViews.PolicyCmd cmd(String project, String pattern, String type,
                                                   boolean protectedBranch, String mergeMethod,
                                                   int approvals) {
        return new BranchPolicyViews.PolicyCmd(null, 1L, project, pattern, type, protectedBranch,
                mergeMethod, approvals, List.of("Maintainer"), List.of("Maintainer"), true, null);
    }
}
