package com.example.req.deploy;

import com.example.platform.common.error.ApiException;
import com.example.req.requirement.RequirementErrors;

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
