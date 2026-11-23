package com.example.req.requirement.change;

import java.util.Map;

/**
 * 变更单的创建/预览请求体。
 *
 * <p>patch 刻意用开放 Map：四种变更类型的字段集合由 {@link RequirementChangeFields#allowedFields}
 * 单点声明，Validator 负责封闭。若改成每类型一个 DTO，白名单就会散成四份，新增字段时
 * 必然漏一处。</p>
 */
public final class RequirementChangeDtos {

    private RequirementChangeDtos() {
    }

    public record CreateRequest(String changeType, String reason, Map<String, Object> patch) {
    }
}
