package com.example.req.approval;

/**
 * 流程定义解析端口：把「单据类型 + 需求类型 + 域」解析成一个已发布的流程版本。
 *
 * <p>解析顺序固定在 {@link JpaWorkflowRegistry}（精确绑定优先、版本最高优先、再退默认流程），
 * 引擎只拿结果，不再判断「该用哪个流程」，避免两处解析出现分歧。
 */
public interface WorkflowRegistry {

    /** 已发布的流程版本视图：{@code id + version} 就是实例要钉住的东西。 */
    record ResolvedWorkflow(long id, int version, String name, String status, WorkflowDefinition definition) {
    }

    /** 解析不到返回 {@code null}（调用方转 400 NO_WORKFLOW）。 */
    ResolvedWorkflow resolve(long tenantId, String bizType, String reqType, long domainId);

    /** 按定义行 ID 读取（含 DRAFT/DISABLED，调用方自行判断状态）。 */
    ResolvedWorkflow byId(long definitionId);
}
