package com.example.req.integration;

import com.example.platform.contract.quality.GateSnapshot;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 需求侧访问交付侧「发布质量门」的唯一入口（{@code QualityGatePort} 的 HTTP 实现）。
 *
 * <p>与 {@link DeliverGateClient} 同构：同一套内部令牌与预算，不另建 HTTP 抽象。
 * 语义上刻意"取不到就抛"——{@link #evaluateDetailed} 失败抛 {@link QualityGateUnavailable}，
 * 由 {@code AdmissionEvaluator} 转成第 6 项 FAIL（{@code GATE_UNAVAILABLE}），
 * 绝不允许把"没拿到结论"当成通过（AC-5.5 fail-closed）。</p>
 *
 * <p>解析一律走 {@code Map}：交付侧内部端点是"快照的 JSON 投影"，
 * 只用本地副本反序列化会让两边的字段演进互相绑死；这里只取准入判定真正需要的字段。</p>
 */
@Component
public class DeliverQualityGateClient {

    /** 质量门求值预算：与准入整体 500ms 同一预算，超时即不可用（不重试，避免放大延迟）。 */
    public static final Duration QUALITY_GATE_BUDGET = Duration.ofMillis(500);

    private final RestClient client;

    public DeliverQualityGateClient(InternalTokenClient internalTokenClient) {
        this.client = internalTokenClient.deliver(QUALITY_GATE_BUDGET);
    }

    /** 质量门不可用/超时/返回体不可解析。 */
    public static class QualityGateUnavailable extends RuntimeException {

        public QualityGateUnavailable(String message, Throwable cause) {
            super(message, cause);
        }

        public QualityGateUnavailable(String message) {
            super(message);
        }
    }

    /**
     * 一次求值的结果。{@code gateId}/{@code reportId} 是交付侧可选出参（AC-5.6 要落
     * {@code deploy_order.quality_gate_id/report_id}）；{@code GateSnapshot} 本身只有 {@code reportVersionId}，
     * 因此这里用宽容的可选字段承载，缺失时为 {@code null}，不影响判定。
     */
    public record Evaluation(GateSnapshot snapshot, Long gateId, Long reportId) {

        public static Evaluation of(GateSnapshot snapshot) {
            return new Evaluation(snapshot, null, null);
        }
    }

    /**
     * 求值并固化快照（同 {@code deployOrderId + result_digest} 幂等复用，由交付侧保证）。
     *
     * @throws QualityGateUnavailable 超时/异常/返回体不可解析
     */
    public Evaluation evaluateDetailed(long releaseId, long deployOrderId, List<Long> requirementIds) {
        Map<String, Object> payload;
        try {
            payload = client.post()
                    .uri("/internal/quality-gates/{releaseId}/evaluate", releaseId)
                    .body(Map.of(
                            "deployOrderId", deployOrderId,
                            "requirementIds", requirementIds == null ? List.of() : requirementIds))
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
        } catch (RuntimeException ex) {
            throw new QualityGateUnavailable("质量门服务不可用：" + rootMessage(ex), ex);
        }
        Map<String, Object> root = unwrap(payload);
        GateSnapshot snapshot = toSnapshot(root, releaseId, deployOrderId);
        // gateId/reportId 是可选出参：既接受与快照同层，也接受包在 {"snapshot":{…}} 外层，缺则留空。
        return new Evaluation(snapshot,
                firstNonNull(asLong(root.get("gateId")), asLong(payload.get("gateId"))),
                firstNonNull(asLong(root.get("reportId")), asLong(payload.get("reportId"))));
    }

    /** 只要快照（蓝图契约方法）；失败同样抛 {@link QualityGateUnavailable}。 */
    public GateSnapshot evaluate(long releaseId, long deployOrderId, List<Long> requirementIds) {
        return evaluateDetailed(releaseId, deployOrderId, requirementIds).snapshot();
    }

    /** 该版本最近一次固化的快照；交付侧 404（还没求值过）或不可用时返回 {@code null}。 */
    public GateSnapshot latest(long releaseId) {
        try {
            Map<String, Object> payload = client.get()
                    .uri("/internal/quality-gates/{releaseId}/latest", releaseId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            return toSnapshot(unwrap(payload), releaseId, 0L);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 版本质量摘要（逃逸/P0P1/回滚/FPY）；不可用时返回 {@code null}，调用方按"暂无"渲染。 */
    public GateSnapshot.QualitySummary summary(long releaseId) {
        try {
            Map<String, Object> payload = client.get()
                    .uri("/internal/quality/summary/{releaseId}", releaseId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            Map<String, Object> root = unwrap(payload);
            if (root == null) {
                return null;
            }
            return new GateSnapshot.QualitySummary(
                    asLongOr(root.get("releaseId"), releaseId),
                    asLongOr(root.get("escapeCount"), 0L),
                    asLongOr(root.get("postReleaseDefects"), 0L),
                    asLongOr(root.get("openP0P1"), 0L),
                    asLongOr(root.get("rollbackCount"), 0L),
                    asLongOr(root.get("executedOrders"), 0L),
                    asDecimal(root.get("fpy")),
                    asLongList(root.get("gateSnapshotIds")),
                    asLongList(root.get("reportVersionIds")));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 解析

    /** 交付侧可能直出快照，也可能包一层 {@code {"snapshot":{...}}}；两种都认。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> unwrap(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Object nested = payload.get("snapshot");
        if (nested instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return payload;
    }

    private static GateSnapshot toSnapshot(Map<String, Object> root, long releaseId, long deployOrderId) {
        if (root == null) {
            throw new QualityGateUnavailable("质量门返回体为空");
        }
        Long snapshotId = asLong(root.get("snapshotId"));
        Object result = root.get("result");
        if (snapshotId == null || result == null) {
            throw new QualityGateUnavailable("质量门返回体缺少 snapshotId/result");
        }
        return new GateSnapshot(
                snapshotId,
                asLongOr(root.get("releaseId"), releaseId),
                asLongOr(root.get("deployOrderId"), deployOrderId),
                asLong(root.get("reportVersionId")),
                String.valueOf(result),
                asString(root.get("hash")),
                asInstant(root.get("computedAt")),
                toRules(root.get("rules")),
                asLongList(root.get("openP0P1")));
    }

    private static List<GateSnapshot.GateRuleResult> toRules(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<GateSnapshot.GateRuleResult> rules = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> map)) {
                continue;
            }
            Object code = map.get("code");
            if (code == null) {
                continue;
            }
            rules.add(new GateSnapshot.GateRuleResult(
                    String.valueOf(code),
                    asString(map.get("status")),
                    Boolean.TRUE.equals(map.get("required")),
                    asString(map.get("actual")),
                    asString(map.get("threshold")),
                    asString(map.get("fixHint"))));
        }
        return rules;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long firstNonNull(Long first, Long second) {
        return first != null ? first : second;
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private static long asLongOr(Object value, long fallback) {
        Long parsed = asLong(value);
        return parsed == null ? fallback : parsed;
    }

    private static BigDecimal asDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private static List<Long> asLongList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(list.size());
        for (Object element : list) {
            Long id = asLong(element);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** 交付侧给 ISO-8601 字符串；解析不了就留 {@code null}（时间只是展示用，不参与判定）。 */
    private static Instant asInstant(Object value) {
        String text = asString(value);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text.trim());
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getSimpleName() : message;
    }
}
