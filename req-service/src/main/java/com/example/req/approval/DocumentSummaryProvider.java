package com.example.req.approval;

import java.util.Map;

/**
 * 单据摘要/属性来源（SPI，由各单据域注册实现）。
 *
 * <p>两个方法职责不同，不能合并：
 * <ul>
 *   <li>{@link #attributes} 供条件分支与抄送解析使用（不含敏感字段，引擎只读不用）</li>
 *   <li>{@link #summary} 供审批中心展示使用，必须按<b>查看人</b>的数据范围裁剪（§5.2：
 *       越权查看只能看到摘要）</li>
 * </ul>
 * 未注册实现时审批中心降级为空摘要，而不是 500。
 */
public interface DocumentSummaryProvider {

    /** 归属的单据类型，如 {@code REQ_REVIEW} / {@code RELEASE}。 */
    String bizType();

    Map<String, Object> attributes(long tenantId, long bizId);

    Map<String, Object> summary(long tenantId, long bizId, long viewerId);
}
