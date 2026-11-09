package com.example.req.integration.sonar;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Sonar 集成域对外的视图（TECHNICAL §4.4 / AC-6）。
 *
 * <p>{@link SonarCard#status()} 与 {@link SonarCard#reasonCode()} 是这张卡片的唯一判定：
 * 前端只做展示映射，不再自己拼 "OK"/"ERROR" 字符串。四种状态：</p>
 * <ul>
 *   <li>{@code OK}/{@code ERROR} —— 已拿到快照，结论以 Sonar 质量门为准；</li>
 *   <li>{@code SKIP} —— 这项不参与（未配置 / 已关闭 / 需求未绑定 key），
 *       文案必须具体到哪一种，不能只显示「跳过」；</li>
 *   <li>{@code UNAVAILABLE} —— 该有结论但拿不到（无快照 / 度量缺失 / 拉取失败）。</li>
 * </ul>
 *
 * <p>准入第 ⑧ 项的四态 PASS/FAIL/SKIP/CLOSED 不在本类：它由
 * {@code AdmissionItemPolicy} + {@code IntegrationEvidenceReader} 从本地快照重新判定，
 * 这里只是同一份事实的展示层，绝不反向复制准入语义（否则两处必然漂移）。</p>
 */
public final class SonarViews {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_SKIP = "SKIP";
    public static final String STATUS_UNAVAILABLE = "UNAVAILABLE";

    /** 这台租户根本没有 Sonar 配置行。 */
    public static final String REASON_NOT_CONFIGURED = "NOT_CONFIGURED";
    /** 有配置行但 enabled=0（或 active_key 不为 ENABLED）。 */
    public static final String REASON_DISABLED = "INTEGRATION_DISABLED";
    /** 已配置也绑定了 key，但还没有任何快照。 */
    public static final String REASON_SNAPSHOT_MISSING = "SNAPSHOT_MISSING";
    /** Sonar 只答了部分度量，按 fail-closed 不落快照。 */
    public static final String REASON_METRIC_MISSING = "METRIC_MISSING";
    /** 外呼失败 / 应答不可解析。 */
    public static final String REASON_ADAPTER_UNAVAILABLE = "ADAPTER_UNAVAILABLE";

    /** PRD AC-6.4 要求的三条具体跳过文案，字面量只在这里出现一次。 */
    public static final String SKIP_NOT_CONFIGURED = "跳过：Sonar 未配置";
    public static final String SKIP_DISABLED = "跳过：Sonar 已关闭";
    public static final String SKIP_NO_KEY = "跳过：需求未绑定 project key";

    private SonarViews() {
    }

    /** 单个度量的展示行：值 + 本平台阈值 + 三态（OK/WARN/ERROR）。 */
    public record MetricView(String key,
                             String label,
                             String value,
                             String status,
                             String threshold) {
    }

    /** Sonar 服务端自己给出的未达标条件（ERROR 时至少有一条可展示）。 */
    public record ConditionView(String metric,
                                String label,
                                String actualValue,
                                String errorThreshold,
                                String comparator,
                                String status) {
    }

    /**
     * 适配器的取数结果。{@code degraded=true} 表示这次没拿到可用数据，
     * {@code gateStatus}/{@code metrics} 可能为空，{@code errorCode} 说明原因。
     */
    public record SonarMeasures(String gateStatus,
                                Map<String, String> metrics,
                                List<ConditionView> conditions,
                                boolean degraded,
                                String errorCode,
                                String message) {

        public static SonarMeasures degraded(String errorCode, String message) {
            return new SonarMeasures(null, Map.of(), List.of(), true, errorCode, message);
        }
    }

    /**
     * 需求详情「Sonar 质量门」卡。
     *
     * @param metrics       camelCase 五度量（coverage/codeSmells/duplicatedLinesDensity…），
     *                      下发给前端 {@code SonarMetricKey}；快照里存的仍是 Sonar 原生 metricKey
     * @param thresholds    与 metrics 同键的阈值文案，页面只展示、不自己算
     * @param metricDetails 同五个度量的阈值三态，供抽屉逐行渲染
     * @param snapshotId    quality_gate_snapshot.id；全新失败时可能为空
     * @param fetchedAt     快照的 computed_at（不是本次请求时间）
     * @param stale         快照早于 max(60 分钟, 2×刷新周期)
     */
    public record SonarCard(String projectKey,
                            String status,
                            Map<String, Object> metrics,
                            Map<String, String> thresholds,
                            List<MetricView> metricDetails,
                            List<ConditionView> conditions,
                            Long snapshotId,
                            String hash,
                            LocalDateTime fetchedAt,
                            boolean stale,
                            boolean degraded,
                            String reasonCode,
                            String skipReason,
                            String message) {
    }

    /**
     * 准入第 ⑧ 项的只读摘要（供诊断/日志使用，不参与准入判定）。
     */
    public record SonarGate(String gateStatus,
                            String reasonCode,
                            boolean stale,
                            Long snapshotId) {
    }

    /**
     * 卡片的三个构造入口：SKIP / UNAVAILABLE / 有快照。放在这里而不是散在 service 里，
     * 是为了让「status 与 reasonCode 的合法组合」只有一处定义——service 只决定走哪一支，
     * 具体字段填充与 metricDetails 派生不重复写三遍。
     */
    public static final class SonarCardFactory {

        private SonarCardFactory() {
        }

        /** 不参与（未配置 / 已关闭 / 未绑定 key）：skipReason 必须是三条具体文案之一。 */
        public static SonarCard skip(String projectKey, String reasonCode, String skipReason) {
            return new SonarCard(projectKey, STATUS_SKIP, Map.of(), Map.of(), List.of(), List.of(),
                    null, null, null, false, false, reasonCode, skipReason, skipReason);
        }

        /**
         * 该有结论但拿不到（无快照 / 度量缺失 / 拉取失败）。带 snapshotId 表示"上一次成功
         * 的快照仍然可看"，此时 stale=true，准入仍按失败处理（fail-closed 在读取侧）。
         */
        public static SonarCard unavailable(String projectKey,
                                            String reasonCode,
                                            String message,
                                            Long snapshotId,
                                            String hash,
                                            LocalDateTime fetchedAt,
                                            Map<String, String> metrics,
                                            List<ConditionView> conditions,
                                            boolean stale,
                                            boolean degraded) {
            return new SonarCard(projectKey, STATUS_UNAVAILABLE,
                    SonarThresholds.cardMetrics(metrics),
                    SonarThresholds.cardThresholds(metrics),
                    SonarThresholds.describe(metrics),
                    conditions == null ? List.of() : conditions,
                    snapshotId, hash, fetchedAt, stale, degraded, reasonCode, null, message);
        }

        /** 有快照：status 只能是 OK/ERROR，metricDetails 由五度量按平台阈值派生。 */
        public static SonarCard snapshot(String projectKey,
                                         String status,
                                         Map<String, String> metrics,
                                         List<ConditionView> conditions,
                                         Long snapshotId,
                                         String hash,
                                         LocalDateTime fetchedAt,
                                         boolean stale,
                                         boolean degraded,
                                         String reasonCode,
                                         String skipReason) {
            return new SonarCard(projectKey, status,
                    SonarThresholds.cardMetrics(metrics),
                    SonarThresholds.cardThresholds(metrics),
                    SonarThresholds.describe(metrics),
                    conditions == null ? List.of() : conditions,
                    snapshotId, hash, fetchedAt, stale, degraded, reasonCode, skipReason, null);
        }
    }
}
