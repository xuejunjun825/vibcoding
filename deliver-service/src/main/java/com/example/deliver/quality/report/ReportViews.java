package com.example.deliver.quality.report;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Wire shapes of the test report surface (TECH §5 "报告"), aligned with {@code web/src/api/types.ts}.
 *
 * <p>The snapshot is a plain {@code Map} parsed out of {@code test_report_version.snapshot_json}: the
 * 8 sections are frozen JSON, deliberately not a typed model, so an old version stays readable after
 * the generator evolves. Its outer keys ({@code conclusion}, {@code passRate}, {@code coverage},
 * {@code planRevision}, {@code resultDigest}) are what the reader shows above the tabs; the 8 frozen
 * sections live under {@code sections} and are rendered in their fixed order.</p>
 */
public final class ReportViews {

    private ReportViews() {
    }

    /** {@code POST /test-reports} body. {@code conclusion} may lower the computed one, never raise it. */
    public record Generate(String scope, Long planId, Long releaseId, String conclusion, String note) {
    }

    /** {@code POST /test-reports/{id}/versions/{n}/signoff} body. */
    public record SignCommand(String decision, String conclusion, String comment, Boolean riskAcknowledged) {
    }

    /** A plan that belongs to the release but is not reportable (snapshot {@code scope.excludedPlans}). */
    public record ExcludedPlan(long planId, String name, String status) {
    }

    /** One row of the version selector. */
    public record VersionRow(long reportId,
                             int versionNo,
                             String status,
                             String conclusion,
                             BigDecimal passRate,
                             BigDecimal coverage,
                             String resultDigest,
                             String hash,
                             String generatedByName,
                             String generatedAt,
                             String signedAt,
                             int signedCount,
                             int totalSignoffs) {
    }

    /**
     * One node of the 3-step signoff rail. {@code status} is PENDING / SIGNED / REJECTED;
     * {@code current} marks the rung waiting for a signature now, {@code canSign} whether the caller
     * may sign it (the UI hides the button, the service still decides).
     */
    public record SignoffStep(int orderNo,
                              String signRole,
                              String roleName,
                              String status,
                              Long signerId,
                              String signerName,
                              String signedAt,
                              String comment,
                              boolean current,
                              boolean canSign) {
    }

    /** Progress of one version's signoff. {@code step = approved + 1}; {@code signed} when all approved. */
    public record SignoffProgress(long reportId,
                                  int versionNo,
                                  String status,
                                  String conclusion,
                                  int step,
                                  int total,
                                  int approved,
                                  boolean signed,
                                  String pendingRole,
                                  String pendingRoleLabel,
                                  List<SignoffStep> steps) {
    }

    /** Report list row (report centre Q6). */
    public record ReportRow(long id,
                            String code,
                            String scope,
                            Long planId,
                            Long releaseId,
                            String planName,
                            String releaseName,
                            String status,
                            int currentVersion,
                            String conclusion,
                            BigDecimal passRate,
                            BigDecimal coverage,
                            int signedCount,
                            int totalSignoffs,
                            String pendingRole,
                            String pendingRoleLabel,
                            String dataCutoff,
                            String generatedAt,
                            String signedAt,
                            String expiresAt,
                            Integer validityDays,
                            Long createdBy,
                            String createdByName) {
    }

    /**
     * Report header + version selector + live signoff progress (Q7 top half). {@code report} is the
     * row the report centre shows, {@code currentVersion} the version the reader opens by default.
     * {@code id}/{@code code} repeat the identity at the top level for scripts and deep links.
     */
    public record ReportDetail(long id,
                               String code,
                               ReportRow report,
                               ReportVersionDetail currentVersion,
                               List<VersionRow> versions,
                               SignoffProgress signoffProgress,
                               List<ExcludedPlan> excludedPlans,
                               List<Map<String, Object>> includedPlans,
                               List<Map<String, Object>> uncoveredRequirements,
                               String lastRejectComment) {
    }

    /** One version with its frozen 8-section snapshot. */
    public record ReportVersionDetail(long reportId,
                                      String code,
                                      String scope,
                                      Long planId,
                                      Long releaseId,
                                      String planName,
                                      String releaseName,
                                      int versionNo,
                                      String status,
                                      String conclusion,
                                      BigDecimal passRate,
                                      BigDecimal coverage,
                                      String resultDigest,
                                      String hash,
                                      String generatedByName,
                                      String generatedAt,
                                      String signedAt,
                                      String expiresAt,
                                      Integer validityDays,
                                      boolean effective,
                                      String note,
                                      SignoffProgress signoff,
                                      Map<String, Object> snapshot) {
    }

    /** Version diff (report centre "对比上一版"). */
    public record ReportDiff(int fromVersion,
                             int toVersion,
                             BigDecimal passRate,
                             BigDecimal coverage,
                             String conclusion,
                             List<String> addedFails,
                             List<String> closedDefects,
                             List<String> newDefects,
                             Map<String, Object> from,
                             Map<String, Object> to) {
    }

    /** One frozen case of a version, resolved against the live case catalogue for its labels. */
    public record CaseDetailRow(long planCaseId,
                                long caseId,
                                String caseCode,
                                int caseVersion,
                                String title,
                                String moduleName,
                                String executorName,
                                String result,
                                String executedAt) {
    }

    /** One frozen defect of a version. */
    public record DefectDetailRow(long id,
                                  String code,
                                  String title,
                                  String severity,
                                  String status,
                                  String source,
                                  boolean escape,
                                  String foundAt) {
    }
}
