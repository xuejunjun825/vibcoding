package com.example.req.integration.store;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 需求对应的一条 GitLab MR（表 {@code mr_sync}，见 V8）。
 *
 * <p>唯一键 {@code uk_mr(tenant_id, gitlab_project, mr_iid)} 让 webhook 与兜底轮询可以
 * 重复投递而不产生重复行；{@code last_event_at} 是单调水位——比它旧的事件只能更新
 * 「标题/审批数」这类可回退字段，不能把 MERGED 打回 OPENED。</p>
 */
@Entity
@Table(name = "mr_sync",
        uniqueConstraints = @UniqueConstraint(name = "uk_mr",
                columnNames = {"tenant_id", "gitlab_project", "mr_iid"}))
public class MrSyncEntity {

    public static final String STATE_OPENED = "OPENED";
    public static final String STATE_MERGED = "MERGED";
    public static final String STATE_CLOSED = "CLOSED";
    public static final String STATE_LOCKED = "LOCKED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "requirement_id", nullable = false)
    private Long requirementId;

    @Column(name = "config_id", nullable = false)
    private Long configId;

    @Column(name = "gitlab_project", nullable = false, length = 200)
    private String gitlabProject;

    @Column(name = "mr_iid", nullable = false)
    private Long mrIid;

    @Column(name = "mr_url", nullable = false, length = 500)
    private String mrUrl;

    @Column(name = "title", length = 300)
    private String title;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    @Column(name = "source_branch", length = 200)
    private String sourceBranch;

    @Column(name = "target_branch", length = 200)
    private String targetBranch;

    @Column(name = "approvals_count", nullable = false)
    private Integer approvalsCount = 0;

    @Column(name = "additions", nullable = false)
    private Integer additions = 0;

    @Column(name = "deletions", nullable = false)
    private Integer deletions = 0;

    @Column(name = "last_event_at", nullable = false)
    private LocalDateTime lastEventAt;

    @Column(name = "merged_at")
    private LocalDateTime mergedAt;

    @Column(name = "payload_json", columnDefinition = "MEDIUMTEXT")
    private String payloadJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (approvalsCount == null) {
            approvalsCount = 0;
        }
        if (additions == null) {
            additions = 0;
        }
        if (deletions == null) {
            deletions = 0;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public boolean isMerged() {
        return STATE_MERGED.equals(state);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getRequirementId() { return requirementId; }
    public void setRequirementId(Long requirementId) { this.requirementId = requirementId; }
    public Long getConfigId() { return configId; }
    public void setConfigId(Long configId) { this.configId = configId; }
    public String getGitlabProject() { return gitlabProject; }
    public void setGitlabProject(String gitlabProject) { this.gitlabProject = gitlabProject; }
    public Long getMrIid() { return mrIid; }
    public void setMrIid(Long mrIid) { this.mrIid = mrIid; }
    public String getMrUrl() { return mrUrl; }
    public void setMrUrl(String mrUrl) { this.mrUrl = mrUrl; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getSourceBranch() { return sourceBranch; }
    public void setSourceBranch(String sourceBranch) { this.sourceBranch = sourceBranch; }
    public String getTargetBranch() { return targetBranch; }
    public void setTargetBranch(String targetBranch) { this.targetBranch = targetBranch; }
    public Integer getApprovalsCount() { return approvalsCount; }
    public void setApprovalsCount(Integer approvalsCount) { this.approvalsCount = approvalsCount; }
    public Integer getAdditions() { return additions; }
    public void setAdditions(Integer additions) { this.additions = additions; }
    public Integer getDeletions() { return deletions; }
    public void setDeletions(Integer deletions) { this.deletions = deletions; }
    public LocalDateTime getLastEventAt() { return lastEventAt; }
    public void setLastEventAt(LocalDateTime lastEventAt) { this.lastEventAt = lastEventAt; }
    public LocalDateTime getMergedAt() { return mergedAt; }
    public void setMergedAt(LocalDateTime mergedAt) { this.mergedAt = mergedAt; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
