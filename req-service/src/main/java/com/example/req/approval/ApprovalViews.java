package com.example.req.approval;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 审批中心对外视图（控制器/前端共用一份字段名，避免前后端各自拼 JSON）。 */
public final class ApprovalViews {

    private ApprovalViews() {
    }

    /** 实例视图；{@code definitionVersion} 是 AC-8「实例记录版本」的可见证据。 */
    public record InstanceView(long id,
                               long definitionId,
                               int definitionVersion,
                               String bizType,
                               long bizId,
                               long initiatorId,
                               String status,
                               String currentNode,
                               LocalDateTime startedAt,
                               LocalDateTime endedAt) {

        public static InstanceView of(ApprovalInstance instance) {
            return new InstanceView(
                    instance.getId() == null ? 0L : instance.getId(),
                    instance.getDefinitionId() == null ? 0L : instance.getDefinitionId(),
                    instance.getDefinitionVersion() == null ? 0 : instance.getDefinitionVersion(),
                    instance.getBizType(),
                    instance.getBizId() == null ? 0L : instance.getBizId(),
                    instance.getInitiatorId() == null ? 0L : instance.getInitiatorId(),
                    instance.getStatus(),
                    instance.getCurrentNode(),
                    instance.getStartedAt(),
                    instance.getEndedAt());
        }
    }

    public record TaskView(long id,
                           String nodeKey,
                           String nodeName,
                           long approverId,
                           String mode,
                           String status,
                           String result,
                           String comment,
                           String origin,
                           String addSignType,
                           int sortNo,
                           LocalDateTime handledAt) {

        public static TaskView of(ApprovalTask task) {
            return new TaskView(
                    task.getId() == null ? 0L : task.getId(),
                    task.getNodeKey(),
                    task.getNodeName(),
                    task.getApproverId() == null ? 0L : task.getApproverId(),
                    task.getMode(),
                    task.getStatus(),
                    task.getResult(),
                    task.getComment(),
                    task.getOrigin(),
                    task.getAddSignType(),
                    task.getSortNo() == null ? 0 : task.getSortNo(),
                    task.getHandledAt());
        }
    }

    /** 时间线条目：{@code action} 取值见 {@link ApprovalHistory}，加签/减签是独立条目（AC-9 可查）。 */
    public record TimelineEntry(long id,
                                String action,
                                Long taskId,
                                Long operatorId,
                                String comment,
                                String detail,
                                LocalDateTime at) {

        public static TimelineEntry of(ApprovalHistory history) {
            return new TimelineEntry(
                    history.getId() == null ? 0L : history.getId(),
                    history.getAction(),
                    history.getTaskId(),
                    history.getOperatorId(),
                    history.getComment(),
                    history.getDetailJson(),
                    history.getCreatedAt());
        }
    }

    public record NodeView(String key, String name, String type, String mode) {
    }

    public record CcView(long receiverId, String nodeKey, LocalDateTime readAt) {
    }

    /** 详情：实例 + 单据摘要 + 节点图 + 任务 + 时间线 + 抄送。 */
    public record InstanceDetail(InstanceView instance,
                                 Map<String, Object> document,
                                 List<NodeView> nodes,
                                 List<TaskView> tasks,
                                 List<TimelineEntry> timeline,
                                 List<CcView> ccs) {
    }

    /** 待办/已办行：带上单据摘要，列表就能直接渲染，不需要前端再逐行请求详情。 */
    public record TaskRow(long taskId,
                          long instanceId,
                          String bizType,
                          long bizId,
                          String nodeKey,
                          String nodeName,
                          long initiatorId,
                          String instanceStatus,
                          String status,
                          LocalDateTime createdAt,
                          Map<String, Object> document) {
    }

    /** 我发起的：按实例聚合，带当前节点与版本。 */
    public record MineRow(long instanceId,
                          String bizType,
                          long bizId,
                          String status,
                          String currentNode,
                          int definitionVersion,
                          LocalDateTime startedAt,
                          LocalDateTime endedAt,
                          Map<String, Object> document) {
    }

    /** 审批历史：按实例的 definition_version 标注，供 AC-8「历史可按版本查」。 */
    public record HistoryView(long instanceId, int definitionVersion, List<TimelineEntry> entries) {
    }
}
