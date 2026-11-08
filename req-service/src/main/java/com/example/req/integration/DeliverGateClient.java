package com.example.req.integration;

import com.example.platform.contract.GateDecision;
import com.example.platform.contract.GatePort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 需求侧访问交付侧门禁/缺陷的唯一入口（{@link GatePort} 的 HTTP 实现）。
 *
 * <p>两种失败语义刻意分开：
 * <ul>
 *   <li>{@link #evaluate(long)} 是需求流转（RD→测试通过）要用的门禁判定，失败按<b>不通过</b>返回，
 *       让需求停在原地（而不是 500 让用户再试一次）；</li>
 *   <li>{@link #evaluateAll}/{@link #openHighDefects} 是发布准入取数，失败抛
 *       {@link DeliverUnavailable}，由 {@code AdmissionPolicy} 判 FAIL 并给出"服务不可用"的修复指引。</li>
 * </ul>
 * 两边都不允许"取不到就放行"。</p>
 */
@Component
public class DeliverGateClient implements GatePort {

    /** 批量门禁预算 250ms（准入整体 500ms 的一部分，留出缺陷计数与本地判定的余量）。 */
    public static final Duration GATE_BUDGET = Duration.ofMillis(250);
    /** 缺陷计数预算 200ms。 */
    public static final Duration DEFECT_BUDGET = Duration.ofMillis(200);
    /** 准入整体预算：两条跨服务往返 + 本地 SQL 判定必须在此之内（AC-12）。 */
    public static final Duration ADMISSION_BUDGET = Duration.ofMillis(500);

    private final RestClient gateClient;
    private final RestClient defectClient;

    public DeliverGateClient(InternalTokenClient internalTokenClient) {
        this.gateClient = internalTokenClient.deliver(GATE_BUDGET);
        this.defectClient = internalTokenClient.deliver(DEFECT_BUDGET);
    }

    /** 交付侧不可用/超时/返回结构不可解析。 */
    public static class DeliverUnavailable extends RuntimeException {

        public DeliverUnavailable(String message, Throwable cause) {
            super(message, cause);
        }

        public DeliverUnavailable(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ 需求流转用

    @Override
    public GateDecision evaluate(long requirementId) {
        try {
            Map<String, Object> payload = gateClient.get()
                    .uri("/internal/gates/{requirementId}", requirementId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            GateDecision decision = toDecision(payload);
            if (decision == null) {
                return GateDecision.block("STRICT", "门禁服务返回了无法解析的结果", List.of());
            }
            return decision;
        } catch (RuntimeException ex) {
            return GateDecision.block("STRICT", "门禁服务不可用：" + rootMessage(ex), List.of());
        }
    }

    // ------------------------------------------------------------------ 发布准入用

    /**
     * 批量门禁：一次 HTTP 拿齐所有需求的门禁结论（准入 500ms 预算里只允许一次往返）。
     *
     * @throws DeliverUnavailable 服务不可用、超时、或返回体不是"需求→结论"的映射
     */
    public Map<Long, GateDecision> evaluateAll(Collection<Long> requirementIds) {
        if (requirementIds == null || requirementIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = new ArrayList<>(requirementIds);
        Map<Long, GateDecision> decisions = new LinkedHashMap<>();
        try {
            Map<String, Object> payload = gateClient.post()
                    .uri("/internal/gates/batch")
                    .body(Map.of("requirementIds", ids))
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            decisions = parseBatch(payload);
        } catch (RuntimeException ex) {
            throw new DeliverUnavailable("批量门禁不可用：" + rootMessage(ex), ex);
        }
        if (!decisions.isEmpty()) {
            return decisions;
        }
        // 批量接口返回空结果（可能只对"有计划"的需求给结论）：逐条兜底，仍取不到就按不可用处理，
        // 而不是把"空的结论集"当成"全部通过"。
        for (Long id : ids) {
            Map<String, Object> payload;
            try {
                payload = gateClient.get()
                        .uri("/internal/gates/{requirementId}", id)
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {
                        });
            } catch (RuntimeException ex) {
                throw new DeliverUnavailable("门禁服务不可用（需求 " + id + "）：" + rootMessage(ex), ex);
            }
            GateDecision decision = toDecision(payload);
            if (decision == null) {
                throw new DeliverUnavailable("门禁服务返回了无法解析的结果（需求 " + id + "）");
            }
            decisions.put(id, decision);
        }
        return decisions;
    }

    /**
     * 关联需求未关闭的 P0/P1 数量。
     *
     * @throws DeliverUnavailable 服务不可用/超时
     */
    public Map<Long, Integer> openHighDefects(Collection<Long> requirementIds) {
        if (requirementIds == null || requirementIds.isEmpty()) {
            return Map.of();
        }
        String joined = String.join(",", requirementIds.stream().map(String::valueOf).toList());
        Map<String, Object> payload;
        try {
            payload = defectClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/defects/open-high")
                            .queryParam("requirementIds", joined)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
        } catch (RuntimeException ex) {
            throw new DeliverUnavailable("缺陷服务不可用：" + rootMessage(ex), ex);
        }
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : valueMap(payload).entrySet()) {
            Long id = asLong(entry.getKey());
            Integer count = asInt(entry.getValue());
            if (id != null && count != null) {
                counts.put(id, count);
            }
        }
        return counts;
    }

    // ------------------------------------------------------------------ 解析

    /** batch 允许两种形状：直接 {@code {id: decision}} 或 {@code {decisions:{id: decision}}}。 */
    private Map<Long, GateDecision> parseBatch(Map<String, Object> payload) {
        Map<Long, GateDecision> decisions = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : valueMap(payload).entrySet()) {
            Long id = asLong(entry.getKey());
            if (id == null) {
                continue;
            }
            GateDecision decision = toDecision(entry.getValue());
            if (decision != null) {
                decisions.put(id, decision);
            }
        }
        return decisions;
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> valueMap(Map<String, Object> payload) {
        if (payload == null) {
            return Map.of();
        }
        Object nested = payload.get("decisions");
        if (nested instanceof Map<?, ?> map) {
            return map;
        }
        Object counts = payload.get("counts");
        if (counts instanceof Map<?, ?> map) {
            return map;
        }
        return payload;
    }

    private GateDecision toDecision(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Object passedValue = map.get("passed");
        if (!(passedValue instanceof Boolean passed)) {
            return null;
        }
        String mode = map.get("mode") == null ? "STRICT" : String.valueOf(map.get("mode"));
        String reason = normalizeReason(map.get("reason"), map.get("blockers"));
        return passed ? GateDecision.pass(mode, reason) : GateDecision.block(mode, reason, List.of());
    }

    /**
     * 阻断项在 contract 里的字段形态由交付侧定义，准入只需要"哪几项没过"这段人话，
     * 因此这里把 blockers 归类成摘要文本，不反序列化成具体类型（避免两份 DTO 漂移）。
     */
    private String normalizeReason(Object rawReason, Object rawBlockers) {
        String reason = rawReason == null ? null : String.valueOf(rawReason);
        if (reason != null && !reason.isBlank()) {
            return reason;
        }
        if (!(rawBlockers instanceof Collection<?> blockers) || blockers.isEmpty()) {
            return "门禁未通过";
        }
        List<String> parts = new ArrayList<>();
        for (Object blocker : blockers) {
            if (parts.size() >= 5) {
                break;
            }
            if (blocker instanceof Map<?, ?> map) {
                Object code = map.get("caseCode") != null ? map.get("caseCode") : map.get("code");
                Object message = map.get("message");
                Object result = map.get("result") != null ? map.get("result") : map.get("name");
                if (message != null && !String.valueOf(message).isBlank()) {
                    // 有 message 就带着：PMO 在准入清单上要看到"哪条用例为什么不通过"，而不只是一个码。
                    parts.add(String.valueOf(code) + "（" + message + "）");
                } else {
                    parts.add(String.valueOf(code) + (result == null ? "" : "=" + result));
                }
            }
        }
        return blockers.size() + " 个用例未通过"
                + (parts.isEmpty() ? "" : "：" + String.join("、", parts));
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

    private static Integer asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
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
