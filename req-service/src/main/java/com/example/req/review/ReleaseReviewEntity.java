package com.example.req.review;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A Go/No-Go review record. It lives in req, not deliver, because the decision belongs to the release
 * domain (same database, same release state machine as the deploy order); quality data is referenced by
 * id only — the gate snapshot and report version are never copied in, so a review can never show numbers
 * that differ from what the gate actually computed.
 *
 * <p>{@code gateEvaluated} is 0 for legacy records whose snapshot could not be located (AC-7: an old
 * order is shown as "gate not enabled", never as a fabricated PASS).
 */
@Entity
@Table(name = "release_review")
public class ReleaseReviewEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "release_id", nullable = false)
    private Long releaseId;

    @Column(name = "deploy_order_id")
    private Long deployOrderId;

    @Column(name = "gate_snapshot_id")
    private Long gateSnapshotId;

    @Column(name = "report_id")
    private Long reportId;

    @Column(name = "report_version_no")
    private Integer reportVersionNo;

    @Column(name = "meeting_at", nullable = false)
    private LocalDateTime meetingAt;

    @Column(name = "participants_json", length = 1000)
    private String participantsJson;

    @Column(name = "risks_json", columnDefinition = "TEXT")
    private String risksJson;

    @Column(name = "open_defects_json", columnDefinition = "TEXT")
    private String openDefectsJson;

    @Column(nullable = false, length = 12)
    private String decision;

    @Column(length = 1000)
    private String conditions;

    @Column(name = "doc_path", length = 300)
    private String docPath;

    @Column(name = "gate_evaluated", nullable = false)
    private Integer gateEvaluated = 0;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(nullable = false, length = 12)
    private String status = "RECORDED";

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getReleaseId() {
        return releaseId;
    }

    public void setReleaseId(Long releaseId) {
        this.releaseId = releaseId;
    }

    public Long getDeployOrderId() {
        return deployOrderId;
    }

    public void setDeployOrderId(Long deployOrderId) {
        this.deployOrderId = deployOrderId;
    }

    public Long getGateSnapshotId() {
        return gateSnapshotId;
    }

    public void setGateSnapshotId(Long gateSnapshotId) {
        this.gateSnapshotId = gateSnapshotId;
    }

    public Long getReportId() {
        return reportId;
    }

    public void setReportId(Long reportId) {
        this.reportId = reportId;
    }

    public Integer getReportVersionNo() {
        return reportVersionNo;
    }

    public void setReportVersionNo(Integer reportVersionNo) {
        this.reportVersionNo = reportVersionNo;
    }

    public LocalDateTime getMeetingAt() {
        return meetingAt;
    }

    public void setMeetingAt(LocalDateTime meetingAt) {
        this.meetingAt = meetingAt;
    }

    public String getParticipantsJson() {
        return participantsJson;
    }

    public void setParticipantsJson(String participantsJson) {
        this.participantsJson = participantsJson;
    }

    public String getRisksJson() {
        return risksJson;
    }

    public void setRisksJson(String risksJson) {
        this.risksJson = risksJson;
    }

    public String getOpenDefectsJson() {
        return openDefectsJson;
    }

    public void setOpenDefectsJson(String openDefectsJson) {
        this.openDefectsJson = openDefectsJson;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getConditions() {
        return conditions;
    }

    public void setConditions(String conditions) {
        this.conditions = conditions;
    }

    public String getDocPath() {
        return docPath;
    }

    public void setDocPath(String docPath) {
        this.docPath = docPath;
    }

    public Integer getGateEvaluated() {
        return gateEvaluated;
    }

    public void setGateEvaluated(Integer gateEvaluated) {
        this.gateEvaluated = gateEvaluated;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Long getConfirmedBy() {
        return confirmedBy;
    }

    public void setConfirmedBy(Long confirmedBy) {
        this.confirmedBy = confirmedBy;
    }

    public LocalDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(LocalDateTime confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
