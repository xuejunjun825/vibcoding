package com.example.req.integration.sonar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.req.integration.adapter.AdapterRegistry;
import com.example.req.integration.config.IntegrationSystem;
import com.example.req.requirement.RequirementEntity;
import com.example.req.requirement.RequirementService;
import com.example.req.requirement.Rows;
import com.example.req.release.ReleaseEntity;
import com.example.req.release.ReleaseService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 代码质量只读快照（AC-13 / TECHNICAL §2.7）。
 *
 * <p>这个类<b>不发任何外呼</b>，也不刷新快照：它只读 {@code quality_gate_snapshot} 中
 * {@code source='SONAR'} 的最新一行。页面打开秒开、桩计数为 0 都由这一条保证；
 * 需要新数据时调 M7 已有的 {@code POST /integration/sonar/{id}/sync}。</p>
 *
 * <p>两项「不补 0」的具体实现：</p>
 * <ul>
 *   <li>没有快照 → 返回 {@code card=null} + {@code emptyReason}，绝不构造零值卡。</li>
 *   <li>有快照但缺某个度量 → 该键<b>不出现在</b> {@code metrics} 里（前端渲染 —）；
 *       只有 Sonar 真的报了 0，才显示 0。</li>
 * </ul>
 */
@Service
public class CodeQualityService {

    /** 需求最新快照（按 id 而不是 computed_at：同一秒写入也稳定）。 */
    private static final String LATEST_SQL = """
            SELECT id, project_key, result, metric_status, metrics_json, computed_at
              FROM quality_gate_snapshot
             WHERE tenant_id = ? AND source = 'SONAR' AND requirement_id = ?
             ORDER BY id DESC
             LIMIT 1
            """;

    private final JdbcTemplate jdbc;
    private final ScopedJdbc scoped;
    private final RequirementService requirementService;
    private final ReleaseService releaseService;
    private final AdapterRegistry adapterRegistry;
    private final ObjectMapper objectMapper;

    public CodeQualityService(JdbcTemplate jdbc,
                              ScopedJdbc scoped,
                              RequirementService requirementService,
                              ReleaseService releaseService,
                              AdapterRegistry adapterRegistry,
                              ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.scoped = scoped;
        this.requirementService = requirementService;
        this.releaseService = releaseService;
        this.adapterRegistry = adapterRegistry;
        this.objectMapper = objectMapper;
    }

    /** 需求详情的代码质量卡；跨域需求由 {@code requireVisible} 抛 404。 */
    @Transactional(readOnly = true)
    public CodeQualityViews.RequirementCard requirement(long requirementId) {
        RequirementEntity requirement = requirementService.requireVisible(requirementId);
        long tenantId = tenant(requirement);
        Map<String, Object> row = latestRow(tenantId, requirementId);
        if (row == null) {
            return new CodeQualityViews.RequirementCard(null, emptyReason(tenantId));
        }
        return new CodeQualityViews.RequirementCard(card(row), null);
    }

    /**
     * 版本质量：版本下每个需求的最新快照，按 {@code project_key} 去重保留最新一条。
     * 同项目多个需求各自有快照时只出现一行，并标明数据来自哪个需求。
     */
    @Transactional(readOnly = true)
    public CodeQualityViews.ReleaseCard release(long releaseId) {
        ReleaseEntity release = releaseService.requireVisible(releaseId);
        long tenantId = tenant(release);
        List<Map<String, Object>> requirements = scoped.list("""
                SELECT r.id, r.code
                  FROM requirement r
                  JOIN release_requirement rr ON rr.requirement_id = r.id
                 WHERE /*scope:r*/ AND rr.release_id = :releaseId
                 ORDER BY r.id
                """, Map.of("releaseId", releaseId));
        Map<String, CodeQualityViews.ProjectCard> byProject = new LinkedHashMap<>();
        for (Map<String, Object> requirement : requirements) {
            long requirementId = Rows.toLong(Rows.object(requirement, "id"), 0L);
            if (requirementId <= 0) {
                continue;
            }
            Map<String, Object> row = latestRow(tenantId, requirementId);
            if (row == null) {
                continue;
            }
            CodeQualityViews.QualityCard card = card(row);
            String key = card.projectKey() == null || card.projectKey().isBlank()
                    ? "requirement:" + requirementId
                    : card.projectKey();
            CodeQualityViews.ProjectCard candidate = new CodeQualityViews.ProjectCard(
                    card.projectKey(), requirementId, Rows.str(requirement, "code"), card);
            CodeQualityViews.ProjectCard existing = byProject.get(key);
            if (existing == null || newer(candidate, existing)) {
                byProject.put(key, candidate);
            }
        }
        List<CodeQualityViews.ProjectCard> projects = new ArrayList<>(byProject.values());
        return new CodeQualityViews.ReleaseCard(projects,
                projects.isEmpty() ? emptyReason(tenantId) : null);
    }

    private static boolean newer(CodeQualityViews.ProjectCard candidate,
                                 CodeQualityViews.ProjectCard existing) {
        Long left = candidate.card().snapshotId();
        Long right = existing.card().snapshotId();
        return left != null && (right == null || left > right);
    }

    private Map<String, Object> latestRow(long tenantId, long requirementId) {
        List<Map<String, Object>> rows = jdbc.queryForList(LATEST_SQL, tenantId, requirementId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private CodeQualityViews.QualityCard card(Map<String, Object> row) {
        Map<String, String> raw = metrics(Rows.str(row, "metrics_json"));
        Map<String, String> five = new LinkedHashMap<>();
        for (String key : SonarThresholds.KEYS) {
            String value = raw.get(key);
            if (value != null) {
                five.put(key, value);
            }
        }
        return new CodeQualityViews.QualityCard(
                Rows.str(row, "project_key"),
                Rows.str(row, "result"),
                five,
                SonarThresholds.cardThresholds(five),
                Rows.toLong(Rows.object(row, "id")),
                Rows.toDateTime(Rows.object(row, "computed_at")));
    }

    private Map<String, String> metrics(String metricsJson) {
        if (metricsJson == null || metricsJson.isBlank()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        try {
            JsonNode node = objectMapper.readTree(metricsJson);
            if (node == null || !node.isObject()) {
                return Map.of();
            }
            node.properties().forEach(entry -> {
                JsonNode value = entry.getValue();
                if (value != null && !value.isNull()) {
                    result.put(entry.getKey(), value.isString() ? value.asText() : value.toString());
                }
            });
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 有启用的 Sonar 才算「配置好了但没同步」，否则空态直接说「集成未启用」。 */
    private String emptyReason(long tenantId) {
        boolean enabled = adapterRegistry.findEnabled(tenantId, IntegrationSystem.SONAR).isPresent();
        if (enabled) {
            return CodeQualityViews.EMPTY_NO_SNAPSHOT;
        }
        return CodeQualityViews.EMPTY_INTEGRATION_DISABLED;
    }

    private long tenant(RequirementEntity requirement) {
        Long tenantId = requirement.getTenantId();
        return tenantId == null ? TenantContext.require().tenantId() : tenantId;
    }

    private long tenant(ReleaseEntity release) {
        Long tenantId = release.getTenantId();
        return tenantId == null ? TenantContext.require().tenantId() : tenantId;
    }
}
