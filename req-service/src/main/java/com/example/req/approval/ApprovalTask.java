package com.example.req.approval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * {@code wf_task}：审批节点的待办。
 *
 * <p>{@code sort_no} 决定会签/或签的<b>处理顺序</b>（前加签取更小值、后加签取更大值），
 * {@code sign_no} 只用于满足唯一键 {@code (instance_id, node_key, approver_id, sign_no)}——
 * 同一个人在同一节点多次加签时递增，否则复用会产生重复行。
 */
@Entity
@Table(name = "wf_task")
public class ApprovalTask {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final String ORIGIN_INITIAL = "INITIAL";
    public static final String ORIGIN_ADD_SIGN = "ADD_SIGN";

    public static final String SIGN_BEFORE = "BEFORE";
    public static final String SIGN_AFTER = "AFTER";

    public static final String RESULT_APPROVE = "APPROVE";
    public static final String RESULT_REJECT = "REJECT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "instance_id", nullable = false)
    private Long instanceId;

    @Column(name = "node_key", nullable = false, length = 64)
    private String nodeKey;

    @Column(name = "node_name", length = 128)
    private String nodeName;

    @Column(name = "approver_id", nullable = false)
    private Long approverId;

    @Column(name = "mode", nullable = false, length = 8)
    private String mode;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_PENDING;

    @Column(name = "result", length = 16)
    private String result;

    @Column(name = "comment", length = 512)
    private String comment;

    @Column(name = "origin", nullable = false, length = 16)
    private String origin = ORIGIN_INITIAL;

    @Column(name = "add_sign_type", length = 8)
    private String addSignType;

    @Column(name = "parent_task_id")
    private Long parentTaskId;

    @Column(name = "sign_no", nullable = false)
    private Integer signNo = 1;

    @Column(name = "sort_no", nullable = false)
    private Integer sortNo = 0;

    @Column(name = "handled_at")
    private LocalDateTime handledAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    public boolean isPending() {
        return STATUS_PENDING.equals(status);
    }

    public boolean isAddSign() {
        return ORIGIN_ADD_SIGN.equals(origin);
    }

    public ApprovalTask copyForTest() {
        ApprovalTask copy = new ApprovalTask();
        copy.id = id;
        copy.tenantId = tenantId;
        copy.instanceId = instanceId;
        copy.nodeKey = nodeKey;
        copy.nodeName = nodeName;
        copy.approverId = approverId;
        copy.mode = mode;
        copy.status = status;
        copy.result = result;
        copy.comment = comment;
        copy.origin = origin;
        copy.addSignType = addSignType;
        copy.parentTaskId = parentTaskId;
        copy.signNo = signNo;
        copy.sortNo = sortNo;
        copy.handledAt = handledAt;
        copy.createdAt = createdAt;
        copy.updatedAt = updatedAt;
        copy.createdBy = createdBy;
        copy.updatedBy = updatedBy;
        return copy;
    }

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

    public Long getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(Long instanceId) {
        this.instanceId = instanceId;
    }

    public String getNodeKey() {
        return nodeKey;
    }

    public void setNodeKey(String nodeKey) {
        this.nodeKey = nodeKey;
    }

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public Long getApproverId() {
        return approverId;
    }

    public void setApproverId(Long approverId) {
        this.approverId = approverId;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public String getAddSignType() {
        return addSignType;
    }

    public void setAddSignType(String addSignType) {
        this.addSignType = addSignType;
    }

    public Long getParentTaskId() {
        return parentTaskId;
    }

    public void setParentTaskId(Long parentTaskId) {
        this.parentTaskId = parentTaskId;
    }

    public Integer getSignNo() {
        return signNo;
    }

    public void setSignNo(Integer signNo) {
        this.signNo = signNo;
    }

    public Integer getSortNo() {
        return sortNo;
    }

    public void setSortNo(Integer sortNo) {
        this.sortNo = sortNo;
    }

    public LocalDateTime getHandledAt() {
        return handledAt;
    }

    public void setHandledAt(LocalDateTime handledAt) {
        this.handledAt = handledAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }
}
