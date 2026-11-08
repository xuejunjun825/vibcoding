package com.example.req.requirement;

import org.springframework.stereotype.Component;

/** 需求提报审批（{@code REQ_SUBMIT}）的摘要来源。 */
@Component
public class RequirementSummaryProvider extends RequirementDocumentSummary {

    public static final String BIZ_TYPE = "REQ_SUBMIT";

    public RequirementSummaryProvider(RequirementRepository repository) {
        super(repository);
    }

    @Override
    public String bizType() {
        return BIZ_TYPE;
    }
}
