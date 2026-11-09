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
     * 四态计数（PRD AC-8.4）：{@code active = total - closed}，{@code passed}/{@code active} 才是通过率；
     * SKIP 永远不进 {@code passed}。{@code active = 0} 时通过率按 0 处理，不除零。
     */
    public record AdmissionCounts(int passed, int failed, int skipped, int closed, int active, int total) {

        public static AdmissionCounts of(List<AdmissionItem> items) {
            List<AdmissionItem> list = items == null ? List.of() : items;
            int passed = 0;
            int failed = 0;
            int skipped = 0;
            int closed = 0;
            for (AdmissionItem item : list) {
                switch (item.status() == null ? AdmissionItem.FAIL : item.status()) {
                    case AdmissionItem.PASS -> passed++;
                    case AdmissionItem.SKIP -> skipped++;
                    case AdmissionItem.CLOSED -> closed++;
                    default -> failed++;
                }
            }
            return new AdmissionCounts(passed, failed, skipped, closed, list.size() - closed, list.size());
        }

        /** 通过率（0–1）：分母只算参与判定的项。 */
        public BigDecimal percentage() {
            if (active <= 0) {
                return BigDecimal.ZERO;
            }
            return BigDecimal.valueOf(passed)
                    .divide(BigDecimal.valueOf(active), 4, java.math.RoundingMode.HALF_UP);
        }
    }

    /**
     * 准入快照对外形态；{@code stale=true} 表示需求集合/状态已变，页面必须提示重新校验。
     *
     * <p>{@code legacy=true} = 没有质量门结论的历史单据：M6 之前生成的五项快照，以及从未评估过
     * （{@code admission_result_json} 为 NULL、{@code items} 为空）的旧单。页面只展示历史结论，
     * 不伪造质量门 PASS；{@code gate} 是该次判定的质量门证据（legacy 单据为 {@code null}）。</p>
     *
     * <p>{@code partial=true} = 快照项数不足九项（M6 的 6 项 / M5 的 5 项历史快照），
     * 页面据此提供"重新校验"把历史结论升级为当前口径。</p>
     */
    public record AdmissionSnapshot(List<AdmissionItem> items, LocalDateTime checkedAt, boolean stale,
                                    String signature, boolean legacy, GateEvidence gate,
                                    AdmissionCounts counts, int skipCount, boolean partial) {

        public AdmissionSnapshot {
            items = items == null ? List.of() : items;
            if (counts == null) {
                counts = AdmissionCounts.of(items);
            }
            if (skipCount <= 0) {
                skipCount = counts.skipped();
            }
            partial = partial || items.size() < AdmissionItem.ORDER.size();
        }

        /** 六组件版：历史调用点（服务层在落库后回捞）。 */
        public AdmissionSnapshot(List<AdmissionItem> items, LocalDateTime checkedAt, boolean stale,
                                 String signature, boolean legacy, GateEvidence gate) {
            this(items, checkedAt, stale, signature, legacy, gate, null, 0, false);
        }

        public AdmissionSnapshot(List<AdmissionItem> items, LocalDateTime checkedAt, boolean stale) {
            this(items, checkedAt, stale, null, legacyOf(items), null, null, 0, false);
        }

        /**
         * 六项之外的旧快照：<b>没有第 6 项就是历史结论</b>。
         *
         * <p>空集（NULL 快照、从未评估的旧单）同样成立——"还没评估过"与"评估时还没有质量门"
         * 对读者是同一件事：没有质量门证据，只能按历史单据展示，不得伪造 PASS（R2 / AC-5）。</p>
         */
        public static boolean legacyOf(List<AdmissionItem> items) {
            if (items == null || items.isEmpty()) {
                return true;
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
     * 提交参数（TECHNICAL §5.7 / §7.5）。
     *
     * <p>{@code confirmNoGo=true} 表示 PMO 已知悉最新 NO-GO 评审风险、二次确认后继续提交；
     * 缺省/空体按"未确认"处理，由服务端返回 409 NO_GO_CONFIRM_REQUIRED。</p>
     *
     * <p>{@code acknowledgedSkipCount} 是"跳过项等值确认"：清单里有 {@code skipCount} 个 SKIP 时，
     * 提交必须把同一个数字回传（少传、多传、不传都 400 SKIP_NOT_ACKNOWLEDGED），
     * 防止跳过在用户不知情的情况下被当成通过（PRD AC-8.3）。</p>
     */
    public record SubmitCmd(Boolean confirmNoGo, Integer acknowledgedSkipCount) {
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
     * 准入判定结果（带四态计数与快照指纹，用于判断详情页展示的结论是否已过期）。
     *
     * <p>第 6 项质量门的证据随结果一起落 {@code admission_result_json}，
     * 提交时据此写 {@code deploy_order.quality_gate*} 三列（AC-5.6）。</p>
     */
    public record AdmissionResult(List<AdmissionItem> items, AdmissionCounts counts, LocalDateTime checkedAt,
                                  String signature, GateEvidence gate) {

        public AdmissionResult {
            items = items == null ? List.of() : items;
            if (counts == null) {
                counts = AdmissionCounts.of(items);
            }
        }

        public AdmissionResult(List<AdmissionItem> items, LocalDateTime checkedAt, String signature,
                               GateEvidence gate) {
            this(items, null, checkedAt, signature, gate);
        }

        public AdmissionResult(List<AdmissionItem> items, LocalDateTime checkedAt, String signature) {
            this(items, null, checkedAt, signature, null);
        }

        /**
         * 提交闸门（PRD AC-8.5）：<b>没有 FAIL 即可提交</b>——CLOSED 不阻断，SKIP 不阻断
         * （但要在提交时显式确认跳过数，见 {@link SubmitCmd#acknowledgedSkipCount()}）。
         */
        public boolean allPassed() {
            return items != null && !items.isEmpty()
                    && items.stream().noneMatch(item -> AdmissionItem.FAIL.equals(item.status()));
        }

        public boolean hasBlocking() {
            return items != null && items.stream().anyMatch(item -> AdmissionItem.FAIL.equals(item.status()));
        }

        public int skipCount() {
            return counts == null ? 0 : counts.skipped();
        }

        /** 九项之外的旧快照（6 项 / 5 项）需要"重新校验"才能升级到当前口径。 */
        public boolean partial() {
            return items == null || items.size() < AdmissionItem.ORDER.size();
        }

        public List<String> failedCodes() {
            return AdmissionPolicy.failedCodes(items);
        }
    }
}
