package com.example.deliver.openapi;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Open-API wire shapes (AC-17). Field names are the external contract; do not rename. */
public final class OpenApiViews {

    private OpenApiViews() {
    }

    public record IngestCommand(
            @JsonProperty("run_id") @JsonAlias("runId") String runId,
            @JsonProperty("case_id") @JsonAlias("caseId") Long caseId,
            @JsonProperty("case_version") @JsonAlias("caseVersion") Integer caseVersion,
            @JsonProperty("result") String result,
            @JsonProperty("duration_ms") @JsonAlias("durationMs") Integer durationMs,
            @JsonProperty("log_url") @JsonAlias("logUrl") String logUrl,
            @JsonProperty("executed_at") @JsonAlias("executedAt") String executedAt,
            @JsonProperty("plan_id") @JsonAlias("planId") Long planId,
            @JsonProperty("executor_code") @JsonAlias("executorCode") String executorCode) {
    }

    public record IngestResult(long id, boolean idempotent) {
    }
}
