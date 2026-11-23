package com.example.req.requirement.change;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.req.approval.WorkflowJson;
import com.example.req.release.ReleaseEntity;
import com.example.req.release.ReleaseRepository;
import com.example.req.release.ReleaseRequirementEntity;
import com.example.req.release.ReleaseRequirementRepository;
import com.example.req.release.ReleaseService;
import com.example.req.requirement.RequirementEntity;
import com.example.req.requirement.RequirementRepository;
import com.example.req.requirement.RequirementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AC-6/AC-7/AC-8/AC-12：apply 是「审批通过 ≠ 生效」的唯一落点。
 *
 * <p>这组断言盯的是顺序不变量：状态闸在前（非 APPROVED 零写）、before 比对在写之前
 * （冲突零写）、items 与审计与字段写在同一事务里、锁定版本额外留痕。</p>
 */
class RequirementChangeApplyServiceTest {

    private static final long TENANT = 1L;
    private static final long REQUIREMENT = 11L;
    private static final long CHANGE = 99L;
    private static final long ACTOR = 7L;

    private final RequirementChangeRepository changes = mock(RequirementChangeRepository.class);
    private final RequirementChangeItemRepository items = mock(RequirementChangeItemRepository.class);
    private final RequirementRepository requirements = mock(RequirementRepository.class);
    private final RequirementService requirementService = mock(RequirementService.class);
    private final RequirementChangeValidator validator = mock(RequirementChangeValidator.class);
    private final RequirementChangeService changeService = mock(RequirementChangeService.class);
    private final RequirementReleaseMembership membership = mock(RequirementReleaseMembership.class);
    private final ReleaseRepository releases = mock(ReleaseRepository.class);
    private final ReleaseRequirementRepository releaseMembers = mock(ReleaseRequirementRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final WorkflowJson json = new WorkflowJson(new ObjectMapper());

    private final RequirementChangeApplyService service = new RequirementChangeApplyService(
            changes, items, requirements, requirementService, validator, changeService,
            membership, releases, releaseMembers, audit, json);

    @BeforeEach
    void setUp() {
        // 真实调用人（7），而不是 system(0)：apply 的 actor 要落进 appliedBy/updatedBy/审计。
        TenantContext.set(DataScope.of(TENANT, com.example.platform.contract.ScopeTier.TENANT,
                java.util.Set.of(), null, ACTOR, java.util.Set.of()));
        when(changeService.detail(CHANGE)).thenReturn(detail("APPLIED", false));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void pendingChangeCannotBeApplied() {
        givenChange(changeRow("PENDING"));

        ApiException error = catchThrowableOfType(() -> service.apply(CHANGE), ApiException.class);

        assertThat(error.status()).isEqualTo(409);
        assertThat(error.code()).isEqualTo("CHANGE_NOT_APPROVED");
        assertThat(error.detail()).containsEntry("status", "PENDING");
        verifyNoInteractions(requirements, audit, items);
        verify(changes, never()).markApplied(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void alreadyAppliedChangeReportsItsApplyTime() {
        RequirementChangeEntity row = changeRow("APPLIED");
        row.setAppliedAt(LocalDateTime.of(2026, 11, 23, 10, 30));
        givenChange(row);

        ApiException error = catchThrowableOfType(() -> service.apply(CHANGE), ApiException.class);

        assertThat(error.code()).isEqualTo("CHANGE_ALREADY_APPLIED");
        assertThat(error.detail()).containsEntry("appliedAt", "2026-11-23T10:30");
        verify(changes, never()).markApplied(anyLong(), anyLong(), any(), any(), any());
    }

    /** 审批期间别人改过同一字段：409 且一个字节都不写（不是「后写覆盖」）。 */
    @Test
    void concurrentEditIsAConflictAndWritesNothing() {
        givenChange(changeRow("APPROVED"));
        RequirementEntity entity = requirement();
        entity.setTitle("别人改过的标题");
        when(requirements.findWithLockByIdAndTenantId(REQUIREMENT, TENANT)).thenReturn(Optional.of(entity));

        ApiException error = catchThrowableOfType(() -> service.apply(CHANGE), ApiException.class);

        assertThat(error.status()).isEqualTo(409);
        assertThat(error.code()).isEqualTo("CHANGE_CONFLICT");
        assertThat(error.detail())
                .containsEntry("field", "title")
                .containsEntry("expected", "原标题")
                .containsEntry("actual", "别人改过的标题");
        verify(changes, never()).markApplied(anyLong(), anyLong(), any(), any(), any());
        verify(requirements, never()).save(any());
        verifyNoInteractions(items, audit, validator);
    }

    @Test
    void appliesFieldsWritesItemsAndOneAudit() {
        givenChange(changeRow("APPROVED"));
        RequirementEntity entity = requirement();
        when(requirements.findWithLockByIdAndTenantId(REQUIREMENT, TENANT)).thenReturn(Optional.of(entity));
        when(changes.markApplied(eq(CHANGE), eq(TENANT), eq("APPROVED"), any(), eq(ACTOR))).thenReturn(1);

        RequirementChangeViews.Detail result = service.apply(CHANGE);

        assertThat(result.status()).isEqualTo("APPLIED");
        assertThat(entity.getTitle()).isEqualTo("改后的标题");
        assertThat(entity.getUpdatedBy()).isEqualTo(ACTOR);
        verify(requirements).save(entity);
        verify(validator).validateApply(eq(entity), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RequirementChangeItemEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(items).saveAll(rows.capture());
        assertThat(rows.getValue()).hasSize(1);
        RequirementChangeItemEntity item = rows.getValue().get(0);
        assertThat(item.getChangeId()).isEqualTo(CHANGE);
        assertThat(item.getField()).isEqualTo("title");
        assertThat(item.getBeforeValue()).isEqualTo("原标题");
        assertThat(item.getAfterValue()).isEqualTo("改后的标题");

        ArgumentCaptor<Map<String, Object>> detail = detailCaptor();
        verify(audit, times(1)).record(eq("REQUIREMENT_CHANGE_APPLIED"), eq("REQ_CHANGE"),
                eq(CHANGE), detail.capture());
        Map<String, Object> payload = detail.getValue();
        assertThat(payload).containsEntry("changeId", CHANGE)
                .containsEntry("requirementId", REQUIREMENT)
                .containsEntry("changeType", "CONTENT")
                .containsEntry("fields", List.of("title"))
                .containsEntry("type", "PDR");
        assertThat(asMap(payload.get("before"))).containsEntry("title", "原标题");
        assertThat(asMap(payload.get("after"))).containsEntry("title", "改后的标题");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    void releaseChangeMovesMembershipBeforeWritingTheField() {
        RequirementChangeEntity row = changeRow("APPROVED");
        row.setBeforeJson("{\"title\":\"原标题\",\"releaseId\":null}");
        row.setAfterJson("{\"title\":\"原标题\",\"releaseId\":31}");
        givenChange(row);
        RequirementEntity entity = requirement();
        when(requirements.findWithLockByIdAndTenantId(REQUIREMENT, TENANT)).thenReturn(Optional.of(entity));
        when(changes.markApplied(eq(CHANGE), eq(TENANT), eq("APPROVED"), any(), eq(ACTOR))).thenReturn(1);

        service.apply(CHANGE);

        verify(membership).move(entity, 31L, ACTOR);
        assertThat(entity.getReleaseId()).isEqualTo(31L);
    }

    /** AC-10：在 LOCKED 版本上应用仍成功，但补一条留痕（不派生字段事件）。 */
    @Test
    void applyingOnLockedReleaseLeavesAnExtraAudit() {
        givenChange(changeRow("APPROVED"));
        RequirementEntity entity = requirement();
        entity.setReleaseId(31L);
        when(requirements.findWithLockByIdAndTenantId(REQUIREMENT, TENANT)).thenReturn(Optional.of(entity));
        when(changes.markApplied(eq(CHANGE), eq(TENANT), eq("APPROVED"), any(), eq(ACTOR))).thenReturn(1);
        ReleaseRequirementEntity member = new ReleaseRequirementEntity();
        member.setTenantId(TENANT);
        member.setReleaseId(31L);
        member.setRequirementId(REQUIREMENT);
        when(releaseMembers.findByTenantIdAndRequirementId(TENANT, REQUIREMENT)).thenReturn(List.of(member));
        ReleaseEntity release = new ReleaseEntity();
        release.setId(31L);
        release.setTenantId(TENANT);
        release.setStatus(ReleaseService.STATUS_LOCKED);
        when(releases.findByIdAndTenantId(31L, TENANT)).thenReturn(Optional.of(release));

        service.apply(CHANGE);

        ArgumentCaptor<Map<String, Object>> detail = detailCaptor();
        verify(audit).record(eq("CHANGE_AFTER_RELEASE_LOCK"), eq("REQ_CHANGE"), eq(CHANGE),
                detail.capture());
        assertThat(detail.getValue()).containsEntry("changeId", CHANGE)
                .containsEntry("requirementId", REQUIREMENT)
                .containsEntry("releaseId", 31L)
                .containsEntry("lockedReleaseIds", List.of(31L));
    }

    @Test
    void planReleaseApplicationLeavesNoLockAudit() {
        givenChange(changeRow("APPROVED"));
        RequirementEntity entity = requirement();
        when(requirements.findWithLockByIdAndTenantId(REQUIREMENT, TENANT)).thenReturn(Optional.of(entity));
        when(changes.markApplied(eq(CHANGE), eq(TENANT), eq("APPROVED"), any(), eq(ACTOR))).thenReturn(1);
        ReleaseRequirementEntity member = new ReleaseRequirementEntity();
        member.setTenantId(TENANT);
        member.setReleaseId(31L);
        member.setRequirementId(REQUIREMENT);
        when(releaseMembers.findByTenantIdAndRequirementId(TENANT, REQUIREMENT)).thenReturn(List.of(member));
        ReleaseEntity release = new ReleaseEntity();
        release.setId(31L);
        release.setTenantId(TENANT);
        release.setStatus(ReleaseService.STATUS_PLAN);
        when(releases.findByIdAndTenantId(31L, TENANT)).thenReturn(Optional.of(release));

        service.apply(CHANGE);

        verify(audit, never()).record(eq("CHANGE_AFTER_RELEASE_LOCK"), any(), any(), any());
    }

    /** 条件更新返回 0：同单已被另一线程应用/撤回，报冲突而不是继续写。 */
    @Test
    void losingTheConditionalUpdateIsAConflict() {
        givenChange(changeRow("APPROVED"));
        RequirementEntity entity = requirement();
        when(requirements.findWithLockByIdAndTenantId(REQUIREMENT, TENANT)).thenReturn(Optional.of(entity));
        when(changes.markApplied(eq(CHANGE), eq(TENANT), eq("APPROVED"), any(), eq(ACTOR))).thenReturn(0);

        ApiException error = catchThrowableOfType(() -> service.apply(CHANGE), ApiException.class);

        assertThat(error.code()).isEqualTo("CHANGE_CONFLICT");
        verify(requirements, never()).save(any());
        verifyNoInteractions(items, audit);
    }

    @Test
    void unknownChangeIsNotFound() {
        when(changes.findByIdAndTenantId(CHANGE, TENANT)).thenReturn(Optional.empty());

        ApiException error = catchThrowableOfType(() -> service.apply(CHANGE), ApiException.class);

        assertThat(error.status()).isEqualTo(404);
        assertThat(error.code()).isEqualTo("NOT_FOUND");
    }

    // ------------------------------------------------------------------ fixtures

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> detailCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    private void givenChange(RequirementChangeEntity row) {
        when(changes.findByIdAndTenantId(CHANGE, TENANT)).thenReturn(Optional.of(row));
    }

    private static RequirementEntity requirement() {
        RequirementEntity entity = new RequirementEntity();
        entity.setId(REQUIREMENT);
        entity.setTenantId(TENANT);
        entity.setCode("PDR-0001");
        entity.setTitle("原标题");
        entity.setType("PDR");
        entity.setStatus("PENDING_ANALYSIS");
        entity.setDomainId(3L);
        entity.setPriority("P2");
        return entity;
    }

    private static RequirementChangeEntity changeRow(String status) {
        RequirementChangeEntity row = new RequirementChangeEntity();
        row.setId(CHANGE);
        row.setTenantId(TENANT);
        row.setRequirementId(REQUIREMENT);
        row.setChangeType("CONTENT");
        row.setStatus(status);
        row.setReason("标题写错了");
        row.setBeforeJson("{\"title\":\"原标题\"}");
        row.setAfterJson("{\"title\":\"改后的标题\"}");
        row.setImpactJson("{\"days\":{\"becomesStale\":false}}");
        row.setInstanceId(42L);
        row.setCreatedAt(LocalDateTime.now());
        row.setCreatedBy(ACTOR);
        return row;
    }

    private static RequirementChangeViews.Detail detail(String status, boolean canApply) {
        return new RequirementChangeViews.Detail(CHANGE,
                new RequirementChangeViews.RequirementRef(REQUIREMENT, "PDR-0001", "改后的标题", "PDR", "PENDING_ANALYSIS"),
                "CONTENT", status, "标题写错了", Map.of("title", "原标题"), Map.of("title", "改后的标题"),
                List.of(new RequirementChangeViews.Item(CHANGE, "title", "原标题", "改后的标题")),
                Map.of(), 42L, status.equals("APPLIED") ? LocalDateTime.now() : null,
                status.equals("APPLIED") ? ACTOR : null, ACTOR, LocalDateTime.now(), canApply);
    }
}
