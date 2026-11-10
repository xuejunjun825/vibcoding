package com.example.deliver.reports;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.deliver.analytics.AnalyticsDatasets;
import com.example.deliver.analytics.AnalyticsErrors;
import com.example.deliver.analytics.AnalyticsViews;
import com.example.platform.common.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The codec is the only place the editor's shape ({@code columns}, {@code value}, {@code IN}) and
 * the engine's ({@code cols}, {@code values}, {@code in}) meet, so both directions are asserted
 * here: a stored definition must read back identical (AC-7) and an operator must never reach SQL
 * unmapped (AC-7 / AC-12).
 */
class ReportDefinitionCodecTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ReportDefinitionCodec codec;

    @BeforeEach
    void setUp() {
        codec = new ReportDefinitionCodec(mapper);
    }

    @Test
    @DisplayName("POST 的 definition 存进去再读出来，逐字段相等（AC-7 深比较的单元级保证）")
    void roundTripKeepsTheEditorShape() {
        ReportViews.ReportDefinitionDto posted = new ReportViews.ReportDefinitionDto("requirement",
                List.of("status"), List.of("priority"), List.of("req.total"),
                List.of(new ReportViews.ReportFilterDto("priority", "=", "P0")));

        String stored = codec.store(posted);

        assertEquals(posted, codec.read(stored));
        assertEquals(posted, codec.parse(json(posted)));
        assertEquals(2, codec.dimCount(posted));
        assertEquals(1, codec.measureCount(posted));
    }

    @Test
    @DisplayName("op 符号与 value 标量只在 engine() 转换：= → eq，标量 → 单元素 values")
    void engineMapsSymbolsAndScalars() {
        ReportViews.ReportDefinitionDto dto = new ReportViews.ReportDefinitionDto("requirement",
                List.of("status"), List.of(), List.of("req.total"),
                List.of(new ReportViews.ReportFilterDto("priority", "=", "P0"),
                        new ReportViews.ReportFilterDto("status", "IN", List.of("A", "B"))));

        AnalyticsViews.ReportDefinition engine = codec.engine("requirement", dto);

        assertEquals(List.of("status"), engine.rows());
        assertEquals(List.of("req.total"), engine.measures());
        assertEquals("eq", engine.filters().get(0).op());
        assertEquals(List.of("P0"), engine.filters().get(0).values());
        assertEquals("in", engine.filters().get(1).op());
        assertEquals(List.of("A", "B"), engine.filters().get(1).values());
    }

    @Test
    @DisplayName("不认识的运算符 → 400 REPORT_UNKNOWN_FIELD，detail.op 带原值")
    void unknownOperatorIsRejectedBeforeSql() {
        ReportViews.ReportDefinitionDto dto = new ReportViews.ReportDefinitionDto("requirement",
                List.of("status"), List.of(), List.of("req.total"),
                List.of(new ReportViews.ReportFilterDto("priority", "@@", "P0")));

        ApiException error = assertThrows(ApiException.class,
                () -> codec.engine("requirement", dto));

        assertEquals(400, error.status());
        assertEquals(AnalyticsErrors.UNKNOWN_FIELD, error.code());
        assertEquals("@@", error.detail().get("op"));
    }

    @Test
    @DisplayName("字段不在白名单 → 400；字段属于别的数据集 → 400 REPORT_DATASET_MISMATCH")
    void whitelistDistinguishesTypoFromWrongDataset() {
        ApiException typo = assertThrows(ApiException.class, () -> AnalyticsDatasets.check(
                AnalyticsDatasets.REQUIREMENT, codec.engine("requirement", definition("nope"))));
        assertEquals(AnalyticsErrors.UNKNOWN_FIELD, typo.code());
        assertEquals("nope", typo.detail().get("field"));

        ApiException mixed = assertThrows(ApiException.class, () -> AnalyticsDatasets.check(
                AnalyticsDatasets.REQUIREMENT, codec.engine("requirement", definition("release"))));
        assertEquals(AnalyticsErrors.DATASET_MISMATCH, mixed.code());
        assertEquals("release", mixed.detail().get("field"));
    }

    @Test
    @DisplayName("超过 3 个度量 → 400 REPORT_MEASURE_LIMIT，不会被悄悄截断")
    void measureLimitIsEnforced() {
        ReportViews.ReportDefinitionDto dto = new ReportViews.ReportDefinitionDto("requirement",
                List.of("status"), List.of(),
                List.of("req.total", "cycle.avg", "cycle.p90", "req.avgDwell"), List.of());

        ApiException error = assertThrows(ApiException.class, () -> AnalyticsDatasets.check(
                AnalyticsDatasets.REQUIREMENT, codec.engine("requirement", dto)));

        assertEquals(400, error.status());
        assertEquals(AnalyticsErrors.MEASURE_LIMIT, error.code());
    }

    private static ReportViews.ReportDefinitionDto definition(String dimension) {
        return new ReportViews.ReportDefinitionDto("requirement", List.of(dimension), List.of(),
                List.of("req.total"), List.of());
    }

    private com.fasterxml.jackson.databind.JsonNode json(ReportViews.ReportDefinitionDto dto) {
        return mapper.valueToTree(dto);
    }
}
