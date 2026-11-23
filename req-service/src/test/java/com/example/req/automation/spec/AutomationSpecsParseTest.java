package com.example.req.automation.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.example.platform.common.error.ApiException;
import com.example.req.automation.spec.AutomationSpecs.ActionSpec;
import com.example.req.automation.spec.AutomationSpecs.ConditionSpec;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 条件与动作的封闭 schema：每个负例都断言<b>错误码</b>，而不是「抛了异常」。
 *
 * <p>为什么不写 @SpringBootTest：这里是纯函数，起容器只会把契约藏在 Spring 的转换层后面；
 * AC-2 要的是「服务端返回哪个码」，断言到码就够。</p>
 */
class AutomationSpecsParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) {
        return MAPPER.readTree(raw);
    }

    private static String codeOf(Runnable action) {
        ApiException ex = catchThrowableOfType(ApiException.class, action::run);
        assertThat(ex).as("应当抛出 ApiException").isNotNull();
        return ex.code();
    }

    // ---------------------------------------------------------------- 条件

    @Test
    void emptyConditionMeansMatchAll() {
        assertThat(ConditionSpec.parse(json("{}")).isEmpty()).isTrue();
        assertThat(ConditionSpec.parse(null).isEmpty()).isTrue();
    }

    @Test
    void unknownConditionKeyIsRejectedWithFieldName() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> ConditionSpec.parse(json("{\"bogus\":1}")));
        assertThat(ex.code()).isEqualTo(AutomationErrors.SCHEMA_INVALID);
        assertThat(ex.detail()).containsEntry("field", "bogus");
    }

    @Test
    void conditionAcceptsKnownEnumsFromStateTables() {
        ConditionSpec spec = ConditionSpec.parse(json(
                "{\"types\":[\"BUS\"],\"priorityIn\":[\"P0\",\"P1\"],\"statusIn\":[\"SUBMITTED\"],"
                        + "\"approvalResults\":[\"APPROVED\"],\"estimateDays\":{\"op\":\"gte\",\"value\":1.5},"
                        + "\"domainIds\":[1,2],\"vendorIds\":[3],\"fields\":[\"priority\"]}"));
        assertThat(spec.types()).containsExactly("BUS");
        assertThat(spec.statusIn()).containsExactly("SUBMITTED");
        assertThat(spec.estimateDays().op()).isEqualTo("gte");
        assertThat(spec.estimateDays().value()).isEqualByComparingTo("1.5");
        assertThat(spec.domainIds()).containsExactly(1L, 2L);
        assertThat(spec.isEmpty()).isFalse();
    }

    @Test
    void conditionRejectsUnboundedEnums() {
        assertThat(codeOf(() -> ConditionSpec.parse(json("{\"types\":[\"NOPE\"]}"))))
                .isEqualTo(AutomationErrors.SCHEMA_INVALID);
        assertThat(codeOf(() -> ConditionSpec.parse(json("{\"priorityIn\":[\"P9\"]}"))))
                .isEqualTo(AutomationErrors.SCHEMA_INVALID);
        assertThat(codeOf(() -> ConditionSpec.parse(json("{\"statusIn\":[\"NOT_A_STATUS\"]}"))))
                .isEqualTo(AutomationErrors.SCHEMA_INVALID);
        assertThat(codeOf(() -> ConditionSpec.parse(json("{\"approvalResults\":[\"MAYBE\"]}"))))
                .isEqualTo(AutomationErrors.SCHEMA_INVALID);
        assertThat(codeOf(() -> ConditionSpec.parse(json("{\"fields\":[\"summary\"]}"))))
                .isEqualTo(AutomationErrors.SCHEMA_INVALID);
    }

    @Test
    void estimateDaysIsBoundedToTwoDecimalsWithinRange() {
        assertThat(codeOf(() -> ConditionSpec.parse(json("{\"estimateDays\":{\"op\":\"~\",\"value\":1}}"))))
                .isEqualTo(AutomationErrors.SCHEMA_INVALID);
        assertThat(codeOf(() -> ConditionSpec.parse(json("{\"estimateDays\":{\"op\":\"gt\",\"value\":1001}}"))))
                .isEqualTo(AutomationErrors.SCHEMA_INVALID);
        assertThat(codeOf(() -> ConditionSpec.parse(json("{\"estimateDays\":{\"op\":\"gt\",\"value\":1.234}}"))))
                .isEqualTo(AutomationErrors.SCHEMA_INVALID);
        assertThat(codeOf(() -> ConditionSpec.parse(json("{\"estimateDays\":{\"op\":\"gt\",\"value\":-1}}"))))
                .isEqualTo(AutomationErrors.SCHEMA_INVALID);
    }

    // ---------------------------------------------------------------- 动作

    @Test
    void forbiddenActionsAreNamedInTheMessage() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> ActionSpec.parseArray(json("[{\"type\":\"SET_STATUS\",\"status\":\"DONE\"}]")));
        assertThat(ex.code()).isEqualTo(AutomationErrors.ACTION_NOT_ALLOWED);
        assertThat(ex.message()).contains("状态");
        assertThat(codeOf(() -> ActionSpec.parseArray(json("[{\"type\":\"APPROVE\"}]"))))
                .isEqualTo(AutomationErrors.ACTION_NOT_ALLOWED);
        assertThat(codeOf(() -> ActionSpec.parseArray(json("[{\"type\":\"UNKNOWN\"}]"))))
                .isEqualTo(AutomationErrors.ACTION_NOT_ALLOWED);
    }

    @Test
    void emptyActionsAreRejected() {
        assertThat(codeOf(() -> ActionSpec.parseArray(json("[]"))))
                .isEqualTo(AutomationErrors.SCHEMA_INVALID);
        assertThat(codeOf(() -> ActionSpec.parseArray(json("{}"))))
                .isEqualTo(AutomationErrors.SCHEMA_INVALID);
    }

    @Test
    void moreThanTenActionsAreRejected() {
        StringBuilder raw = new StringBuilder("[");
        for (int i = 0; i < 11; i++) {
            if (i > 0) {
                raw.append(',');
            }
            raw.append("{\"type\":\"ADD_TAG\",\"tag\":\"t").append(i).append("\"}");
        }
        raw.append(']');
        assertThat(codeOf(() -> ActionSpec.parseArray(json(raw.toString()))))
                .isEqualTo(AutomationErrors.TOO_MANY_ACTIONS);
    }

    @Test
    void tagLengthAndWebhookPathAreConstrained() {
        String tooLong = "x".repeat(AutomationSpecs.ActionSpec.MAX_TAG_LENGTH + 1);
        assertThat(codeOf(() -> ActionSpec.parseArray(json("[{\"type\":\"ADD_TAG\",\"tag\":\"" + tooLong + "\"}]"))))
                .isEqualTo(AutomationErrors.SCHEMA_INVALID);
        assertThat(codeOf(() -> ActionSpec.parseArray(json(
                "[{\"type\":\"WEBHOOK\",\"configId\":1,\"path\":\"hooks/x\"}]"))))
                .isEqualTo(AutomationErrors.SCHEMA_INVALID);
        assertThat(codeOf(() -> ActionSpec.parseArray(json(
                "[{\"type\":\"WEBHOOK\",\"configId\":1,\"path\":\"/http://evil\"}]"))))
                .isEqualTo(AutomationErrors.SCHEMA_INVALID);
        assertThat(codeOf(() -> ActionSpec.parseArray(json(
                "[{\"type\":\"WEBHOOK\",\"configId\":1,\"path\":\"/../etc\"}]"))))
                .isEqualTo(AutomationErrors.SCHEMA_INVALID);
    }

    @Test
    void actionShapesAreClosedPerType() {
        assertThat(codeOf(() -> ActionSpec.parseArray(json("[{\"type\":\"ASSIGN_OWNER\"}]"))))
                .isEqualTo(AutomationErrors.SCHEMA_INVALID);
        assertThat(codeOf(() -> ActionSpec.parseArray(json(
                "[{\"type\":\"NOTIFY\",\"recipients\":[{\"kind\":\"USER\"}]}]"))))
                .isEqualTo(AutomationErrors.SCHEMA_INVALID);
        assertThat(codeOf(() -> ActionSpec.parseArray(json(
                "[{\"type\":\"NOTIFY\",\"recipients\":[{\"kind\":\"DOMAIN_OWNER\"}],\"tag\":\"x\"}]"))))
                .isEqualTo(AutomationErrors.SCHEMA_INVALID);
    }

    @Test
    void validActionsAreParsed() {
        List<ActionSpec> actions = ActionSpec.parseArray(json("[{\"type\":\"NOTIFY\",\"recipients\":["
                + "{\"kind\":\"USER\",\"id\":7},{\"kind\":\"ROLE\",\"code\":\"PMO\"},"
                + "{\"kind\":\"DOMAIN_OWNER\"}]},"
                + "{\"type\":\"ASSIGN_OWNER\",\"userId\":9},"
                + "{\"type\":\"ADD_TAG\",\"tag\":\" 大需求 \"},"
                + "{\"type\":\"WEBHOOK\",\"configId\":3,\"path\":\"/hooks/req\"}]"));
        assertThat(actions).hasSize(4);
        assertThat(actions.get(0).recipients()).hasSize(3);
        assertThat(actions.get(2).tag()).isEqualTo("大需求");
        assertThat(actions.get(3).path()).isEqualTo("/hooks/req");
    }
}
