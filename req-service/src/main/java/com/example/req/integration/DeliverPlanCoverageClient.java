package com.example.req.integration;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 需求侧查询交付侧「测试计划覆盖」的唯一入口（R1 / AC-1..AC-4）。
 *
 * <p>批准状态是计划数据的所有者的判断，req 不直连 deliver 表：只调只读内部端点
 * {@code GET /internal/test-plans/pending-coverage}，由 deliver 用
 * {@code PlanLifecycle.pendingStatuses()} 单一状态口径过滤。</p>
 *
 * <p>与 {@link DeliverQualityGateClient} 同构：走 {@link InternalTokenClient} 这一条内部调用出口
 * （内部 token + 租户头 + 预算），解析一律用 {@code Map}，不绑定交付侧内部 record。
 * 语义上刻意"取不到就抛"——任何不可用都抛 {@link PlanCheckUnavailable}，
 * 由上层转 503 fail-closed，绝不把"没查到"当成"没有未批准计划"（那是放行）。</p>
 */
@Component
public class DeliverPlanCoverageClient {

    /** 覆盖查询预算：发布单写库前的同步校验，超过 300ms 即视为不可用（不重试，避免放大延迟）。 */
    public static final Duration PLAN_COVERAGE_BUDGET = Duration.ofMillis(300);

    private final RestClient client;

    public DeliverPlanCoverageClient(InternalTokenClient internalTokenClient) {
        this.client = internalTokenClient.deliver(PLAN_COVERAGE_BUDGET);
    }

    /** 覆盖计划不可查（连接失败/超时/非 2xx/载荷不可解析）：上层必须转 503，禁止降级为空列表。 */
    public static class PlanCheckUnavailable extends RuntimeException {

        public PlanCheckUnavailable(String message) {
            super(message);
        }

        public PlanCheckUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 一条挡住发布单的待批准计划；{@code requirementIds} 是该计划覆盖到的、且本次发布单引用的需求。 */
    public record PlanRef(long id, String name, String status, List<Long> requirementIds) {
    }

    /**
     * 该版本下状态为待批（DRAFT/PENDING_REVIEW）且覆盖到给定需求的计划。
     *
     * <p>空需求集直接返回空列表且不发请求：没有需求就没有覆盖，
     * 与"没有任何覆盖计划"是同一条放行路径（AC-3）。</p>
     *
     * @throws PlanCheckUnavailable 端点不可用/超时/返回体不可解析
     */
    public List<PlanRef> pending(long releaseId, List<Long> requirementIds) {
        if (requirementIds == null || requirementIds.isEmpty()) {
            return List.of();
        }
        String joined = String.join(",", requirementIds.stream().map(String::valueOf).toList());
        Map<String, Object> payload;
        try {
            payload = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/test-plans/pending-coverage")
                            .queryParam("releaseId", releaseId)
                            .queryParam("requirementIds", joined)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
        } catch (RuntimeException ex) {
            throw new PlanCheckUnavailable("测试计划覆盖服务不可用：" + rootMessage(ex), ex);
        }
        if (payload == null) {
            throw new PlanCheckUnavailable("测试计划覆盖服务返回空响应");
        }
        return toPlanRefs(payload.get("items"));
    }

    /** {@code items} 不是列表、或某项缺 {@code planId} ⇒ 载荷不可解析（fail-closed，不猜）。 */
    private static List<PlanRef> toPlanRefs(Object items) {
        if (items == null) {
            return List.of();
        }
        if (!(items instanceof List<?> list)) {
            throw new PlanCheckUnavailable("测试计划覆盖服务返回体结构异常：items 不是数组");
        }
        List<PlanRef> plans = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> map)) {
                throw new PlanCheckUnavailable("测试计划覆盖服务返回体结构异常：items 元素不是对象");
            }
            Long planId = asLong(map.get("planId"));
            if (planId == null) {
                throw new PlanCheckUnavailable("测试计划覆盖服务返回体结构异常：缺少 planId");
            }
            plans.add(new PlanRef(planId, asString(map.get("planName")), asString(map.get("status")),
                    asLongList(map.get("requirementIds"))));
        }
        plans.sort(Comparator.comparingLong(PlanRef::id));
        return plans;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
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
        ids.sort(Long::compareTo);
        return ids;
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
