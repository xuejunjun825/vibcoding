package com.example.req.deploy;

import com.example.platform.common.error.ApiException;
import com.example.req.integration.DeliverPlanCoverageClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * R1 的唯一闸门：三条判定（无需求放行 / 待批 409 / 不可用 503）各自独立断言。
 *
 * <p>端到端（真实栈 201/409/503 + 行数不变）由 {@code scripts/tests/quality/q1-plan-lifecycle.sh} 覆盖；
 * 这里钉住"服务层不许绕过"的部分，避免把 HTTP 语义散落到多个调用点。</p>
 */
class DeployPlanGuardTest {

    private static final long RELEASE_ID = 10L;
    private static final List<Long> REQUIREMENTS = List.of(5L, 9L);

    private final DeliverPlanCoverageClient coverage = mock(DeliverPlanCoverageClient.class);
    private final DeployPlanGuard guard = new DeployPlanGuard(coverage);

    @Test
    @DisplayName("没有需求就放行：不发跨服务请求（AC-3 的空需求集路径）")
    void emptyRequirementsPassWithoutCallingDeliver() {
        guard.requireApproved(RELEASE_ID, List.of());
        guard.requireApproved(RELEASE_ID, null);

        verifyNoInteractions(coverage);
    }

    @Test
    @DisplayName("覆盖计划全部已批准（空列表）→ 放行")
    void approvedPlansPass() {
        when(coverage.pending(RELEASE_ID, REQUIREMENTS)).thenReturn(List.of());

        guard.requireApproved(RELEASE_ID, REQUIREMENTS);

        verify(coverage).pending(RELEASE_ID, REQUIREMENTS);
    }

    @Test
    @DisplayName("任一覆盖计划待批 → 409 PLAN_NOT_APPROVED，detail 带 planIds/plans/requirementIds/hint")
    void pendingPlanBlocks() {
        when(coverage.pending(RELEASE_ID, REQUIREMENTS)).thenReturn(List.of(
                new DeliverPlanCoverageClient.PlanRef(142L, "2026-11 回归计划", "DRAFT", List.of(5L))));

        ApiException error = assertThrows(ApiException.class,
                () -> guard.requireApproved(RELEASE_ID, REQUIREMENTS));

        assertEquals(409, error.status());
        assertEquals(DeployErrors.PLAN_NOT_APPROVED, error.code());
        assertEquals(List.of(142L), error.detail().get("planIds"));
        assertEquals(REQUIREMENTS, error.detail().get("requirementIds"));
        assertTrue(error.detail().get("hint") != null && !String.valueOf(error.detail().get("hint")).isBlank());
        assertTrue(error.message().contains("#142"), "人读消息要带计划号：" + error.message());
        verify(coverage, never()).pending(RELEASE_ID, List.of());
    }

    @Test
    @DisplayName("覆盖查询不可用 → 503 PLAN_CHECK_UNAVAILABLE（fail-closed，不当成没有计划）")
    void unavailableFailsClosed() {
        when(coverage.pending(RELEASE_ID, REQUIREMENTS))
                .thenThrow(new DeliverPlanCoverageClient.PlanCheckUnavailable("连接被拒绝"));

        ApiException error = assertThrows(ApiException.class,
                () -> guard.requireApproved(RELEASE_ID, REQUIREMENTS));

        assertEquals(503, error.status());
        assertEquals(DeployErrors.PLAN_CHECK_UNAVAILABLE, error.code());
        assertEquals("连接被拒绝", error.detail().get("reason"));
    }
}
