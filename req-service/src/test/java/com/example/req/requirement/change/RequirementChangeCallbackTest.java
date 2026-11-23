package com.example.req.requirement.change;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AC-5：审批结果只推进单据状态（{@code PENDING → APPROVED/REJECTED/WITHDRAWN}），
 * 幂等且绝不动需求字段；条件更新命中 0 行不抛，避免回滚引擎已写完的审批结果。
 */
class RequirementChangeCallbackTest {

    private static final long TENANT = 1L;
    private static final long CHANGE = 99L;
    private static final long INSTANCE = 42L;

    private final RequirementChangeRepository changes = mock(RequirementChangeRepository.class);
    private final RequirementChangeCallback callback = new RequirementChangeCallback(changes);

    @Test
    void approvedOnlyAdvancesPendingToApproved() {
        when(changes.findById(CHANGE)).thenReturn(Optional.of(row("PENDING")));

        callback.onApproved(CHANGE, INSTANCE);

        verify(changes).markStatus(CHANGE, TENANT, "PENDING", "APPROVED");
    }

    @Test
    void rejectedAndWithdrawnAreTerminalButNeverApplied() {
        when(changes.findById(CHANGE)).thenReturn(Optional.of(row("PENDING")));

        callback.onRejected(CHANGE, INSTANCE, "字段写错");
        callback.onWithdrawn(CHANGE, INSTANCE);

        verify(changes).markStatus(CHANGE, TENANT, "PENDING", "REJECTED");
        verify(changes).markStatus(CHANGE, TENANT, "PENDING", "WITHDRAWN");
    }

    @Test
    void repeatedCallbackOnAppliedRowIsSwallowed() {
        // 第二次回调：条件更新命中 0 行（APPROVED→APPLIED 已经把状态改走），回调不能因此抛异常。
        when(changes.findById(CHANGE)).thenReturn(Optional.of(row("APPLIED")));
        when(changes.markStatus(CHANGE, TENANT, "PENDING", "APPROVED")).thenReturn(0);

        assertThatCode(() -> callback.onApproved(CHANGE, INSTANCE)).doesNotThrowAnyException();
        verify(changes).markStatus(CHANGE, TENANT, "PENDING", "APPROVED");
    }

    @Test
    void unknownChangeIsIgnored() {
        when(changes.findById(CHANGE)).thenReturn(Optional.empty());

        assertThatCode(() -> callback.onApproved(CHANGE, INSTANCE)).doesNotThrowAnyException();
        verify(changes, never()).markStatus(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void bizTypeRegistersAsSixthDocumentType() {
        assertThat(callback.bizType()).isEqualTo("REQ_CHANGE");
    }

    private static RequirementChangeEntity row(String status) {
        RequirementChangeEntity row = new RequirementChangeEntity();
        row.setId(CHANGE);
        row.setTenantId(TENANT);
        row.setRequirementId(11L);
        row.setChangeType("DAYS");
        row.setStatus(status);
        row.setInstanceId(INSTANCE);
        return row;
    }
}
