package com.example.req.release;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 质量门段的历史兼容（PRD AC-7 / TECHNICAL §7.1）。
 *
 * <p>风险点是 record 加了组件之后旧快照读不出来：r30/r32 冻结的 payload 里没有
 * {@code skippedRules}，也没有五个计数，而它们的 {@code failedRules} 里混着 {@code SKIPPED} 行。
 * 所以这里用<b>旧形状 JSON</b> 真的反序列化一次：不抛异常、列表归一为空、计数留 {@code null}
 * ——「没有这个字段」绝不能伪装成「跳过 0 条」。</p>
 *
 * <p>反序列化用的是 {@code new ObjectMapper()}（与 {@code FlowSnapshotService} 注入的是同一类），
 * 因此这条断言覆盖的正是回放走的那条路径，而不是手工构造对象。</p>
 */
class FlowChainViewsQualityGateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** r30/r32 提交时的段形状：无 skippedRules、无计数，failedRules 内混着一条 SKIPPED。 */
    private static final String LEGACY = """
            {"status":"PASS","reason":null,"result":"PASS","snapshotId":9001,"gateId":7001,
             "reportId":501,"computedAt":"2026-11-19T02:00:00Z",
             "failedRules":[{"code":"QG-05","status":"SKIPPED","required":false,
                             "actual":"本轮未启用","threshold":"外部质量源","fixHint":null}],
             "waivers":[]}
            """;

    /** 新口径：failedRules 只装 FAIL，SKIPPED 单列，计数齐备。 */
    private static final String CURRENT = """
            {"status":"PASS","reason":null,"result":"PASS","snapshotId":9001,"gateId":7001,
             "reportId":501,"computedAt":"2026-11-19T02:00:00Z",
             "failedRules":[],
             "skippedRules":[{"code":"QG-05","status":"SKIPPED","required":false,
                              "actual":"本轮未启用","threshold":"外部质量源","fixHint":null}],
             "waivers":[],
             "total":5,"passedCount":4,"waivedCount":0,"failedCount":0,"skippedCount":1}
            """;

    @Test
    @DisplayName("旧形状 JSON：反序列化不抛异常，skippedRules 为空、五个计数均为 null")
    void legacy_quality_gate_json_deserializes() {
        FlowChainViews.QualityGateSegment segment =
                MAPPER.readValue(LEGACY, FlowChainViews.QualityGateSegment.class);

        assertEquals("PASS", segment.status());
        assertEquals("PASS", segment.result());
        assertEquals(9001L, segment.snapshotId());
        assertEquals(1, segment.failedRules().size(), "旧快照的 SKIPPED 行仍在 failedRules 里（原样回放）");
        assertEquals("SKIPPED", segment.failedRules().get(0).status());
        assertTrue(segment.skippedRules().isEmpty());
        assertTrue(segment.waivers().isEmpty());
        assertNull(segment.total(), "缺字段必须是 null，不能伪装成 0");
        assertNull(segment.passedCount());
        assertNull(segment.waivedCount());
        assertNull(segment.failedCount());
        assertNull(segment.skippedCount());
    }

    @Test
    @DisplayName("新形状 JSON：skippedRules 与计数如实读入（透传不重算）")
    void current_quality_gate_json_deserializes() {
        FlowChainViews.QualityGateSegment segment =
                MAPPER.readValue(CURRENT, FlowChainViews.QualityGateSegment.class);

        assertTrue(segment.failedRules().isEmpty());
        assertEquals(1, segment.skippedRules().size());
        assertEquals("QG-05", segment.skippedRules().get(0).code());
        assertEquals(5, segment.total());
        assertEquals(4, segment.passedCount());
        assertEquals(0, segment.waivedCount());
        assertEquals(0, segment.failedCount());
        assertEquals(1, segment.skippedCount());
    }

    @Test
    @DisplayName("缺失段：没有规则也没有计数，四个列表归一为空")
    void missing_segment_has_no_counts() {
        FlowChainViews.QualityGateSegment segment =
                FlowChainViews.QualityGateSegment.missing("deliver 未返回质量门段");

        assertEquals("MISSING", segment.status());
        assertTrue(segment.failedRules().isEmpty());
        assertTrue(segment.skippedRules().isEmpty());
        assertTrue(segment.waivers().isEmpty());
        assertNull(segment.total());
        assertNull(segment.skippedCount());
    }

    // ------------------------------------------------------------------ 透传（deliver Map → 段）

    /** deliver 返回的那个 Map 的形状（键名与 deliver 段一致）。 */
    private static java.util.Map<String, Object> deliverSegment(java.util.List<String> extraKeys) {
        java.util.Map<String, Object> segment = new java.util.LinkedHashMap<>();
        segment.put("status", "PASS");
        segment.put("reason", null);
        segment.put("result", "PASS");
        segment.put("snapshotId", 9001);
        segment.put("gateId", 7001);
        segment.put("reportId", 501);
        segment.put("computedAt", "2026-11-19T02:00:00Z");
        segment.put("failedRules", java.util.List.of());
        segment.put("waivers", java.util.List.of());
        if (extraKeys.contains("skippedRules")) {
            segment.put("skippedRules", java.util.List.of(java.util.Map.of(
                    "code", "QG-05", "status", "SKIPPED", "required", false,
                    "actual", "本轮未启用", "threshold", "外部质量源", "fixHint", "")));
        }
        if (extraKeys.contains("counts")) {
            segment.put("total", 5);
            segment.put("passedCount", 4);
            segment.put("waivedCount", 0);
            segment.put("failedCount", 0);
            segment.put("skippedCount", 1);
        }
        return segment;
    }

    /** 私有透传方法：req 只搬运 deliver 的事实，不在这里重算任何数字。 */
    private static FlowChainViews.QualityGateSegment passThrough(java.util.Map<String, Object> segment)
            throws Exception {
        java.lang.reflect.Method method =
                FlowChainService.class.getDeclaredMethod("qualityGate", java.util.Map.class);
        method.setAccessible(true);
        return (FlowChainViews.QualityGateSegment) method.invoke(null, segment);
    }

    @Test
    @DisplayName("透传：deliver 的 skippedRules 与五个计数原样进入段（不重算、不补 0）")
    void service_passes_skipped_rules_and_counts_through() throws Exception {
        FlowChainViews.QualityGateSegment segment = passThrough(deliverSegment(java.util.List.of("skippedRules", "counts")));

        assertEquals(1, segment.failedRules().size() + segment.skippedRules().size());
        assertEquals("QG-05", segment.skippedRules().get(0).code());
        assertEquals("SKIPPED", segment.skippedRules().get(0).status());
        assertEquals(5, segment.total());
        assertEquals(4, segment.passedCount());
        assertEquals(0, segment.failedCount());
        assertEquals(1, segment.skippedCount());
    }

    @Test
    @DisplayName("透传旧段：deliver 没给 skippedRules/计数时列表为空、计数为 null")
    void service_leaves_counts_null_for_legacy_segment() throws Exception {
        FlowChainViews.QualityGateSegment segment = passThrough(deliverSegment(java.util.List.of()));

        assertTrue(segment.skippedRules().isEmpty());
        assertNull(segment.total());
        assertNull(segment.passedCount());
        assertNull(segment.waivedCount());
        assertNull(segment.failedCount());
        assertNull(segment.skippedCount());
    }
}
