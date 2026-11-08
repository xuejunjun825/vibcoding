package com.example.req.approval;

import java.util.Map;

/**
 * 发起审批的入参。
 *
 * @param bizType      单据类型：{@code REQ_SUBMIT / REQ_REVIEW / RELEASE / REQ_DEPLOY}
 * @param bizId        单据 ID
 * @param reqType      需求类型（{@code RD/BUS/TECH}），用于流程绑定；非需求单据可传 {@code null}
 * @param domainId     业务域 ID，用于流程绑定
 * @param attributes   条件分支需要的单据属性（priority/estimateDays/domainIds…）；
 *                     启动时快照进 SUBMIT 流水，后续节点不再回读单据，避免「审批中途改优先级导致重路由」
 * @param definitionId 指定流程版本（一般不用；不给则按类型×域解析已发布流程）
 * @param initiatorId  发起人；为空取当前登录人（代提场景由调用方显式给出）
 */
public record StartApproval(String bizType,
                            long bizId,
                            String reqType,
                            Long domainId,
                            Map<String, Object> attributes,
                            Long definitionId,
                            Long initiatorId) {

    public StartApproval {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
