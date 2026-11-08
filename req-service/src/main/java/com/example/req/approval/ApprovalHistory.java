package com.example.req.approval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * {@code wf_history}：审批动作的唯一流水（AC-9「全部动作写 wf_history 并可查」）。
 *
 * <p>{@code detail_json} 存结构化补充（加签类型/人员、条件分支下标、转办前后人），
 * 时间线渲染与按版本回溯都读这张表，不读任务表的历史态。
 */
@Entity
@Table(name = "wf_history")
public class ApprovalHistory {

    public static final String SUBMIT = "SUBMIT";
    public static final String APPROVE = "APPROVE";
    public static final String REJECT = "REJECT";
    public static final String ADD_SIGN = "ADD_SIGN";
    public static final String REMOVE_SIGN = "REMOVE_SIGN";
    public static final String TRANSFER = "TRANSFER";
    public static final String WITHDRAW = "WITHDRAW";
    public static final String URGE = "URGE";
    public static final String CC_SEND = "CC_SEND";
    public static final String CONDITION_ROUTE = "CONDITION_ROUTE";
    public static final String NODE_APPROVED = "NODE_APPROVED";
    public static final String TASK_CANCELLED = "TASK_CANCELLED";
    public static final String INSTANCE_APPROVED = "INSTANCE_APPROVED";
    public static final String INSTANCE_REJECTED = "INSTANCE_REJECTED";
    public static final String INSTANCE_WITHDRAWN = "INSTANCE_WITHDRAWN";
    public static final String APPROVER_UNRESOLVED = "APPROVER_UNRESOLVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "instance_id", nullable = false)
    private Long instanceId;

    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "operator_id")
    private Long operatorId;

    @Column(name = "comment", length = 512)
    private String comment;

    @Column(name = "detail_json", columnDefinition = "json")
    private String detailJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

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

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(Long operatorId) {
        this.operatorId = operatorId;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public String getDetailJson() {
        return detailJson;
    }

    public void setDetailJson(String detailJson) {
        this.detailJson = detailJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
