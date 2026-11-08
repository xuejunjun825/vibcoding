package com.example.req.approval;

import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 流程定义 JSON 的唯一读写点。定义结构非法一律映射为 {@code 400 WORKFLOW_INVALID}，
 * 避免解析异常以 500 形态泄漏到脚本断言里。
 */
public class WorkflowJson {

    private final ObjectMapper objectMapper;

    public WorkflowJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public WorkflowDefinition parse(String json) {
        if (json == null || json.isBlank()) {
            throw new ApprovalException(400, ApprovalException.WORKFLOW_INVALID, "流程定义为空", java.util.Map.of());
        }
        try {
            WorkflowDefinition definition = objectMapper.readValue(json, WorkflowDefinition.class);
            if (definition == null) {
                throw new ApprovalException(400, ApprovalException.WORKFLOW_INVALID, "流程定义为空", java.util.Map.of());
            }
            return definition;
        } catch (ApprovalException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApprovalException(400, ApprovalException.WORKFLOW_INVALID,
                    "流程定义 JSON 无法解析：" + ex.getMessage(), java.util.Map.of());
        }
    }

    public String write(WorkflowDefinition definition) {
        return write((Object) definition);
    }

    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new ApprovalException(400, ApprovalException.WORKFLOW_INVALID,
                    "流程定义无法序列化：" + ex.getMessage(), java.util.Map.of());
        }
    }

    /**
     * 读任意 JSON 对象（流水 {@code detail_json} 等）。内容损坏时返回空 Map 而不是抛异常：
     * 流水详情只用于展示/审计，不能因为一条历史脏数据让待办打不开。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
