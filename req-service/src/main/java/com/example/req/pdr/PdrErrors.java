package com.example.req.pdr;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.platform.common.error.ApiException;

/**
 * PDR 评审的错误工厂（设计 §6 R1/R2/R5/R7/R8 的错误码集中在这里）。
 *
 * <p>刻意不复用 {@code RequirementErrors}：那一类是「需求」的错误码，评审单有自己的 id 空间与
 * 生命周期；混在一处后 QA 无法判断某个 409 来自需求状态机还是评审单。</p>
 */
public final class PdrErrors {

    public static final String REVIEW_NOT_FOUND = "REVIEW_NOT_FOUND";
    public static final String PDR_REVIEW_ALREADY_OPEN = "PDR_REVIEW_ALREADY_OPEN";
    public static final String REVIEW_CLOSED = "REVIEW_CLOSED";
    public static final String REVIEWER_NOT_IN_PROJECT = "REVIEWER_NOT_IN_PROJECT";
    public static final String NO_REVIEWER = "NO_REVIEWER";
    public static final String NOT_REVIEWER = "NOT_REVIEWER";
    public static final String NOT_ALLOWED = "NOT_ALLOWED";
    public static final String INVALID_CATEGORY = "INVALID_CATEGORY";
    public static final String INVALID_DECISION = "INVALID_DECISION";
    public static final String INVALID_REQUIREMENT_TYPE = "INVALID_REQUIREMENT_TYPE";
    public static final String PDR_REVIEW_INCOMPLETE = "PDR_REVIEW_INCOMPLETE";
    public static final String OPINION_NOT_FOUND = "OPINION_NOT_FOUND";
    public static final String OPINION_ALREADY_TASKED = "OPINION_ALREADY_TASKED";
    public static final String TASK_CREATE_UNAVAILABLE = "TASK_CREATE_UNAVAILABLE";

    private PdrErrors() {
    }

    public static ApiException reviewNotFound(long reviewId) {
        return new ApiException(404, REVIEW_NOT_FOUND, "评审单不存在或不在你的数据范围内",
                Map.of("reviewId", reviewId));
    }

    public static ApiException opinionNotFound(long opinionId) {
        return new ApiException(404, OPINION_NOT_FOUND, "意见不存在", Map.of("opinionId", opinionId));
    }

    /** 同一需求已有在评评审单（设计 R5：唯一约束 + 409 兜底）。 */
    public static ApiException alreadyOpen(long requirementId, long reviewId) {
        return new ApiException(409, PDR_REVIEW_ALREADY_OPEN,
                "该需求已有一张在评的 PDR 评审单，请先得出结论或关闭它",
                Map.of("requirementId", requirementId, "reviewId", reviewId));
    }

    /** 评审已结束：新增意见 / 上传附件 / 删除附件 / 再次关闭都是这个码（设计 R7/R8）。 */
    public static ApiException reviewClosed(long reviewId, String status) {
        return new ApiException(409, REVIEW_CLOSED, "评审已结束，不能再新增意见或附件",
                Map.of("reviewId", reviewId, "status", status == null ? "" : status));
    }

    /** 评审人不在项目组名册里（验收 B4）。 */
    public static ApiException reviewerNotInProject(long reviewerId) {
        return new ApiException(400, REVIEWER_NOT_IN_PROJECT,
                "评审人必须先是该项目组成员", Map.of("reviewerId", reviewerId));
    }

    /** 项目组为空且回退也找不到人（产品经理与域负责人都没有）。 */
    public static ApiException noReviewer(long requirementId) {
        return new ApiException(400, NO_REVIEWER,
                "项目组为空，且回退的「产品经理 + 域负责人」也无人可评；请先维护项目组成员",
                Map.of("requirementId", requirementId));
    }

    /** 调用者不是这张单的评审人/发起人（验收 B7）。 */
    public static ApiException notReviewer(long reviewId, long userId) {
        return new ApiException(403, NOT_REVIEWER, "只有本评审单的评审人或发起人可以操作",
                Map.of("reviewId", reviewId, "userId", userId));
    }

    /** 与 NOT_REVIEWER 区分的一档：动作本身不允许（如非评审人转任务，验收 D4）。 */
    public static ApiException notAllowed(String action) {
        return new ApiException(403, NOT_ALLOWED, "无权执行该操作", Map.of("action", action));
    }

    /** 分类缺失或不在九类内（验收 C1）：detail.allowed[] 恰好九个 code。 */
    public static ApiException invalidCategory(String category) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("category", category);
        detail.put("allowed", PdrCategories.ALL);
        return new ApiException(400, INVALID_CATEGORY,
                "category 必填且必须是九类之一", detail);
    }

    public static ApiException invalidDecision(String decision) {
        return new ApiException(400, INVALID_DECISION, "decision 只能是 PASS / NEED_CHANGE / REJECT",
                Map.of("decision", decision == null ? "" : decision,
                        "allowed", List.of("PASS", "NEED_CHANGE", "REJECT")));
    }

    public static ApiException invalidRequirementType(String type) {
        return new ApiException(400, INVALID_REQUIREMENT_TYPE,
                "只有 PDR（产品需求）才能发起 PDR 评审", Map.of("type", type == null ? "" : type));
    }

    /** MODE_ALL 下有人没评就想出结论。 */
    public static ApiException incomplete(List<Long> pending) {
        return new ApiException(409, PDR_REVIEW_INCOMPLETE,
                "还有评审人未提交意见，MODE_ALL 需要全员评审", Map.of("pendingReviewerIds", pending));
    }

    /** 意见已转过任务（验收 D3）：detail.taskId 是既有任务。 */
    public static ApiException alreadyTasked(long opinionId, Long taskId) {
        return new ApiException(409, OPINION_ALREADY_TASKED,
                "该意见已转为任务，不重复创建", Map.of("opinionId", opinionId, "taskId", taskId));
    }

    /** 交付域不可用（设计 R8：跨服务失败 503，本地写回滚）。 */
    public static ApiException taskCreateUnavailable(String reason) {
        return new ApiException(503, TASK_CREATE_UNAVAILABLE,
                "任务创建服务暂不可用，请稍后重试", Map.of("reason", reason == null ? "" : reason));
    }
}
