package com.example.deliver.quality.report;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One signature of the three-level sign-off (QA lead → QA manager → product owner). {@code orderNo}
 * stores the level 1..3 so "signed out of order" is detectable from the rows themselves, and the unique
 * key (report_id, version_no, sign_role) is what makes a duplicate signature a database conflict rather
 * than a second row nobody notices.
 */
@Entity
@Table(name = "report_signoff")
public class ReportSignoffEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "sign_role", nullable = false, length = 16)
    private String signRole;

    @Column(name = "signer_id", nullable = false)
    private Long signerId;

    @Column(nullable = false, length = 12)
    private String decision;

    /** Reserved word in MySQL; mapped as-is and quoted by hibernate.auto_quote_keyword. */
    @Column(name = "comment", length = 1000)
    private String comment;

    @Column(name = "signed_at", nullable = false)
    private LocalDateTime signedAt;

    @Column(name = "order_no", nullable = false)
    private Integer orderNo;

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

    public Long getReportId() {
        return reportId;
    }

    public void setReportId(Long reportId) {
        this.reportId = reportId;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getSignRole() {
        return signRole;
    }

    public void setSignRole(String signRole) {
        this.signRole = signRole;
    }

    public Long getSignerId() {
        return signerId;
    }

    public void setSignerId(Long signerId) {
        this.signerId = signerId;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public LocalDateTime getSignedAt() {
        return signedAt;
    }

    public void setSignedAt(LocalDateTime signedAt) {
        this.signedAt = signedAt;
    }

    public Integer getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(Integer orderNo) {
        this.orderNo = orderNo;
    }
}
