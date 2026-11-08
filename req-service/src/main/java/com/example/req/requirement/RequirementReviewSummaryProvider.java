package com.example.req.requirement;

import org.springframework.stereotype.Component;

/** 需求评审审批（{@code REQ_REVIEW}）的摘要来源；字段裁剪与提报审批一致。 */
@Component
public class RequirementReviewSummaryProvider extends RequirementDocumentSummary {

    public static final String BIZ_TYPE = "REQ_REVIEW";

    public RequirementReviewSummaryProvider(RequirementRepository repository) {
        super(repository);
    }

    @Override
    public String bizType() {
        return BIZ_TYPE;
    }
}
