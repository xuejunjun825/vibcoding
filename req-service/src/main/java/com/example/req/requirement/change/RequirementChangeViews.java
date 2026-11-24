package com.example.req.requirement.change;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 变更单的稳定对外视图：与实体分离，列表行不带 before/after 大 JSON。
 *
 * <p>{@code created/impact} 是创建时冻结的快照；{@code detail} 里的 {@code canApply}
 * 由服务端算（状态 + 动作权限），前端不再复刻双依赖。</p>
 *
 * <p>快照 Map 显式 {@code ALWAYS}（含 content）：全局 Jackson 是
 * {@code default-property-inclusion: non_null}，而 AC-3 的契约是<b>固定键集</b>——
 * 7 个 before/after 键、8 个 impact 键即使值为 {@code null} 也必须出现。只在本文件局部
 * 覆盖，避免改全局默认影响其它接口。</p>
 */
public final class RequirementChangeViews {

    private RequirementChangeViews() {
    }

    /**
     * 列表行；{@code fields} 是这次变更的真实变化字段（规范序，由已冻结的 before/after 就地算），
     * 空快照的历史行降级为 {@code []} 而不是省略键——前端「—」与 200 都是契约的一部分。
     */
    public record Row(long id, String changeType, String status, String reason,
                      Long createdBy, LocalDateTime createdAt, LocalDateTime appliedAt,
                      List<String> fields, Map<String, Object> impactSummary) {
    }

    public record RequirementRef(long id, String code, String title, String type, String status) {
    }

    public record Item(long changeId, String field, String before, String after) {
    }

    public record Detail(long id, RequirementRef requirement, String changeType, String status,
                         String reason,
                         @JsonInclude(value = JsonInclude.Include.ALWAYS,
                                 content = JsonInclude.Include.ALWAYS)
                         Map<String, Object> before,
                         @JsonInclude(value = JsonInclude.Include.ALWAYS,
                                 content = JsonInclude.Include.ALWAYS)
                         Map<String, Object> after,
                         List<Item> items,
                         @JsonInclude(value = JsonInclude.Include.ALWAYS,
                                 content = JsonInclude.Include.ALWAYS)
                         Map<String, Object> impact, Long instanceId,
                         LocalDateTime appliedAt, Long appliedBy, Long createdBy,
                         LocalDateTime createdAt, boolean canApply) {
    }

    public record Created(long id, String status, Long instanceId,
                          @JsonInclude(value = JsonInclude.Include.ALWAYS,
                                  content = JsonInclude.Include.ALWAYS)
                          Map<String, Object> impact) {
    }

    public record Preview(String changeType,
                          @JsonInclude(value = JsonInclude.Include.ALWAYS,
                                  content = JsonInclude.Include.ALWAYS)
                          Map<String, Object> before,
                          @JsonInclude(value = JsonInclude.Include.ALWAYS,
                                  content = JsonInclude.Include.ALWAYS)
                          Map<String, Object> after,
                          List<String> fields,
                          @JsonInclude(value = JsonInclude.Include.ALWAYS,
                                  content = JsonInclude.Include.ALWAYS)
                          Map<String, Object> impact) {
    }
}
