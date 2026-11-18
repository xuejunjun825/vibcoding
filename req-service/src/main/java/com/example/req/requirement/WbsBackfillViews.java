package com.example.req.requirement;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 存量工时评估回填通道的传输契约（r29）：命令 + 报告 DTO，<b>不含任何业务判定</b>。
 *
 * <p>为什么把 HTTP 字段名集中在这里：控制器只做门与参数、服务只做编排、运维脚本与
 * {@code scripts/wbs-backfill-verify.sh} 只按这些键名断言。字段散落在控制器里拼 {@code Map}
 * 时，脚本只能靠猜键名，改一个键就会让门禁假红。record 天然不可变，报告可原样序列化。</p>
 *
 * <p>两只「状态」不要混：{@code Row.status} 是<b>需求</b>自身状态（如 {@code POOLED}），
 * {@code Row.previousStatus} 是回填<b>之前</b>的工时评估六态（{@code NONE|PENDING|APPROVED|
 * REJECTED|SUPERSEDED|STALE}，唯一真相是 {@link WbsEstimateService#statusOf}）。</p>
 */
public final class WbsBackfillViews {

    private WbsBackfillViews() {
    }

    /** 命令：租户编码必填；{@code dryRun} 默认 true（先看计划，再显式 apply）。 */
    public record Command(String tenant, String batchId, boolean dryRun) {
    }

    /**
     * 计划阶段的一行候选（内部结构，不进 HTTP 契约）。
     *
     * <p>{@code status} 是需求状态、{@code previousStatus} 是评估六态——两者都要进
     * {@link Row}，所以扫描阶段一起带出来，避免计划阶段再逐行回查。</p>
     */
    public record Candidate(long requirementId,
                            String code,
                            String type,
                            String status,
                            String previousStatus,
                            BigDecimal estimateDays,
                            Long domainId) {
    }

    /** 汇总计数：{@code planned} 是计划阶段判为「可补」的行数（apply 后应等于 created+failed）。 */
    public record Summary(int scanned, int planned, int created, int skipped, int failed) {
    }

    /**
     * 单行结论。
     *
     * <p>固定输出全部键：全局 Jackson 是 {@code default-property-inclusion: non_null}，而契约
     * （TECH-CHANGE §3.6 样张）写的是 {@code "reason":null}／{@code "instanceId":null}。
     * 运维报告与门禁都按「七键齐全」读，空键被全局配置吞掉会让报告与契约不一致，
     * 故单行与报告都显式 {@code ALWAYS}（与 {@code ReleaseReviewViews.ReviewDetail} 同因）。</p>
     *
     * @param status         需求状态（回填前）
     * @param previousStatus 评估六态（回填前）
     * @param approverId     实际完成审批的人（apply 才有值）
     * @param approverSource DOMAIN_OWNER / PLATFORM_ADMIN / PENDING_TASK
     * @param flags          含 {@link #FLAG_FALLBACK_APPROVER} 即审批人非域负责人
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Row(String code,
                      long requirementId,
                      String type,
                      String status,
                      BigDecimal estimateDays,
                      String result,
                      String reason,
                      Long instanceId,
                      String previousStatus,
                      Long approverId,
                      String approverSource,
                      List<String> flags) {
    }

    /** 报告：脚本只认这里的 {@code mode}/{@code summary}/{@code rows[].result|reason}。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Report(String batchId, String tenant, String mode, String generatedAt,
                         Summary summary, List<Row> rows) {
    }

    // ------------------------------------------------------------------ 常量

    public static final String RESULT_PLANNED = "PLANNED";
    public static final String RESULT_CREATED = "CREATED";
    public static final String RESULT_SKIPPED = "SKIPPED";
    public static final String RESULT_FAILED = "FAILED";

    public static final String MODE_DRY_RUN = "dry-run";
    public static final String MODE_APPLY = "apply";

    public static final String SOURCE_DOMAIN_OWNER = "DOMAIN_OWNER";
    public static final String SOURCE_PLATFORM_ADMIN = "PLATFORM_ADMIN";
    public static final String SOURCE_PENDING_TASK = "PENDING_TASK";

    public static final String FLAG_FALLBACK_APPROVER = "FALLBACK_APPROVER";

    // 跳过/失败原因（脚本按字符串断言，勿改拼写）
    public static final String REASON_TYPE_BUS = "TYPE_BUS";
    public static final String REASON_TERMINAL = "TERMINAL";
    public static final String REASON_NO_ESTIMATE_DAYS = "NO_ESTIMATE_DAYS";
    public static final String REASON_HAS_ACTIVE_ESTIMATE = "HAS_ACTIVE_ESTIMATE";
    public static final String REASON_HAS_APPROVED_ESTIMATE = "HAS_APPROVED_ESTIMATE";
    public static final String REASON_INVALID_ESTIMATE_DAYS = "INVALID_ESTIMATE_DAYS";
    public static final String REASON_NO_APPROVER = "NO_APPROVER";
    public static final String REASON_APPROVED_NOT_DERIVED = "APPROVED_NOT_DERIVED";
    public static final String REASON_CONCURRENT_ACTIVE = "CONCURRENT_ACTIVE";
    public static final String REASON_NO_WORKFLOW_DEFINITION = "NO_WORKFLOW_DEFINITION";
    public static final String REASON_ROW_FAILED = "ROW_FAILED";
}
