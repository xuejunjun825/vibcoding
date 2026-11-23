package com.example.req.approval;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 流程定义 JSON 的唯一读写点。定义结构非法一律映射为 {@code 400 WORKFLOW_INVALID}，
 * 避免解析异常以 500 形态泄漏到脚本断言里。
 */
public class WorkflowJson {

    private final ObjectMapper objectMapper;

    /**
     * 固定键集专用：与注入的 mapper 同配置，只把默认属性包含改成 {@code ALWAYS}。
     *
     * <p>变更单的 before/after（各 7 键）与 impact（8 键）是 AC-3 的契约，null 值也必须落库；
     * 全局 {@code default-property-inclusion: non_null} 会把 Map 里的 null 键直接吞掉。
     * 这里从注入实例 rebuild，避免另造一份丢失日期/数值格式的 mapper，也不改全局默认。</p>
     */
    private final ObjectMapper stableMapper;

    public WorkflowJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.stableMapper = objectMapper.rebuild()
                .changeDefaultPropertyInclusion(inclusion -> inclusion
                        .withValueInclusion(JsonInclude.Include.ALWAYS)
                        .withContentInclusion(JsonInclude.Include.ALWAYS))
                .build();
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
     * 序列化「固定键集」快照（变更单 before/after/impact）：null 值保留为显式 {@code null}。
     *
     * <p>只给契约要求键齐全的 Map 用；流程定义等普通 JSON 仍走 {@link #write(Object)}，
     * 保持全局 non_null 默认不动。</p>
     */
    public String writeStable(Object value) {
        try {
            return stableMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new ApprovalException(400, ApprovalException.WORKFLOW_INVALID,
                    "快照无法序列化：" + ex.getMessage(), java.util.Map.of());
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
