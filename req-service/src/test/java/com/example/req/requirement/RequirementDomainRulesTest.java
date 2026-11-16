package com.example.req.requirement;

import com.example.platform.contract.DataScope;
import com.example.platform.contract.ScopeTier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * r23 AC-10：提报 domainIds 的规则部分是纯函数（必填 / 非法值 / 去重 / 存在性 / 越权），
 * 脱离 Spring 单测；这里同时固定「空集=该维度不限制」的 DataScope 注释语义。
 */
class RequirementDomainRulesTest {

    @Test
    void checkDomainIdsRules() {
        RequirementRules.Violation missing = RequirementRules.checkDomainIds(null);
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.code()).isEqualTo(RequirementRules.VALIDATION_FAILED);
        assertThat(missing.message()).isEqualTo("请选择业务域");
        assertThat(new ArrayList<>(missing.detail().keySet())).containsExactly("field");
        assertThat(missing.detail().get("field")).isEqualTo("domainIds");

        RequirementRules.Violation empty = RequirementRules.checkDomainIds(List.of());
        assertThat(empty.status()).isEqualTo(400);
        assertThat(empty.message()).isEqualTo("请选择业务域");

        assertThat(RequirementRules.checkDomainIds(List.of(1L, 2L))).isNull();

        RequirementRules.Violation invalid =
                RequirementRules.checkDomainIds(Arrays.asList(3L, null, 0L, -1L, 4L));
        assertThat(invalid.status()).isEqualTo(400);
        Map<String, Object> detail = invalid.detail();
        assertThat(new ArrayList<>(detail.keySet())).containsExactly("field", "invalidIds");
        assertThat(detail.get("field")).isEqualTo("domainIds");
        assertThat(detail.get("invalidIds")).isEqualTo(Arrays.asList(null, 0L, -1L));
    }

    @Test
    void distinctDomainIdsKeepsOrder() {
        assertThat(RequirementRules.distinctDomainIds(List.of(3L, 1L, 3L, 2L, 1L)))
                .containsExactly(3L, 1L, 2L);
        assertThat(RequirementRules.distinctDomainIds(List.of())).isEmpty();
        assertThat(RequirementRules.distinctDomainIds(null)).isEmpty();
    }

    @Test
    void unseenIdsAreReportedOnce() {
        assertThat(RequirementDomainValidator.unseen(List.of(1L, 2L, 3L), List.of(1L, 2L)))
                .containsExactly(3L);
        assertThat(RequirementDomainValidator.unseen(List.of(1L, 2L), List.of(1L, 2L))).isEmpty();
        assertThat(RequirementDomainValidator.unseen(List.of(7L, 8L), List.of())).containsExactly(7L, 8L);
        assertThat(RequirementDomainValidator.unseen(List.of(), List.of(1L))).isEmpty();
    }

    /** 空集=该维度不限制：TENANT 与「DOMAIN 但 domainIds 为空」都不产生越权。 */
    @Test
    void outOfScopeEmptySetMeansUnrestricted() {
        DataScope tenant = DataScope.system(1L);
        assertThat(RequirementDomainValidator.outOfScope(List.of(9L), tenant)).isEmpty();

        DataScope domainWithoutIds =
                DataScope.of(1L, ScopeTier.DOMAIN, Set.of(), null, 0L, Set.of());
        assertThat(RequirementDomainValidator.outOfScope(List.of(9L), domainWithoutIds)).isEmpty();

        assertThat(RequirementDomainValidator.outOfScope(List.of(9L), null)).isEmpty();
    }

    @Test
    void outOfScopeReportsForeignDomainsInRequestOrder() {
        DataScope domain = DataScope.of(1L, ScopeTier.DOMAIN, Set.of(1L), null, 0L, Set.of());
        assertThat(RequirementDomainValidator.outOfScope(List.of(1L), domain)).isEmpty();
        assertThat(RequirementDomainValidator.outOfScope(List.of(1L, 2L, 3L), domain))
                .containsExactly(2L, 3L);

        DataScope vendor = DataScope.of(1L, ScopeTier.VENDOR, Set.of(4L), 9L, 0L, Set.of());
        assertThat(RequirementDomainValidator.outOfScope(List.of(4L), vendor)).isEmpty();
        assertThat(RequirementDomainValidator.outOfScope(List.of(5L), vendor)).containsExactly(5L);
    }
}
