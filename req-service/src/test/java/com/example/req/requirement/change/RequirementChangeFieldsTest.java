package com.example.req.requirement.change;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * r44：{@code knownType} 是「外部字符串 → 白名单」之间唯一的边界判定，必须是全函数。
 *
 * <p>{@code TYPES} 是 JDK 不可变集合，{@code contains(null)} 抛 NPE 而不是返回 false：修复前
 * 漏传 {@code changeType} 的创建请求直接 500（且默认错误体没有 code，前端无法区分「参数错」
 * 与「服务崩」）。真值表同时钉住三件事——null/空白恒 false、四个合法值恒 true、
 * <b>刻意不做 trim/大小写归一</b>（类型串来自前端常量，归一化会掩盖拼错）。</p>
 *
 * <p>纯函数、无 Spring、无 Mock：这条边界退化会在毫秒级单测里红，不必等 HTTP 门禁。</p>
 */
class RequirementChangeFieldsTest {

    @Test
    @DisplayName("knownType 在 null 与空白上是全函数：返回 false，绝不抛异常")
    void knownTypeIsTotalOverNullAndBlank() {
        // 修复前的缺陷点：TYPES.contains(null) → ImmutableCollections$ListN.indexOf(null) → NPE
        assertThat(RequirementChangeFields.knownType(null)).isFalse();
        assertThat(RequirementChangeFields.knownType("")).isFalse();
        assertThat(RequirementChangeFields.knownType(" ")).isFalse();
        assertThat(RequirementChangeFields.knownType("   ")).isFalse();
        assertThat(RequirementChangeFields.knownType("\t")).isFalse();
        assertThat(RequirementChangeFields.knownType("\n")).isFalse();
    }

    @Test
    @DisplayName("四个合法变更类型恰好全为 true，且合法值集合只有这四个")
    void knownTypeAcceptsExactlyFourLegalTypes() {
        assertThat(RequirementChangeFields.knownType(RequirementChangeFields.TYPE_CONTENT)).isTrue();
        assertThat(RequirementChangeFields.knownType(RequirementChangeFields.TYPE_DAYS)).isTrue();
        assertThat(RequirementChangeFields.knownType(RequirementChangeFields.TYPE_PARENT)).isTrue();
        assertThat(RequirementChangeFields.knownType(RequirementChangeFields.TYPE_RELEASE)).isTrue();

        assertThat(RequirementChangeFields.TYPES)
                .containsExactly("CONTENT", "DAYS", "PARENT", "RELEASE");
    }

    @Test
    @DisplayName("大小写与前后空格不归一：只有逐字相等才算合法")
    void knownTypeRejectsCaseAndPaddingVariants() {
        assertThat(RequirementChangeFields.knownType("content")).isFalse();
        assertThat(RequirementChangeFields.knownType("Content")).isFalse();
        assertThat(RequirementChangeFields.knownType("nope")).isFalse();
        assertThat(RequirementChangeFields.knownType("NOPE")).isFalse();
        assertThat(RequirementChangeFields.knownType(" content ")).isFalse();
        assertThat(RequirementChangeFields.knownType("CONTENT ")).isFalse();
        assertThat(RequirementChangeFields.knownType(" CONTENT")).isFalse();
        assertThat(RequirementChangeFields.knownType("estimateDays")).isFalse();
    }

    @Test
    @DisplayName("allowedFields 对 null/空白/未知类型返回空表而不是抛异常")
    void allowedFieldsOfUnknownTypeIsEmpty() {
        assertThatCode(() -> RequirementChangeFields.allowedFields(null))
                .doesNotThrowAnyException();
        assertThat(RequirementChangeFields.allowedFields(null)).isEmpty();
        assertThat(RequirementChangeFields.allowedFields("")).isEmpty();
        assertThat(RequirementChangeFields.allowedFields(" ")).isEmpty();
        assertThat(RequirementChangeFields.allowedFields("NOPE")).isEmpty();

        // 全函数化没有把合法值一起吞掉：四个白名单仍是逐字段确定的
        assertThat(RequirementChangeFields.allowedFields(RequirementChangeFields.TYPE_CONTENT))
                .isEqualTo(RequirementChangeFields.CONTENT_FIELDS);
        assertThat(RequirementChangeFields.allowedFields(RequirementChangeFields.TYPE_DAYS))
                .isEqualTo(List.of("estimateDays"));
        assertThat(RequirementChangeFields.allowedFields(RequirementChangeFields.TYPE_PARENT))
                .isEqualTo(List.of("parentId"));
        assertThat(RequirementChangeFields.allowedFields(RequirementChangeFields.TYPE_RELEASE))
                .isEqualTo(List.of("releaseId"));
    }
}
