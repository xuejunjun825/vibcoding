package com.example.req.requirement;

import com.example.platform.common.error.ApiException;
import com.example.platform.common.notify.NotificationWriter;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.ScopeTier;
import com.example.req.requirement.RequirementPoolOpsViews.FollowRequest;
import com.example.req.requirement.RequirementPoolOpsViews.FollowResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 跟单幂等（r47 §3.2）：唯一键 + INSERT IGNORE 的 0/1 翻译成 200/201，通知按「收件人 + 天」只发一次。
 *
 * <p>为什么用 mock 而不是起库：幂等由 {@code uk_follow} 保证，插入返回 0 就是「已跟过」的全部事实；
 * 这里要钉的是服务层有没有把 0 当失败、有没有重复发通知、有没有跳过跟单人自己。</p>
 */
class RequirementFollowServiceTest {

    private static final long TENANT = 1L;
    private static final long VIEWER = 7L;
    private static final long REQUIREMENT = 42L;

    private RequirementService requirements;
    private RequirementFollowQuery follows;
    private NotificationWriter notifications;
    private RequirementStateMachine stateMachine;
    private RequirementFollowService service;

    @BeforeEach
    void setUp() {
        requirements = mock(RequirementService.class);
        follows = mock(RequirementFollowQuery.class);
        notifications = mock(NotificationWriter.class);
        stateMachine = mock(RequirementStateMachine.class);
        service = new RequirementFollowService(requirements, follows, notifications, stateMachine);
        TenantContext.set(DataScope.of(TENANT, ScopeTier.TENANT, Set.of(), null, VIEWER, Set.of()));
        when(follows.orgOf(VIEWER)).thenReturn(88L);
        // 写路径的 SQL scope 兜底默认放行；P2-9 的越权场景在 requireInScopeRejectsOutOfScope 里
        // 单独把 visible 改回 false，验证 403 且不落行。
        lenient().when(follows.visible(anyLong())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private RequirementEntity entity(Long mergedIntoId, String status, Long reporter, Long owner,
                                     boolean terminal) {
        RequirementEntity entity = mock(RequirementEntity.class);
        when(entity.getId()).thenReturn(REQUIREMENT);
        when(entity.getTitle()).thenReturn("采购订单移动审批");
        when(entity.getStatus()).thenReturn(status);
        when(entity.getMergedIntoId()).thenReturn(mergedIntoId);
        when(entity.getReporterId()).thenReturn(reporter);
        when(entity.getOwnerUserId()).thenReturn(owner);
        when(stateMachine.isTerminalAcrossTypes(anyString())).thenReturn(terminal);
        when(requirements.requireVisible(REQUIREMENT)).thenReturn(entity);
        when(follows.nameOf(VIEWER)).thenReturn("陈业务");
        when(follows.stats(REQUIREMENT)).thenReturn(new RequirementFollowQuery.Stats(1, true));
        return entity;
    }

    @Test
    @DisplayName("首次跟单：insert=1 → created=true，通知 reporter 一次")
    void firstFollowReturnsCreatedAndNotifiesReporter() {
        entity(null, "IN_DEV", 2L, null, false);
        when(follows.insert(REQUIREMENT, VIEWER, 88L, null)).thenReturn(1);

        FollowResult result = service.follow(REQUIREMENT, new FollowRequest("  "));

        assertThat(result.created()).isTrue();
        assertThat(result.followCount()).isEqualTo(1);
        assertThat(result.followedByMe()).isTrue();
        ArgumentCaptor<Long> recipient = ArgumentCaptor.forClass(Long.class);
        verify(notifications, times(1)).send(recipient.capture(), eq("REQUIREMENT_FOLLOWED"),
                anyString(), anyString(), eq(RequirementService.BIZ_TYPE), eq(REQUIREMENT));
        assertThat(recipient.getValue()).as("通知发给 reporter，不是跟单人").isEqualTo(2L);
    }

    @Test
    @DisplayName("重复跟单：insert=0 → created=false（HTTP 200），不再发通知")
    void duplicateFollowIsIdempotent() {
        entity(null, "IN_DEV", 2L, 3L, false);
        when(follows.insert(REQUIREMENT, VIEWER, 88L, null)).thenReturn(0);
        when(follows.notifiedToday(REQUIREMENT, 2L)).thenReturn(true);
        when(follows.notifiedToday(REQUIREMENT, 3L)).thenReturn(true);

        FollowResult result = service.follow(REQUIREMENT, new FollowRequest(null));

        assertThat(result.created()).isFalse();
        assertThat(result.followCount()).isEqualTo(1);
        assertThat(result.followedByMe()).isTrue();
        verify(notifications, never()).send(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyLong());
    }

    @Test
    @DisplayName("同一天第二个跟单人：通知已发过（notifiedToday=true）就不再发")
    void secondFollowerSameDayDoesNotNotifyAgain() {
        RequirementEntity entity = entity(null, "IN_DEV", 2L, null, false);
        when(follows.insert(REQUIREMENT, VIEWER, 88L, null)).thenReturn(1);
        when(follows.notifiedToday(REQUIREMENT, 2L)).thenReturn(true);

        service.follow(REQUIREMENT, new FollowRequest("备注"));

        verify(notifications, never()).send(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyLong());
        assertThat(entity.getTitle()).isNotBlank();
    }

    @Test
    @DisplayName("终态需求 → 409 REQUIREMENT_CLOSED，且不写行、不发通知")
    void terminalRequirementIsRejected() {
        entity(null, "CLOSED", 2L, null, true);

        assertThatThrownBy(() -> service.follow(REQUIREMENT, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.status()).isEqualTo(409);
                    assertThat(api.code()).isEqualTo(RequirementErrors.REQUIREMENT_CLOSED);
                });
        verify(follows, never()).insert(anyLong(), anyLong(), any(), any());
        verify(notifications, never()).send(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyLong());
    }

    @Test
    @DisplayName("已被合并的源需求也算关闭：merged_into_id 非空 → 409")
    void mergedSourceIsClosed() {
        entity(99L, "IN_DEV", 2L, null, false);

        assertThatThrownBy(() -> service.follow(REQUIREMENT, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(RequirementErrors.REQUIREMENT_CLOSED));
    }

    @Test
    @DisplayName("取消没跟过的需求 → 404 NOT_FOUND（DELETE 的契约）")
    void unfollowWithoutRowIs404() {
        RequirementEntity entity = mock(RequirementEntity.class);
        when(requirements.requireVisible(REQUIREMENT)).thenReturn(entity);
        when(follows.delete(REQUIREMENT, VIEWER)).thenReturn(0);

        assertThatThrownBy(() -> service.unfollow(REQUIREMENT))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.status()).isEqualTo(404);
                    assertThat(api.code()).isEqualTo(RequirementErrors.NOT_FOUND);
                });
    }

    @Test
    @DisplayName("备注超过 255 截断而不是 500；空白备注落库为 NULL")
    void noteIsNormalized() {
        entity(null, "IN_DEV", 2L, null, false);
        when(follows.insert(anyLong(), anyLong(), any(), any())).thenReturn(1);

        service.follow(REQUIREMENT, new FollowRequest("x".repeat(300)));
        verify(follows).insert(eq(REQUIREMENT), eq(VIEWER), eq(88L), eq("x".repeat(255)));

        service.follow(REQUIREMENT, new FollowRequest("   "));
        verify(follows).insert(eq(REQUIREMENT), eq(VIEWER), eq(88L), eq(null));
    }

    @Test
    void requireInScopeRejectsOutOfScope() {
        RequirementEntity entity = entity(null, "PENDING_ANALYSIS", 2L, 3L, false);
        when(requirements.requireVisible(REQUIREMENT)).thenReturn(entity);
        when(follows.visible(REQUIREMENT)).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.follow(REQUIREMENT, new FollowRequest("厂商不可见")));

        assertEquals(403, ex.status());
        assertEquals(RequirementErrors.OUT_OF_SCOPE, ex.code());
        verify(follows, never()).insert(anyLong(), anyLong(), any(), any());
        verify(notifications, never()).send(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyLong());
    }
}
