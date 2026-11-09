package com.example.req.integration.sonar;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 代码质量（CCode）只读视图（AC-13 / TECHNICAL §2.7）。
 *
 * <p>三条不可越界的规则，用类型形状来保证：</p>
 * <ol>
 *   <li><b>空态不补 0</b>：没有快照时 {@code card == null} + {@code emptyReason}，
 *       而不是一张五项全 0、覆盖率 0% 的卡片。0 是「查出来是 0」，空是「没查过」，
 *       两者在质量页上必须可区分。</li>
 *   <li><b>五项原样</b>：{@code metrics} 直接来自 {@code quality_gate_snapshot.metrics_json}，
 *       缺失的键<b>不出现</b>（前端渲染 —），本类不做任何补值或换算。</li>
 *   <li><b>版本按项目去重</b>：同一 {@code project_key} 在一个版本里只出现一次，
 *       取最新快照，并带上「来自哪个需求」。</li>
 * </ol>
 */
public final class CodeQualityViews {

    /** 有 Sonar 集成但没有快照。 */
    public static final String EMPTY_NO_SNAPSHOT = "NO_SNAPSHOT";
    /** Sonar 集成未启用/未配置：此时连「没同步过」都不该断言。 */
    public static final String EMPTY_INTEGRATION_DISABLED = "INTEGRATION_DISABLED";

    private CodeQualityViews() {
    }

    /**
     * 一个项目的质量卡（= 一条 {@code quality_gate_snapshot} 的投影）。
     *
     * @param gateResult 门禁结论原文（OK/ERROR），不是本系统重算的结论
     * @param metrics    五项度量中<b>实际存在</b>的键值（字符串原样）
     * @param thresholds 与 {@code metrics} 同源的阈值（来自 SonarThresholds，保证展示与判定一致）
     * @param snapshotAt 快照时间（{@code computed_at}），不是本次请求时间
     */
    public record QualityCard(String projectKey,
                              String gateResult,
                              Map<String, String> metrics,
                              Map<String, String> thresholds,
                              Long snapshotId,
                              LocalDateTime snapshotAt) {

        public QualityCard {
            metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
            thresholds = thresholds == null ? Map.of() : Map.copyOf(thresholds);
        }
    }

    /** 需求详情「代码质量」卡：有卡就没有 emptyReason，反之亦然。 */
    public record RequirementCard(QualityCard card, String emptyReason) {
    }

    /** 版本页里的一行：项目 + 数据来自哪个需求（tooltip「来自需求 XXX」）。 */
    public record ProjectCard(String projectKey,
                              long requirementId,
                              String requirementCode,
                              QualityCard card) {
    }

    /** 版本质量：按项目去重后的行 + 整页空态原因（有数据时为 null）。 */
    public record ReleaseCard(List<ProjectCard> projects, String emptyReason) {

        public ReleaseCard {
            projects = projects == null ? List.of() : List.copyOf(projects);
        }
    }
}
