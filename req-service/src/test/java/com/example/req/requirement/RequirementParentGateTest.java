package com.example.req.requirement;

import com.example.platform.common.audit.AuditService;
import com.example.platform.common.code.CodeGenerator;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.notify.NotificationWriter;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.ActionPermissionResolver;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.StateTables;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * r23 AC-6/AC-7：父状态门是纯函数，遍历状态机的全部状态断言「允许/拒绝 + 409 形状」，
 * 并固定「详情 decomposeGate 与 409 同源」这一条，防止两套文案漂移。
 */
class RequirementParentGateTest {

    private static final String POOLED = "POOLED";
    private static final String BUS_MESSAGE = "父需求尚未入池（当前 %s，需 POOLED），入池后才能创建下级需求";
    private static final String PDR_MESSAGE =
            "父产品需求需先排期（当前 %s，需 SCHEDULED 或 IN_DEV），排期后才能创建研发需求";

    @Test
    void busGateOnlyPooled() {
        List<String> statuses = StateTables.statuses(RequirementRules.TYPE_BUS);
        assertThat(statuses).contains(POOLED);
        for (String status : statuses) {
            RequirementRules.ParentGate gate =
                    RequirementRules.parentGate(RequirementRules.TYPE_BUS, status);
            assertThat(gate.allowed()).as("BUS 父状态 %s", status).isEqualTo(POOLED.equals(status));
            assertThat(gate.required()).as("BUS 父状态 %s 的 required", status).isEqualTo(POOLED);
            if (POOLED.equals(status)) {
                assertThat(gate.code()).as("BUS %s 通过时无错误码", status).isNull();
                assertThat(gate.message()).as("BUS %s 通过时无文案", status).isNull();
                assertThat(RequirementRules.parentGateViolation(42L, status, gate)).isNull();
            } else {
                assertThat(gate.code()).isEqualTo(RequirementRules.PARENT_NOT_POOLED);
                assertThat(gate.message()).isEqualTo(BUS_MESSAGE.formatted(status));
                assertBusViolation(status, gate);
            }
        }
    }

    @Test
    void pdrGateAllowsScheduledAndInDev() {
        List<String> statuses = StateTables.statuses(RequirementRules.TYPE_PDR);
        assertThat(statuses).containsAll(List.of("SCHEDULED", "IN_DEV"));
        for (String status : statuses) {
            RequirementRules.ParentGate gate =
                    RequirementRules.parentGate(RequirementRules.TYPE_PDR, status);
            boolean allowed = List.of("SCHEDULED", "IN_DEV").contains(status);
            assertThat(gate.allowed()).as("PDR 父状态 %s", status).isEqualTo(allowed);
            assertThat(gate.required()).as("PDR 父状态 %s 的 allowed", status)
                    .isEqualTo(List.of("SCHEDULED", "IN_DEV"));
            if (allowed) {
                assertThat(gate.code()).isNull();
                assertThat(gate.message()).isNull();
                assertThat(RequirementRules.parentGateViolation(42L, status, gate)).isNull();
            } else {
                assertThat(gate.code()).isEqualTo(RequirementRules.PARENT_NOT_SCHEDULED);
                assertThat(gate.message()).isEqualTo(PDR_MESSAGE.formatted(status));
                assertPdrViolation(status, gate);
            }
        }
    }

    /** AC-7：allowed ⇔ 不产生 409；reason/message 逐字一致；required 与 detail 一致。 */
    @Test
    void decomposeGateMatchesViolation() {
        for (String type : List.of(RequirementRules.TYPE_BUS, RequirementRules.TYPE_PDR)) {
            for (String status : StateTables.statuses(type)) {
                RequirementRules.ParentGate gate = RequirementRules.parentGate(type, status);
                RequirementRules.Violation violation =
                        RequirementRules.parentGateViolation(42L, status, gate);
                RequirementViews.DecomposeGate view = RequirementViews.DecomposeGate.from(
                        RequirementRules.decomposeGate(type, status), status);

                assertThat(view.allowed()).as("%s/%s 的 decomposeGate.allowed", type, status)
                        .isEqualTo(gate.allowed());
                assertThat(view.parentStatus()).as("%s/%s 的 parentStatus", type, status)
                        .isEqualTo(status);
                if (gate.allowed()) {
                    assertThat(violation).as("%s/%s 不应产生 409", type, status).isNull();
                    assertThat(view.reason()).isNull();
                } else {
                    assertThat(violation.status()).isEqualTo(409);
                    assertThat(view.reason()).as("%s/%s 的 reason 必须等于 409 message", type, status)
                            .isEqualTo(violation.message());
                    assertThat(view.required())
                            .as("%s/%s 的 required 必须等于 detail", type, status)
                            .isEqualTo(violation.detail().get(
                                    RequirementRules.PARENT_NOT_POOLED.equals(gate.code())
                                            ? "required" : "allowed"));
                }
            }
        }
    }

    @Test
    void rdCannotDecompose() {
        for (String status : StateTables.statuses(RequirementRules.TYPE_RD)) {
            RequirementViews.DecomposeGate view = RequirementViews.DecomposeGate.from(
                    RequirementRules.decomposeGate(RequirementRules.TYPE_RD, status), status);
            assertThat(view.allowed()).as("RD/%s 不可再拆解", status).isFalse();
            assertThat(view.reason()).isEqualTo("该类型不能再拆解：RD");
        }
    }

