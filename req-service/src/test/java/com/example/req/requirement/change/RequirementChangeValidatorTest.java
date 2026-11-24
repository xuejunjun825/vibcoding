package com.example.req.requirement.change;

import com.example.platform.common.error.ApiException;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.ScopeTier;
import com.example.req.release.ReleaseEntity;
import com.example.req.release.ReleaseService;
import com.example.req.requirement.RequirementEntity;
import com.example.req.requirement.RequirementRepository;
import com.example.req.requirement.RequirementStateMachine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 变更单人天规则的作用范围：只判「本次真的改了人天」的单据。
 *
 * <p>父需求（BUS/PDR）库里带汇总人天，而 after 是「整份快照 + patch」，若按 after 原样判，
 * 父需求连 CONTENT/RELEASE 都建不出单（r43 QA 发现的 P1：矩阵说 CHANGE_REQUIRED，
 * 前端画出入口，点下去 400 PARENT_DAYS_READONLY）。本测试锁住修复后的两侧语义：
 * 无关人天的变更必须放行，真正改人天仍必须拒。</p>
 *
 * <p>r44 追加三例：缺 {@code changeType}（null）/ 空串与空格 / 未知类型都必须 400
 * {@code CHANGE_FIELD_NOT_ALLOWED} 而不是 500（不可变集合 {@code contains(null)} 抛 NPE），
 * 且 detail 必须带 {@code allowedTypes} 四值，前端据此给出可选类型。</p>
 */
class RequirementChangeValidatorTest {

    private final RequirementRepository requirements = mock(RequirementRepository.class);
    private final ReleaseService releases = mock(ReleaseService.class);
    private final RequirementChangeValidator validator =
            new RequirementChangeValidator(requirements, releases, mock(RequirementStateMachine.class));

    private static final long TENANT = 1L;
    private static final long ACTOR = 7L;

