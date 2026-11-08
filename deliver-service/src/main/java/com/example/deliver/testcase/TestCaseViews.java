package com.example.deliver.testcase;

import java.util.List;

/**
 * Wire shapes for the case library.
 *
 * <p>Field names follow {@code web/src/api/types.ts} verbatim: the frontend is written, so the
 * contract is fixed here rather than negotiated later.</p>
 */
public final class TestCaseViews {

    private TestCaseViews() {
    }

    public record StepPayload(Integer stepNo, String action, String expected) {
    }

    /** A step as returned inside a version snapshot: no step id, only what the tester reads. */
    public record StepView(int stepNo, String action, String expected) {
    }

    public record ModuleView(long id, Long parentId, String name, String path, int level,
                             Long domainId, Long vendorId, int sortNo) {
    }

    public record CreateModule(Long parentId, String name, Long domainId, Long vendorId) {
    }

    public record SaveModule(String name, Integer sortNo) {
    }

    public record CreateCase(Long moduleId, Long requirementId, String title, String type,
                             String priority, String precondition, List<StepPayload> steps,
                             List<String> tags, Boolean automated, String scriptRef) {
    }

    /** Row shape of a list/table: the case at its current version. */
    public record CaseRow(long id, String code, String title, Long moduleId, String moduleName,
                          String type, String priority, int currentVersion, boolean automated,
                          Long requirementId, String updatedAt) {
    }

    /** Detail shape: the requested version (defaults to the current one). */
    public record CaseView(long id, String code, String title, Long moduleId, String moduleName,
                           String type, String priority, int currentVersion, boolean automated,
                           Long requirementId, String updatedAt, int version, String precondition,
                           List<StepView> steps, List<String> tags, String scriptRef,
                           boolean readonly, String status) {
    }

    public record CaseVersionRow(int versionNo, String createdAt, Long createdBy, boolean current,
                                 String note) {
    }

    public record RowError(int row, String message) {
    }

    public record ImportResult(int ok, int failed, List<RowError> errors) {
    }

    /** Plan detail case row (kept here so the plan package can reuse the SQL mapping). */
    public record PlanCaseRow(long caseId, String caseCode, int caseVersion, String title,
                              String result, String executedAt, String note) {
    }
}
