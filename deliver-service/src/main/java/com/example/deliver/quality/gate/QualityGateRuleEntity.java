package com.example.deliver.quality.gate;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One of the five gate rules (QG-01..QG-05) with its threshold and last result. {@code enabled} is INT
 * rather than BIT/TINYINT on purpose: Hibernate's schema validation compares JDBC type codes, and an
 * Integer field on a TINYINT column fails the boot (V2 learned this the hard way).
 */
@Entity
@Table(name = "quality_gate_rule")
public class QualityGateRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "gate_id", nullable = false)
    private Long gateId;

    @Column(name = "rule_code", nullable = false, length = 8)
    private String ruleCode;

    @Column(nullable = false)
    private Integer enabled = 1;

    @Column(name = "threshold_json", length = 500)
    private String thresholdJson;

    @Column(length = 12)
    private String status;

    @Column(name = "evidence_json", columnDefinition = "TEXT")
    private String evidenceJson;

    @Column(name = "evaluated_at")
    private LocalDateTime evaluatedAt;

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

    public Long getGateId() {
        return gateId;
    }

    public void setGateId(Long gateId) {
        this.gateId = gateId;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public void setRuleCode(String ruleCode) {
        this.ruleCode = ruleCode;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public String getThresholdJson() {
        return thresholdJson;
    }

    public void setThresholdJson(String thresholdJson) {
        this.thresholdJson = thresholdJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getEvidenceJson() {
        return evidenceJson;
    }

    public void setEvidenceJson(String evidenceJson) {
        this.evidenceJson = evidenceJson;
    }

    public LocalDateTime getEvaluatedAt() {
        return evaluatedAt;
    }

    public void setEvaluatedAt(LocalDateTime evaluatedAt) {
        this.evaluatedAt = evaluatedAt;
    }
}