    @BeforeEach
    void setUp() {
        // RD/PDR 必须有父需求，父链校验会读 TenantContext（与生产同一条路径）
        TenantContext.set(DataScope.of(TENANT, ScopeTier.TENANT, Set.of(), null, ACTOR, Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void parentContentChangeIsNotBlockedByRollupDays() {
        RequirementEntity parent = parent("BUS", "POOLED", new BigDecimal("16.0"), new BigDecimal("4.0"));

        Map<String, Object> after = validator.validateCreate(parent, "CONTENT", "标题写错了",
                Map.of("title", "采购订单协同 v2"));

        assertThat(after.get("title")).isEqualTo("采购订单协同 v2");
        // 快照仍是完整 7 字段：没改的人天原样保留，apply 才能证明「没动人天」
        assertThat(after.get("estimateDays")).isEqualTo(new BigDecimal("16.0"));
    }

    @Test
    void parentReleaseChangeIsNotBlockedByRollupDays() {
        RequirementEntity parent = parent("BUS", "POOLED", new BigDecimal("16.0"), new BigDecimal("4.0"));
        ReleaseEntity release = new ReleaseEntity();
        release.setId(42L);
        release.setStatus("PLANNING");
        when(releases.requireVisible(42L)).thenReturn(release);

        Map<String, Object> after = validator.validateCreate(parent, "RELEASE", "排期改了",
                Map.of("releaseId", 42));

        assertThat(after.get("releaseId")).isEqualTo(42L);
    }

    @Test
    void parentDaysChangeStillRejectedAsRollup() {
        RequirementEntity parent = parent("BUS", "POOLED", new BigDecimal("16.0"), new BigDecimal("4.0"));

        ApiException error = catchThrowableOfType(
                () -> validator.validateCreate(parent, "DAYS", "人天调一下",
                        Map.of("estimateDays", 7)),
                ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.status()).isEqualTo(400);
        assertThat(error.code()).isEqualTo("PARENT_DAYS_READONLY");
    }

    @Test
    void childHalfDayPrecisionStillEnforced() {
        RequirementEntity child = childOfPdr(new BigDecimal("2.0"), null);

        ApiException error = catchThrowableOfType(
                () -> validator.validateCreate(child, "DAYS", "人天调一下",
                        Map.of("estimateDays", new BigDecimal("2.3"))),
                ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.status()).isEqualTo(400);
        assertThat(error.code()).isEqualTo("INVALID_DAY_PRECISION");
    }

    @Test
    void childContentChangePassesWithRollupFreeDays() {
        RequirementEntity child = childOfPdr(new BigDecimal("2.0"), new BigDecimal("1.0"));

        Map<String, Object> after = validator.validateCreate(child, "CONTENT", "标题写错了",
                Map.of("title", "库存预警定时任务 v2"));

        assertThat(after.get("title")).isEqualTo("库存预警定时任务 v2");
        assertThat(after.get("estimateDays")).isEqualTo(new BigDecimal("2.0"));
    }

    // ------------------------------------------------------- r44：缺类型 / 空白 / 未知类型

    /**
     * 漏传 changeType 是 400 契约错误，不是 NPE。
     *
     * <p>修复前 {@code TYPES.contains(null)} 抛 NPE 穿出控制器，GlobalExceptionHandler 刻意
     * 不 catch → 默认 500 且无 code。这里同时锁「状态 400 + 稳定 code」，并断言 detail 恰好
     * 两键：{@code changeType} 与 {@code field} 都是 null，被 {@code ApiException} 丢弃。</p>
     */
    @Test
    void missingChangeTypeIs400NotNpe() {
        RequirementEntity entity = parent("RD", "IN_DEV", null, null);

        ApiException error = catchThrowableOfType(
                () -> validator.validateCreate(entity, null, "标题写错了", Map.of("title", "新标题")),
                ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.status()).isEqualTo(400);
        assertThat(error.code()).isEqualTo("CHANGE_FIELD_NOT_ALLOWED");
        assertThat(error.detail()).containsOnlyKeys("allowedFields", "allowedTypes");
        assertThat(error.detail().get("allowedFields")).isEqualTo(List.of());
        // 四参重载的两个相邻 List 实参换位不会编译报错：allowedFields 空 + allowedTypes 四值
        // 同时断言，任一换位必红
        assertThat(error.detail().get("allowedTypes"))
                .isEqualTo(List.of("CONTENT", "DAYS", "PARENT", "RELEASE"));
    }

    /** 空串/空格一直是 400（走 equals 路径），但仍必须带 allowedTypes——与缺键同一形状契约。 */
    @Test
    void blankChangeTypeIs400() {
        RequirementEntity entity = parent("RD", "IN_DEV", null, null);

        for (String blank : List.of("", " ", "   ")) {
            ApiException error = catchThrowableOfType(
                    () -> validator.validateCreate(entity, blank, "标题写错了", Map.of("title", "新标题")),
                    ApiException.class);

            assertThat(error).as("changeType=<%s> 必须是契约 400", blank).isNotNull();
            assertThat(error.status()).as("changeType=<%s>", blank).isEqualTo(400);
            assertThat(error.code()).as("changeType=<%s>", blank).isEqualTo("CHANGE_FIELD_NOT_ALLOWED");
            // 空白串非 null，因此 changeType 键保留原值（与缺键的两键形状可区分）
            assertThat(error.detail()).as("changeType=<%s>", blank)
                    .containsOnlyKeys("changeType", "allowedFields", "allowedTypes");
            assertThat(error.detail().get("changeType")).as("changeType=<%s>", blank).isEqualTo(blank);
            assertThat(error.detail().get("allowedFields")).as("changeType=<%s>", blank)
                    .isEqualTo(List.of());
            assertThat(error.detail().get("allowedTypes")).as("changeType=<%s>", blank)
                    .isEqualTo(List.of("CONTENT", "DAYS", "PARENT", "RELEASE"));
        }
    }

    /** 未知类型（非 null）同样带 allowedTypes，且 detail 保留原 changeType 供排查拼写。 */
    @Test
    void unknownChangeTypeDetailCarriesAllowedTypes() {
        RequirementEntity entity = parent("RD", "IN_DEV", null, null);

        ApiException error = catchThrowableOfType(
                () -> validator.validateCreate(entity, "NOPE", "标题写错了", Map.of("title", "新标题")),
                ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.status()).isEqualTo(400);
        assertThat(error.code()).isEqualTo("CHANGE_FIELD_NOT_ALLOWED");
        assertThat(error.detail()).hasSize(3).containsEntry("changeType", "NOPE")
                .doesNotContainKey("field");
        assertThat(error.detail().get("allowedFields")).isEqualTo(List.of());
        assertThat(error.detail().get("allowedTypes"))
                .isEqualTo(List.of("CONTENT", "DAYS", "PARENT", "RELEASE"));
    }

    /** RD 必须有父需求，且只允许 BUS → PDR → RD：给一条真实父链，父状态门/祖先环都走一遍。 */
    private RequirementEntity childOfPdr(BigDecimal estimateDays, BigDecimal actualDays) {
        RequirementEntity child = parent("RD", "IN_DEV", estimateDays, actualDays);
        child.setParentId(2001L);
        RequirementEntity pdr = parent("PDR", "IN_DEV", null, null);
        pdr.setId(2001L);
        pdr.setParentId(2003L);
        RequirementEntity bus = parent("BUS", "POOLED", null, null);
        bus.setId(2003L);
        when(requirements.findByIdAndTenantId(2001L, TENANT)).thenReturn(Optional.of(pdr));
        when(requirements.findByIdAndTenantId(2003L, TENANT)).thenReturn(Optional.of(bus));
        return child;
    }

    private static RequirementEntity parent(String type, String status,
                                           BigDecimal estimateDays, BigDecimal actualDays) {
        RequirementEntity entity = new RequirementEntity();
        entity.setId(1001L);
        entity.setTenantId(1L);
        entity.setCode("REQ-1001");
        entity.setTitle("原标题");
        entity.setType(type);
        entity.setStatus(status);
        entity.setPriority("P2");
        entity.setEstimateDays(estimateDays);
        entity.setActualDays(actualDays);
        return entity;
    }
}
