package com.example.req.requirement.change;

import com.example.platform.common.error.ApiException;
import com.example.req.requirement.RequirementDtos;
import com.example.req.requirement.RequirementEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * AC-13/AC-15：PUT 收口的四条不变量。
 *
 * <ol>
 *   <li>晚期态改四类字段 → 409 {@code CHANGE_REQUIRED}，且 detail 直接给出发起端点与字段清单；</li>
 *   <li>早期态（逐类型表）仍可直接编辑；</li>
 *   <li>只改 domainIds/sonarKey，或写相同值 → 不拦（否则「保存」按钮会无故报错）；</li>
 *   <li>releaseId 走 extra，同样受收口约束——它不是漏网字段。</li>
 * </ol>
 */
class RequirementChangeGuardTest {

    @Test
    void lateStageContentEditIsRejectedWithTheChangeEndpoint() {
        RequirementEntity entity = requirement("RD", "DEVELOPING");
        RequirementDtos.UpdateRequest request = new RequirementDtos.UpdateRequest();
        request.title = "改后的标题";

        ApiException error = catchThrowableOfType(
                () -> RequirementChangeGuard.rejectDirectEdit(entity, request), ApiException.class);

        assertThat(error.status()).isEqualTo(409);
        assertThat(error.code()).isEqualTo("CHANGE_REQUIRED");
        assertThat(error.detail())
                .containsEntry("requirementId", 11L)
                .containsEntry("fields", List.of("title"))
                .containsEntry("changeEndpoint", "/api/req/requirements/11/changes");
    }

    @Test
    void everyGuardedFieldIsCoveredIncludingTheOnesInExtra() {
        RequirementEntity entity = requirement("RD", "DEVELOPING");

        for (String field : RequirementChangeFields.GUARDED_FIELDS) {
            RequirementDtos.UpdateRequest request = requestTouching(field);
            ApiException error = catchThrowableOfType(
                    () -> RequirementChangeGuard.rejectDirectEdit(entity, request), ApiException.class);
            assertThat(error).as(field + " must go through a change order").isNotNull();
            assertThat(error.code()).as(field).isEqualTo("CHANGE_REQUIRED");
            assertThat(error.detail().get("fields")).as(field).isEqualTo(List.of(field));
        }
    }

    @Test
    void earlyStageEditStillGoesThrough() {
        // BUS 在 SUBMITTED/APPROVING/PENDING_REVIEW 内允许直接编辑；PDR 只在 PENDING_ANALYSIS。
        RequirementDtos.UpdateRequest request = new RequirementDtos.UpdateRequest();
        request.title = "改后的标题";
        assertThatCode(() -> RequirementChangeGuard.rejectDirectEdit(requirement("BUS", "SUBMITTED"), request))
                .doesNotThrowAnyException();
        assertThatCode(() -> RequirementChangeGuard.rejectDirectEdit(
                requirement("PDR", "PENDING_ANALYSIS"), request)).doesNotThrowAnyException();
    }

    @Test
    void nonContentFieldsAndIdenticalValuesAreNotGuarded() {
        RequirementEntity entity = requirement("RD", "DEVELOPING");

        RequirementDtos.UpdateRequest domainsOnly = new RequirementDtos.UpdateRequest();
        domainsOnly.domainIds = List.of(1L, 2L);
        domainsOnly.any("sonarProjectKey", "some-key");
        assertThatCode(() -> RequirementChangeGuard.rejectDirectEdit(entity, domainsOnly))
                .doesNotThrowAnyException();

        RequirementDtos.UpdateRequest sameValues = new RequirementDtos.UpdateRequest();
        sameValues.title = "原标题";
        sameValues.priority = "P2";
        sameValues.expectedDeliveryDate = LocalDate.of(2026, 12, 31);
        sameValues.any("estimateDays", 3);
        assertThatCode(() -> RequirementChangeGuard.rejectDirectEdit(entity, sameValues))
                .doesNotThrowAnyException();
    }

    /** 同一次 PUT 里改了多个字段：一次性列出全部，前端才能整组锁定。 */
    @Test
    void allChangedFieldsAreListedTogether() {
        RequirementEntity entity = requirement("RD", "DEVELOPING");
        RequirementDtos.UpdateRequest request = new RequirementDtos.UpdateRequest();
        request.title = "改后的标题";
        request.priority = "P0";
        request.any("releaseId", 31);

        ApiException error = catchThrowableOfType(
                () -> RequirementChangeGuard.rejectDirectEdit(entity, request), ApiException.class);

        assertThat(error.detail().get("fields")).isEqualTo(List.of("title", "priority", "releaseId"));
    }

    // ------------------------------------------------------------------ fixtures

    private static RequirementEntity requirement(String type, String status) {
        RequirementEntity entity = new RequirementEntity();
        entity.setId(11L);
        entity.setTenantId(1L);
        entity.setCode("PDR-0001");
        entity.setTitle("原标题");
        entity.setType(type);
        entity.setStatus(status);
        entity.setPriority("P2");
        entity.setExpectedDeliveryDate(LocalDate.of(2026, 12, 31));
        entity.setEstimateDays(new java.math.BigDecimal("3"));
        return entity;
    }

    private static RequirementDtos.UpdateRequest requestTouching(String field) {
        RequirementDtos.UpdateRequest request = new RequirementDtos.UpdateRequest();
        switch (field) {
            case "title" -> request.title = "改后的标题";
            case "description" -> request.description = "改后的描述";
            case "priority" -> request.priority = "P0";
            case "expectedDeliveryDate" -> request.expectedDeliveryDate = LocalDate.of(2027, 1, 31);
            case "estimateDays" -> request.any("estimateDays", 8);
            case "parentId" -> request.any("parentId", 5);
            case "releaseId" -> request.any("releaseId", 31);
            default -> throw new IllegalArgumentException("unmapped field: " + field);
        }
        return request;
    }
}
