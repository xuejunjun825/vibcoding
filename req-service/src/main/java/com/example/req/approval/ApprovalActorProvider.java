package com.example.req.approval;

/**
 * 当前操作人的来源（SPI）。生产实现从 {@code TenantContext} 取，单测用固定实现，
 * 这样引擎/校验器都不依赖线程上下文，也就不需要为了跑单测启动 Spring。
 */
public interface ApprovalActorProvider {

    ApprovalActor current();

    /**
     * 是否可执行管理员动作（代他人减签、转办他人待办、催办）。
     * 权限矩阵之外的特例只在这里落地，控制器与引擎不各自判断角色。
     */
    boolean isAdmin(long tenantId, long userId);
}
