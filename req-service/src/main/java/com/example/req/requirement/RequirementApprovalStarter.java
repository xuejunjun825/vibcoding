package com.example.req.requirement;

/**
 * 需求侧发起审批的端口：实现由审批引擎适配器提供（req-service 的 approval 包）。
 * 需求服务只依赖该窄接口，因此没有审批引擎时也能启动、单测与演示流转。
 */
public interface RequirementApprovalStarter {

    /**
     * @param requirementId 需求 ID（审批实例的 biz_id）
     * @param bizType       REQ_SUBMIT / REQ_REVIEW
     * @param source        UI / SYSTEM
     * @return 审批实例 ID；返回 0 表示未创建实例
     */
    long start(long requirementId, String bizType, String source);

    /**
     * 发起「评审入池」审批（REQ_REVIEW）。已有在途实例时幂等返回该实例，不重复创建。
     *
     * @param definitionId 指定的流程定义；null 表示按租户 + req_type 自动绑定
     * @return 审批实例 ID
     */
    long startReview(long requirementId, String source, Long definitionId);

    /** 最近一次该类审批是否已通过（无实例按未通过处理，fail-closed）。 */
    boolean isApproved(long requirementId, String bizType);
}
