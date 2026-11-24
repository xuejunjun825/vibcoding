package com.example.req.requirement;

import com.example.platform.common.error.ApiException;
import com.example.platform.contract.RequirementEditability;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * r43 PUT 收口（取代 r40 的 {@code RequirementChangeGuardTest}）：由「状态是否早期」改为
 * 「11 字段矩阵判定」，因此断言从「某状态整类字段被拦」变成「逐字段决策 + 两类 409 分流」。
 *
 * <ol>
 *   <li>patch 必须覆盖全部 11 字段（含 extra 里的 type/ownerUserId/sonarProjectKey 与 domainIds）；</li>
 *   <li>同值不产生变化——否则「保存」按钮在终态也会 409；</li>
 *   <li>混合请求只报 LOCKED 一类，field 取规范序首个被锁字段；</li>
 *   <li>终态 → 409 FIELD_LOCKED/TERMINAL；非 RD 人天 → 409 FIELD_LOCKED/PARENT_ROLLUP（取代旧 400）。</li>
 * </ol>
 */
class RequirementEditGuardTest {

    @Test
    void patchCarriesEveryFieldIncludingTheOnesInExtra() {
        RequirementDtos.UpdateRequest request = everyFieldRequest("RD");

        Map<String, Object> patch =
                RequirementEditGuard.patch(request, List.of(1L));

        assertThat(patch.keySet())
                .containsExactlyInAnyOrderElementsOf(RequirementEditability.FIELDS);
        assertThat(patch.get("domainIds")).isEqualTo(List.of(1L));
        assertThat(patch.get("sonarProjectKey")).isEqualTo("req-own-key");
        assertThat(patch.get("type")).isEqualTo("RD");
        assertThat(patch.get("ownerUserId")).isEqualTo(21);
    }

    @Test
    void snapshotCoversTheSameElevenFieldsInCanonicalOrder() {
        Map<String, Object> snapshot =
                RequirementEditGuard.snapshot(requirement("RD", "DEVELOPING"), List.of(1L));

        assertThat(snapshot.keySet()).containsExactlyElementsOf(RequirementEditability.FIELDS);
        assertThat(snapshot.get("type")).isEqualTo("RD");
    }

    @Test
    void identicalValuesProduceNoChanges() {
        RequirementEntity entity = requirement("BUS", "CLOSED");

        List<String> changed = RequirementEditGuard.changed(
                RequirementEditGuard.snapshot(entity, List.of(1L)),
                RequirementEditGuard.patch(everyFieldRequest("BUS"), List.of(1L)));

        assertThat(changed).isEmpty();
    }

    @Test
    void domainIdsAreComparedAsASetIgnoringOrderAndDuplicates() {
        RequirementEntity entity = requirement("BUS", "SUBMITTED");
        RequirementDtos.UpdateRequest request = new RequirementDtos.UpdateRequest();
        request.domainIds = List.of(2L, 1L, 2L);

        List<String> changed = RequirementEditGuard.changed(
                RequirementEditGuard.snapshot(entity, List.of(1L, 2L)),
                RequirementEditGuard.patch(request, List.of(1L, 2L)));

        assertThat(changed).isEmpty();
    }

    @Test
    void terminalReportsFieldLocked() {
        RequirementDtos.UpdateRequest request = new RequirementDtos.UpdateRequest();
        request.title = "改后的标题";

        ApiException error = rejection("BUS", "CLOSED", request);

        assertThat(error.status()).isEqualTo(409);
        assertThat(error.code()).isEqualTo(RequirementErrors.FIELD_LOCKED);
        assertThat(error.detail())
                .containsEntry("requirementId", 11L)
                .containsEntry("field", "title")
                .containsEntry("reasonCode", RequirementEditability.TERMINAL)
                .containsEntry("fields", List.of("title"));
    }

    /** PDR IN_DEV：title 受控、estimateDays 与非 RD 的 type 锁定——只报 LOCKED 一类，且只列锁定字段。 */
    @Test
    void lockedWinsOverControlled() {
        RequirementDtos.UpdateRequest request = new RequirementDtos.UpdateRequest();
        request.title = "改后的标题";
        request.any("estimateDays", 8);
        request.any("type", "RD");

        ApiException error = rejection("PDR", "IN_DEV", request);

        assertThat(error.code()).isEqualTo(RequirementErrors.FIELD_LOCKED);
        assertThat(error.detail()).containsEntry("field", "estimateDays")
                .containsEntry("reasonCode", RequirementEditability.PARENT_ROLLUP)
                .containsEntry("fields", List.of("estimateDays", "type"));
    }

    @Test
    void controlledFieldsAreListedInCanonicalOrderWithTheChangeEndpoint() {
        RequirementDtos.UpdateRequest request = new RequirementDtos.UpdateRequest();
        request.title = "改后的标题";
        request.priority = "P0";
        request.any("releaseId", 99);

        ApiException error = rejection("PDR", "IN_DEV", request);

        assertThat(error.status()).isEqualTo(409);
        assertThat(error.code()).isEqualTo(RequirementErrors.CHANGE_REQUIRED);
        assertThat(error.detail())
                .containsEntry("fields", List.of("title", "priority", "releaseId"))
                .containsEntry("changeEndpoint", "/api/req/requirements/11/changes");
    }

