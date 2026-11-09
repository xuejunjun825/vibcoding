package com.example.req.deploy;

import com.example.platform.contract.quality.GateSnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/** 发布单对外视图（与前端 {@code web/src/api/types.ts} 的 DeployOrder* 一一对应）。 */
public final class DeployViews {

    private DeployViews() {
    }

    /** 发布步骤：{@code order} 从 1 开始，前端按它排序（不让渲染顺序决定执行顺序）。 */
    public record Step(String name, Integer order) {
    }

    /** 随单展示的需求行（只读快照，用于让审批人知道这一单要发什么）。 */
    public record RequirementRow(long id, String code, String type, String status, String mrUrl,
                                 BigDecimal estimateDays, BigDecimal actualDays) {
    }

    public record OrderRow(long id, String code, long releaseId, String releaseName, String env,
                           String status, LocalDateTime windowStart, LocalDateTime windowEnd,
                           String artifactVersion, Long ownerId, String ownerName, boolean forcedRelease,
                           LocalDateTime createdAt) {
    }

    public record ForcedRelease(String reason, String by, LocalDateTime at) {
    }

    public record Execution(String result, LocalDateTime startedAt, LocalDateTime endedAt, String note,
                            Long defectId, Integer durationMinutes) {
    }

    /**
     * 质量门证据：判定当时那一份不可变快照的定位信息 + 规则明细。
     *
     * <p>只读副本，不再回查 deliver——回查会让详情页随新数据漂移（AC-7 回放针对的是快照本身）。
     * {@code gateId}/{@code reportId} 由 deliver 内部端点可选出参，缺失时为 {@code null}（不影响判定）。</p>
     */
    public record GateEvidence(Long snapshotId, Long gateId, Long reportId, String result, String hash,
                               Instant computedAt, List<GateSnapshot.GateRuleResult> rules) {

        public static GateEvidence of(GateSnapshot snapshot, Long gateId, Long reportId) {
            if (snapshot == null) {
                return null;
            }
            return new GateEvidence(snapshot.snapshotId(), gateId, reportId, snapshot.result(), snapshot.hash(),
                    snapshot.computedAt(), snapshot.rules());
        }

        /** 与 deliver 侧 {@code GateSnapshot#passed()} 同语义：PASS / PASS_WITH_WAIVER 都算通过。 */
        public boolean passed() {
            return GateSnapshot.PASS.equals(result) || GateSnapshot.PASS_WITH_WAIVER.equals(result);
        }

        public List<GateSnapshot.GateRuleResult> failedRules() {
            if (rules == null || rules.isEmpty()) {
                return List.of();
            }
            return rules.stream().filter(rule -> !rule.passed()).toList();
        }
    }

    /**
     * 准入快照对外形态；{@code stale=true} 表示需求集合/状态已变，页面必须提示重新校验。
     *
     * <p>{@code legacy=true} = M6 之前生成的五项单据（没有第 6 项质量门），页面只展示历史结论，
     * 不伪造质量门 PASS；{@code gate} 是该次判定的质量门证据（legacy 单据为 {@code null}）。</p>
     */
    public record AdmissionSnapshot(List<AdmissionItem> items, LocalDateTime checkedAt, boolean stale,
                                    String signature, boolean legacy, GateEvidence gate) {

        public AdmissionSnapshot(List<AdmissionItem> items, LocalDateTime checkedAt, boolean stale) {
            this(items, checkedAt, stale, null, legacyOf(items), null);
        }

        /** 六项之外的旧快照：缺 QUALITY_GATE 项即视为 M6 之前的历史结论。 */
        public static boolean legacyOf(List<AdmissionItem> items) {
            if (items == null || items.isEmpty()) {
                return false;
            }
            return items.stream().noneMatch(item -> AdmissionItem.QUALITY_GATE.equals(item.code()));
        }
    }

    public record OrderDetail(long id, String code, long releaseId, String releaseName, String env,
                              String status, LocalDateTime windowStart, LocalDateTime windowEnd,
                              String artifactVersion, List<Step> steps, String rollbackPlan, String impact,
                              Long ownerId, String ownerName, boolean forcedRelease,
                              List<String> allowedActions, LocalDateTime createdAt, LocalDateTime updatedAt,
                              List<RequirementRow> requirements, AdmissionSnapshot admission,
                              ForcedRelease forcedReleaseDetail,
                              Execution execution,
                              Long qualityGateSnapshotId, String gateResult) {
    }

    /**
     * 提交参数（TECHNICAL §5.7）。{@code confirmNoGo=true} 表示 PMO 已知悉最新 NO-GO 评审风险、
     * 二次确认后继续提交；缺省/空体按"未确认"处理，由服务端返回 409 NO_GO_CONFIRM_REQUIRED。
     */
    public record SubmitCmd(Boolean confirmNoGo) {
    }

    /** 提交结果：正常路径只回状态（审批实例 id 属实现细节，前端不需要）。 */
    public record SubmitResult(String status) {
    }

    public record FailReason(String reason, long count) {
    }

    /**
     * 发布统计。{@code successRate} 是比率（0–1，四位小数），前端 {@code formatPercent} 两种形态都认；
     * 三个数字都能由单据明细逐单复算：成功率=成功/(成功+失败+回滚)，平均耗时=已完成单据 durationMinutes 均值，
     * 失败原因取执行记录的备注（没填备注时按执行结果归类）。
     */
    public record DeployStats(BigDecimal successRate, BigDecimal avgDurationMinutes, List<FailReason> failReasons,
                              long total, long succeeded, long failed, long rolledBack) {
    }

    /**
     * 准入判定结果（带快照指纹，用于判断详情页展示的结论是否已过期）。
     *
     * <p>第 6 项质量门的证据随结果一起落 {@code admission_result_json}，
     * 提交时据此写 {@code deploy_order.quality_gate*} 三列（AC-5.6）。</p>
     */
    public record AdmissionResult(List<AdmissionItem> items, LocalDateTime checkedAt, String signature,
                                  GateEvidence gate) {

        public AdmissionResult(List<AdmissionItem> items, LocalDateTime checkedAt, String signature) {
            this(items, checkedAt, signature, null);
        }

        public boolean allPassed() {
            return items != null && !items.isEmpty() && items.stream().allMatch(AdmissionItem::passed);
        }

        public List<String> failedCodes() {
            return AdmissionPolicy.failedCodes(items);
        }
    }
}