    /** 状态改名防护：允许集必须仍是 PDR 状态表里的真状态，否则门会永久拒绝。 */
    @Test
    void allowedStatusesAreRealPdrStates() {
        List<String> pdr = StateTables.statuses(RequirementRules.TYPE_PDR);
        assertThat(pdr).containsAll(RequirementRules.RD_PARENT_ALLOWED);
        assertThat(RequirementRules.RD_PARENT_ALLOWED).doesNotContain(RequirementRules.REQUIRED_POOLED);
    }

    /** 主路径：直建（Service.create）在落库前被门拦下，且不写任何一行。 */
    @Test
    void createRejectsUnpooledParentBeforeInsert() {
        RequirementRepository repository = mock(RequirementRepository.class);
        when(repository.findByIdAndTenantId(7L, 1L)).thenReturn(Optional.of(parent("SUBMITTED")));
        RequirementService service = newService(repository);

        TenantContext.set(DataScope.system(1L));
        try {
            assertThatThrownBy(() -> service.create(new RequirementDtos.CreateCommand(
                    RequirementRules.TYPE_PDR, "子产品需求", null, List.of(1L), "P1",
                    null, 7L, null, null, null)))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.status()).isEqualTo(409);
                        assertThat(ex.code()).isEqualTo(RequirementRules.PARENT_NOT_POOLED);
                        // ApiException 用 Map.copyOf 收敛 detail（platform-common 既有实现），
                        // 键集合是契约、键序不是——这里按集合断言，避免与 platform-common 打架。
                        assertThat(ex.detail().keySet())
                                .containsExactlyInAnyOrder("parentId", "parentStatus", "required");
                        assertThat(ex.detail().get("parentId")).isEqualTo(7L);
                        assertThat(ex.detail().get("parentStatus")).isEqualTo("SUBMITTED");
                        assertThat(ex.detail().get("required")).isEqualTo(RequirementRules.REQUIRED_POOLED);
                    });
        } finally {
            TenantContext.clear();
        }
        verify(repository, never()).save(any());
    }

    /** 主路径：拆解走的是同一个 create，因此同码同结构（AC-6 三入口同源）。 */
    @Test
    void decomposeUsesSameGate() {
        RequirementRepository repository = mock(RequirementRepository.class);
        when(repository.findByIdAndTenantId(7L, 1L)).thenReturn(Optional.of(parent("SUBMITTED")));
        RequirementService service = newService(repository);

        RequirementDtos.DecomposeRequest request = new RequirementDtos.DecomposeRequest();
        request.title = "拆出来的产品需求";
        request.domainIds = List.of(1L);

        TenantContext.set(DataScope.system(1L));
        try {
            assertThatThrownBy(() -> service.decompose(7L, request))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.status()).isEqualTo(409);
                        assertThat(ex.code()).isEqualTo(RequirementRules.PARENT_NOT_POOLED);
                        assertThat(ex.detail().get("parentId")).isEqualTo(7L);
                    });
        } finally {
            TenantContext.clear();
        }
        verify(repository, never()).save(any());
    }

    private RequirementEntity parent(String status) {
        RequirementEntity parent = new RequirementEntity();
        parent.setId(7L);
        parent.setTenantId(1L);
        parent.setType(RequirementRules.TYPE_BUS);
        parent.setStatus(status);
        parent.setCode("BUS-0001");
        return parent;
    }

    @SuppressWarnings("unchecked")
    private RequirementService newService(RequirementRepository repository) {
        return new RequirementService(repository,
                mock(RequirementDomainRelRepository.class),
                mock(RequirementStateMachine.class),
                mock(CodeGenerator.class),
                mock(AuditService.class),
                mock(NotificationWriter.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(JdbcTemplate.class),
                mock(ActionPermissionResolver.class));
    }

    @Test
    void unknownParentTypeIsRejected() {
        assertThatThrownBy(() -> RequirementRules.parentGate(RequirementRules.TYPE_RD, "TODO"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知父需求类型");
    }

    private void assertBusViolation(String status, RequirementRules.ParentGate gate) {
        RequirementRules.Violation violation =
                RequirementRules.parentGateViolation(42L, status, gate);
        assertThat(violation.status()).isEqualTo(409);
        assertThat(violation.code()).isEqualTo(RequirementRules.PARENT_NOT_POOLED);
        assertThat(violation.message()).isEqualTo(BUS_MESSAGE.formatted(status));
        Map<String, Object> detail = violation.detail();
        assertThat(new ArrayList<>(detail.keySet()))
                .containsExactly("parentId", "parentStatus", "required");
        assertThat(detail.get("parentId")).isEqualTo(42L);
        assertThat(detail.get("parentStatus")).isEqualTo(status);
        assertThat(detail.get("required")).isEqualTo(POOLED);
    }

    private void assertPdrViolation(String status, RequirementRules.ParentGate gate) {
        RequirementRules.Violation violation =
                RequirementRules.parentGateViolation(42L, status, gate);
        assertThat(violation.status()).isEqualTo(409);
        assertThat(violation.code()).isEqualTo(RequirementRules.PARENT_NOT_SCHEDULED);
        assertThat(violation.message()).isEqualTo(PDR_MESSAGE.formatted(status));
        Map<String, Object> detail = violation.detail();
        assertThat(new ArrayList<>(detail.keySet()))
                .containsExactly("parentId", "parentStatus", "allowed");
        assertThat(detail.get("parentId")).isEqualTo(42L);
        assertThat(detail.get("parentStatus")).isEqualTo(status);
        assertThat(detail.get("allowed")).isEqualTo(List.of("SCHEDULED", "IN_DEV"));
    }
}
