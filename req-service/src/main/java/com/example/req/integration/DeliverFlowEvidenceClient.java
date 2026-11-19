package com.example.req.integration;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * r30 / AC-8：需求侧读取交付侧「发版链路前三段事实」的唯一入口。
 *
 * <p>为什么解析成 {@code Map} 而不是 deliver 的 {@code QualityGateViews}/{@code ReportViews}：
 * 那两个 record 属于 deliver 的内部视图，字段一改就会让 req 编译期崩；而这里要的只是
 * 「有什么就摆什么」的事实。用 Map 承接后，缺字段/多字段都只是渲染上的差别，聚合层
 * （{@code FlowChainService}）负责把它们折成 {@code FlowChainViews} 的固定契约。</p>
 *
 * <p>失败语义只有一个：{@link EvidenceUnavailable}。调用点把它翻成三段 MISSING，
 * 版本详情继续 200——「读不到」绝不允许伪装成「通过」，也绝不允许把首屏打成 500。</p>
 */
@Component
public class DeliverFlowEvidenceClient {

    /** 首屏预算：四段链路是详情页的一部分，不能比版本自身的查询更慢。 */
    public static final Duration EVIDENCE_BUDGET = Duration.ofMillis(800);

    private static final String PATH = "/internal/releases/{releaseId}/flow-evidence?requirementIds={ids}";
    private static final Logger log = LoggerFactory.getLogger(DeliverFlowEvidenceClient.class);

    private final RestClient client;

    public DeliverFlowEvidenceClient(InternalTokenClient internalTokenClient) {
        this.client = internalTokenClient.deliver(EVIDENCE_BUDGET);
    }

    /** 三段事实；某段为 {@code null} 表示 deliver 没给该键（由聚合层标 MISSING）。 */
    public record Evidence(Map<String, Object> testPlan,
                           Map<String, Object> signoff,
                           Map<String, Object> qualityGate) {

        /** deliver 不可用时的占位：三段全空，调用点据此统一标 MISSING。 */
        public static Evidence absent() {
            return new Evidence(null, null, null);
        }
    }

    /** 交付侧不可用/超时/响应不可解析。 */
    public static class EvidenceUnavailable extends RuntimeException {

        public EvidenceUnavailable(String message) {
            super(message);
        }

        public EvidenceUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 一次往返取三段（计划/签署/门）。
     *
     * @throws EvidenceUnavailable 服务不可用、超时或响应不是对象
     */
    public Evidence fetch(long releaseId, List<Long> requirementIds) {
        String ids = join(requirementIds);
        Map<String, Object> payload;
        try {
            payload = client.get()
                    .uri(PATH, releaseId, ids)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
        } catch (RuntimeException ex) {
            throw new EvidenceUnavailable("deliver 链路证据不可用：" + rootMessage(ex), ex);
        }
        if (payload == null) {
            throw new EvidenceUnavailable("deliver 链路证据返回空响应");
        }
        return new Evidence(section(payload, "testPlan"), section(payload, "signoff"),
                section(payload, "qualityGate"));
    }

    private static Map<String, Object> section(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (k != null) {
                    out.put(String.valueOf(k), v);
                }
            });
            return out;
        }
        if (value != null) {
            log.warn("deliver 链路证据字段 {} 不是对象，按缺段处理", key);
        }
        return null;
    }

    private static String join(List<Long> requirementIds) {
        if (requirementIds == null || requirementIds.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Long id : requirementIds) {
            if (id == null) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(',');
            }
            out.append(id);
        }
        return out.toString();
    }

    private static String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String text = cursor.getMessage();
        return cursor.getClass().getSimpleName() + (text == null || text.isBlank() ? "" : "：" + text);
    }
}
