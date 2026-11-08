package com.example.req.approval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * 审批流程定义（schemaVersion=1）的 JSON 模型。纯数据，不依赖 Spring/DB。
 *
 * <p>结构见 TECHNICAL §5.6：节点类型 {@code APPROVAL / CONDITION / CC}，终点用哨兵 {@code "end"}。
 * 定义一旦发布即不可编辑，实例钉住 {@code definition_id + definition_version}，因此这里不需要快照拷贝。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowDefinition(Integer schemaVersion, String start, List<Node> nodes) {

    /** 支持的定义结构版本。 */
    public static final int SUPPORTED_SCHEMA = 1;

    /** 流程终点哨兵值（{@code next}/{@code defaultNext}/{@code branch.next} 都可指向它）。 */
    public static final String END = "end";

    public static final String TYPE_APPROVAL = "APPROVAL";
    public static final String TYPE_CONDITION = "CONDITION";
    public static final String TYPE_CC = "CC";

    public static final String MODE_ALL = "ALL";
    public static final String MODE_ANY = "ANY";

    public WorkflowDefinition {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    /** 按 key 查节点；不存在返回 {@code null}（{@link #END} 也返回 null）。 */
    public Node node(String key) {
        if (key == null || END.equals(key)) {
            return null;
        }
        for (Node node : nodes) {
            if (node != null && key.equals(node.key())) {
                return node;
            }
        }
        return null;
    }

    public boolean endsAt(String key) {
        return key == null || END.equals(key);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Node(String key,
                       String type,
                       String name,
                       String mode,
                       List<Approver> approvers,
                       List<Branch> branches,
                       String defaultNext,
                       String next,
                       List<Approver> receivers) {

        public Node {
            approvers = approvers == null ? List.of() : List.copyOf(approvers);
            branches = branches == null ? List.of() : List.copyOf(branches);
            receivers = receivers == null ? List.of() : List.copyOf(receivers);
            if (mode == null || mode.isBlank()) {
                mode = MODE_ALL;
            }
        }

        public boolean isApproval() {
            return TYPE_APPROVAL.equalsIgnoreCase(type);
        }

        public boolean isCondition() {
            return TYPE_CONDITION.equalsIgnoreCase(type);
        }

        public boolean isCc() {
            return TYPE_CC.equalsIgnoreCase(type);
        }

        /** 显示名：未配置时退回节点 key，保证通知/历史里始终有可读文本。 */
        public String displayName() {
            return name == null || name.isBlank() ? key : name;
        }

        /** 节点里第一个前加签（BEFORE）之后的待办任务是否已存在——由引擎按需计算，这里只提供判据。 */
        public boolean allowsAddSign() {
            return isApproval();
        }
    }

    /** 审批人声明：{@code ROLE} / {@code USER} / {@code SUPERVISOR} / {@code DOMAIN_OWNER}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Approver(String type, String value, String scope) {

        public static final String TYPE_ROLE = "ROLE";
        public static final String TYPE_USER = "USER";
        public static final String TYPE_SUPERVISOR = "SUPERVISOR";
        public static final String TYPE_DOMAIN_OWNER = "DOMAIN_OWNER";

        public boolean is(String candidate) {
            return candidate != null && candidate.equalsIgnoreCase(type);
        }
    }

    /** 条件分支：命中 {@code when} 的第一条即路由，未命中走 {@code defaultNext}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Branch(When when, String next) {
    }

    /**
     * 条件表达式。所有非空字段取「与」；同一字段内的多个取值取「或」。
     * {@code estimateDays} 支持 {@code gte/gt/lte/lt}。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record When(List<String> reqType,
                       List<Long> domainId,
                       List<String> priority,
                       Days estimateDays) {

        public When {
            reqType = reqType == null ? List.of() : List.copyOf(reqType);
            domainId = domainId == null ? List.of() : List.copyOf(domainId);
            priority = priority == null ? List.of() : List.copyOf(priority);
        }

        public boolean isEmpty() {
            return reqType.isEmpty() && domainId.isEmpty() && priority.isEmpty() && estimateDays == null;
        }
    }

    /** 人天区间条件，全部字段可选。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Days(BigDecimal gte, BigDecimal gt, BigDecimal lte, BigDecimal lt) {
    }
}