    @Test
    void nonRdDaysAreLockedAsParentRollupEvenInTheEarlyStage() {
        RequirementDtos.UpdateRequest request = new RequirementDtos.UpdateRequest();
        request.any("estimateDays", 12);

        ApiException error = rejection("BUS", "SUBMITTED", request);

        assertThat(error.code()).isEqualTo(RequirementErrors.FIELD_LOCKED);
        assertThat(error.detail()).containsEntry("field", "estimateDays")
                .containsEntry("reasonCode", RequirementEditability.PARENT_ROLLUP);
    }

    @Test
    void earlyStageContentEditStillGoesThrough() {
        RequirementDtos.UpdateRequest request = new RequirementDtos.UpdateRequest();
        request.title = "改后的标题";

        assertThatCode(() -> RequirementEditGuard.reject(11L, "BUS", "SUBMITTED",
                List.of("title"))).doesNotThrowAnyException();
        assertThatCode(() -> RequirementEditGuard.reject(11L, "PDR", "PENDING_ANALYSIS",
                List.of("title"))).doesNotThrowAnyException();
    }

    @Test
    void sonarKeyTrimmedEmptyMeansClearedAndOwnerTransferIsLocked() {
        RequirementEntity entity = requirement("RD", "IN_DEV");
        entity.setSonarProjectKey("   ");

        RequirementDtos.UpdateRequest clear = new RequirementDtos.UpdateRequest();
        clear.any("sonarProjectKey", null);
        assertThat(RequirementEditGuard.changed(
                RequirementEditGuard.snapshot(entity, List.of(1L)),
                RequirementEditGuard.patch(clear, List.of(1L)))).isEmpty();

        RequirementDtos.UpdateRequest transfer = new RequirementDtos.UpdateRequest();
        transfer.any("ownerUserId", 99);
        ApiException error = rejection("RD", "IN_DEV", transfer);
        assertThat(error.code()).isEqualTo(RequirementErrors.FIELD_LOCKED);
        assertThat(error.detail()).containsEntry("field", "ownerUserId")
                .containsEntry("reasonCode", RequirementEditability.ASSIGN_FLOW);
    }

    @Test
    void carriesActualDaysOnlyWhenTheRequestMentionsIt() {
        RequirementDtos.UpdateRequest plain = new RequirementDtos.UpdateRequest();
        plain.title = "改后的标题";
        assertThat(RequirementEditGuard.carriesActualDays(plain)).isFalse();

        RequirementDtos.UpdateRequest withActual = new RequirementDtos.UpdateRequest();
        withActual.any("actualDays", 4);
        assertThat(RequirementEditGuard.carriesActualDays(withActual)).isTrue();
    }

    // ------------------------------------------------------------------ fixtures

    private static ApiException rejection(String type, String status,
                                          RequirementDtos.UpdateRequest request) {
        RequirementEntity entity = requirement(type, status);
        List<Long> currentDomains = List.of(1L);
        List<String> changed = RequirementEditGuard.changed(
                RequirementEditGuard.snapshot(entity, currentDomains),
                RequirementEditGuard.patch(request, currentDomains));
        return catchThrowableOfType(
                () -> RequirementEditGuard.reject(11L, type, status, changed), ApiException.class);
    }

    /** 11 字段全部携带、且与 {@link #requirement} 完全同值（type 随夹具变化）。 */
    private static RequirementDtos.UpdateRequest everyFieldRequest(String type) {
        RequirementDtos.UpdateRequest request = new RequirementDtos.UpdateRequest();
        request.title = "原标题";
        request.description = "原描述";
        request.priority = "P2";
        request.expectedDeliveryDate = LocalDate.of(2026, 12, 31);
        request.domainIds = List.of(1L);
        request.any("estimateDays", 3);
        request.any("parentId", null);
        request.any("releaseId", 31);
        request.any("type", type);
        request.any("ownerUserId", 21);
        request.any("sonarProjectKey", "req-own-key");
        return request;
    }

    private static RequirementEntity requirement(String type, String status) {
        RequirementEntity entity = new RequirementEntity();
        entity.setId(11L);
        entity.setTenantId(1L);
        entity.setCode("PDR-0001");
        entity.setTitle("原标题");
        entity.setDescription("原描述");
        entity.setType(type);
        entity.setStatus(status);
        entity.setPriority("P2");
        entity.setExpectedDeliveryDate(LocalDate.of(2026, 12, 31));
        entity.setEstimateDays(new BigDecimal("3"));
        entity.setDomainId(1L);
        entity.setReleaseId(31L);
        entity.setOwnerUserId(21L);
        entity.setSonarProjectKey("req-own-key");
        return entity;
    }
}
