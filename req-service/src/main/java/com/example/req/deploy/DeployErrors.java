package com.example.req.deploy;

import com.example.platform.common.error.ApiException;
import com.example.req.integration.DeliverPlanCoverageClient;
import com.example.req.requirement.RequirementErrors;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发布单错误工厂。通用码（VALIDATION_FAILED/REASON_REQUIRED/FORBIDDEN/NOT_FOUND）复用需求域工厂，
 * 只有准入相关新增两个：{@link #ADMISSION_FAILED}（AC-12 的断言对象）与
 * {@link #APPROVAL_NOT_CONFIGURED}（没有已发布的 REQ_DEPLOY 流程时明确报错，而不是静默放行）。
 */
public final class DeployErrors {

    public static final String ADMISSION_FAILED = "ADMISSION_FAILED";
    public static final String APPROVAL_NOT_CONFIGURED = "APPROVAL_NOT_CONFIGURED";
    public static final String BIZ_NOT_FOUND = "BIZ_NOT_FOUND";
    public static final String NO_GO_CONFIRM_REQUIRED = "NO_GO_CONFIRM_REQUIRED";

    /** 跳过项未等值确认（AC-8.3）：400，detail 带 skipCount 与逐条跳过项。 */
    public static final String SKIP_NOT_ACKNOWLEDGED = "SKIP_NOT_ACKNOWLEDGED";

    /**
     * 发布单引用了未批准的测试计划（R1）。与交付侧"生成报告"的同名码刻意不合并：
     * 那边 detail 是 {@code {planId,status,allowedStatuses}}（单个报告源计划），
     * 这边是 {@code {planIds,plans,requirementIds,hint}}（发布单的覆盖计划集合）。
     */
    public static final String PLAN_NOT_APPROVED = "PLAN_NOT_APPROVED";

    /** 无法校验测试计划批准状态（R1 fail-closed）：查不到就不放行。 */
    public static final String PLAN_CHECK_UNAVAILABLE = "PLAN_CHECK_UNAVAILABLE";

    /** 409 的处置提示：给人和给前端的下一步，而不是一句"未通过"。 */
    private static final String PLAN_NOT_APPROVED_HINT = "先提交评审并批准计划，或从发布单移除该需求后重试";

    private DeployErrors() {
    }

    /**
     * 准入未通过。单独成类是为了让 {@code submit} 的 {@code @Transactional(noRollbackFor=…)}
     * 仍提交已落库的准入快照——失败了也要留下证据，而不是随异常一起回滚。
     */
    public static final class AdmissionFailedException extends ApiException {

        private final transient List<AdmissionItem> items;

        public AdmissionFailedException(List<AdmissionItem> items, List<String> failedCodes) {
            super(409, ADMISSION_FAILED,
                    "准入未通过（" + failedCodes.size() + " 项）：" + String.join("、", failedCodes),
                    Map.of("items", items, "failed", failedCodes));
            this.items = items;
        }

        public List<AdmissionItem> items() {
            return items;
        }
    }

    /**
     * 跳过项未等值确认（PRD AC-8.3）：400 + 机器码，{@code detail.skipCount} 是服务端算出的跳过数，
     * 前端据此弹"本次有 N 项被跳过，确认请回传 N"。单独成类是为了让 submit 的
     * {@code noRollbackFor} 仍提交已落库的准入快照（用户看得到是哪几项被跳过）。
     */
    public static final class SkipNotAcknowledgedException extends ApiException {

        public SkipNotAcknowledgedException(int skipCount, Integer acknowledged, List<AdmissionItem> items) {
            super(400, SKIP_NOT_ACKNOWLEDGED,
                    "本次准入有 " + skipCount + " 项被跳过（skip ≠ pass），请确认后以 acknowledgedSkipCount="
                            + skipCount + " 重新提交",
                    detail(skipCount, acknowledged, items));
        }

        private static Map<String, Object> detail(int skipCount, Integer acknowledged, List<AdmissionItem> items) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("skipCount", skipCount);
            detail.put("acknowledgedSkipCount", acknowledged == null ? 0 : acknowledged);
            detail.put("skipped", items == null ? List.of() : items.stream()
                    .filter(item -> AdmissionItem.SKIP.equals(item.status()))
                    .map(AdmissionPolicy::toMap)
                    .toList());
            return detail;
        }
    }

    public static SkipNotAcknowledgedException skipNotAcknowledged(int skipCount, Integer acknowledged,
                                                                   List<AdmissionItem> items) {
        return new SkipNotAcknowledgedException(skipCount, acknowledged, items);
    }

    /** 没有可用的需求发布审批流：单据停在 DRAFT（不会静默变 APPROVED）。 */
    public static final class ApprovalNotConfiguredException extends ApiException {

        public ApprovalNotConfiguredException(String detail) {
            super(400, APPROVAL_NOT_CONFIGURED, detail,
                    Map.of("bizType", DeployOrderService.APPROVAL_BIZ_TYPE));
        }
    }

    public static AdmissionFailedException admissionFailed(List<AdmissionItem> items) {
        return new AdmissionFailedException(items, AdmissionPolicy.failedCodes(items));
    }

    /**
     * 最新发布评审为 NO-GO 且未二次确认（AC-10.5）：409 + 机器码，前端据此弹"知悉风险并继续"。
     * 确认后仍要过第 6 项质量门，NO-GO 不是放行开关，只是把决策责任显式落到 PMO 身上。
     */
    public static ApiException noGoConfirmRequired(long reviewId) {
        return new ApiException(409, NO_GO_CONFIRM_REQUIRED,
                "最新发布评审为 NO-GO，需 PMO 二次确认后才能提交",
                Map.of("reviewId", reviewId));
    }

    /**
     * 409：发布单覆盖到待批准的测试计划（R1）。
     *
     * <p>机器可读位置统一是 {@code detail.planIds}（与既有 409 的 {@code detail.failedRules} 同规格），
     * {@code message} 同时带 {@code #id}，便于人读与文本断言；{@code plans} 是逐计划明细，
     * 前端能直接渲染"哪张计划、什么状态、覆盖了哪些需求"。</p>
     */
    public static ApiException planNotApproved(List<DeliverPlanCoverageClient.PlanRef> plans,
                                               List<Long> requirementIds) {
        List<DeliverPlanCoverageClient.PlanRef> safePlans = plans == null ? List.of() : plans;
        List<Long> planIds = safePlans.stream().map(DeliverPlanCoverageClient.PlanRef::id).toList();
        String refs = planIds.stream().map(id -> "#" + id).reduce((left, right) -> left + "、" + right)
                .orElse("#?");
        return new ApiException(409, PLAN_NOT_APPROVED,
                "发布单引用了未批准的测试计划 " + refs + "，请先完成计划评审批准或移除对应需求",
                Map.of("planIds", planIds,
                        "plans", safePlans,
                        "requirementIds", requirementIds == null ? List.of() : requirementIds,
                        "hint", PLAN_NOT_APPROVED_HINT));
    }

    /**
     * 503：覆盖查询不可用（超时/连接失败/载荷异常）。fail-closed —— 校验不了就不落单，
     * 绝不把"没查到"当成"没有待批计划"。
     */
    public static ApiException planCheckUnavailable(String reason) {
        return new ApiException(503, PLAN_CHECK_UNAVAILABLE,
                "暂时无法校验测试计划批准状态，请稍后重试",
                Map.of("reason", reason == null ? "" : reason));
    }

    public static ApprovalNotConfiguredException approvalNotConfigured() {
        return new ApprovalNotConfiguredException(
                "没有匹配的已发布审批流程（bizType=" + DeployOrderService.APPROVAL_BIZ_TYPE + "），"
                        + "请先在审批流配置中发布需求发布流程");
    }

    public static ApiException illegalTransition(String action, String status) {
        return new ApiException(409, RequirementErrors.ILLEGAL_TRANSITION,
                "当前状态不允许该动作：" + status + " --" + action,
                Map.of("from", status, "action", action,
                        "allowedActions", DeployOrderStates.allowedActions(status)));
    }

    public static ApiException validation(String message) {
        return RequirementErrors.validation(message);
    }

    public static ApiException validation(String message, Map<String, Object> detail) {
        return RequirementErrors.validation(message, detail);
    }

    public static ApiException notFound(Object id) {
        return RequirementErrors.notFound("发布单", id);
    }

    public static ApiException reasonRequired() {
        return RequirementErrors.reasonRequired();
    }

    public static ApiException forbidden(String message) {
        return RequirementErrors.forbidden(message);
    }
}
