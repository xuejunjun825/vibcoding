package com.example.deliver.metric;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.deliver.defect.DefectBoardQuery;
import com.example.deliver.defect.DefectViews.BoardMetrics;
import com.example.deliver.defect.DefectViews.DefectFilter;
import com.example.deliver.metric.MetricViews.DefinitionCmd;
import com.example.deliver.metric.MetricViews.DefinitionView;
import com.example.deliver.metric.MetricViews.DefectMetrics;
import com.example.deliver.metric.MetricViews.PlanMetrics;
import com.example.deliver.support.Rows;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.MetricFormulas;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Metric reads: raw counts from SQL, every ratio from {@link MetricFormulas}.
 *
 * <p>No multiplication or division happens in this class — the formula lives in the contract
 * module so the board, the requirement pool and the release report cannot disagree about what
 * "通过率" means. Definitions are versioned rows; the current one is the highest version.</p>
 */
@Component
public class MetricQuery {

    private static final List<String[]> DEFAULTS = List.of(
            new String[]{"DEFECT_DENSITY", "缺陷密度", "DEFECT",
                    "缺陷总数 / 需求总数", "defect, requirement", "需求数取当前数据范围内可见需求"},
            new String[]{"REOPEN_RATE", "缺陷重开率", "DEFECT",
                    "重开过的缺陷数 / 缺陷总数", "defect", "reopen_count > 0 计一次，不按重开次数累加"},
            new String[]{"TEST_PASS_RATE", "测试通过率", "TEST",
                    "通过用例数 / 计划用例数", "test_plan_case", "未执行计入分母（严格口径）"},
            new String[]{"MTTR", "平均修复时长(分钟)", "DEFECT",
                    "AVG(closed_at - found_at) / 60", "defect", "仅统计已关闭缺陷"});

    private final DefectBoardQuery board;
    private final ScopedJdbc jdbc;
    private final MetricDefinitionRepository definitions;

    public MetricQuery(DefectBoardQuery board, ScopedJdbc jdbc, MetricDefinitionRepository definitions) {
        this.board = board;
        this.jdbc = jdbc;
        this.definitions = definitions;
    }

    /** The defect topic: same predicate as the cards and their drill-down. */
    public DefectMetrics defects(DefectFilter filter) {
        BoardMetrics metrics = board.metrics(filter);
        return new DefectMetrics(metrics.total(), metrics.openHigh(), metrics.reopened(),
                metrics.closed(), metrics.requirements(), metrics.density(),
                metrics.reopenRate(), metrics.mttrMinutes());
    }

    /** Test-execution topic for one plan, straight from the pinned plan rows. */
    public PlanMetrics plan(long planId) {
        List<Map<String, Object>> rows = jdbc.list("""
                SELECT COUNT(*) AS planned,
                       SUM(CASE WHEN p.result = 'PASS' THEN 1 ELSE 0 END) AS passed,
                       SUM(CASE WHEN p.result = 'FAIL' THEN 1 ELSE 0 END) AS failed,
                       SUM(CASE WHEN p.result = 'BLOCKED' THEN 1 ELSE 0 END) AS blocked,
                       SUM(CASE WHEN p.result = 'NOT_RUN' THEN 1 ELSE 0 END) AS not_run,
                       SUM(CASE WHEN p.execution_id IS NOT NULL THEN 1 ELSE 0 END) AS executed
                FROM test_plan_case p
                WHERE p.plan_id = :planId AND /*scope:p*/
                """, Map.of("planId", planId));
        Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);
        long planned = Rows.toLong(Rows.object(row, "planned"), 0L);
        long passed = Rows.toLong(Rows.object(row, "passed"), 0L);
        long failed = Rows.toLong(Rows.object(row, "failed"), 0L);
        long blocked = Rows.toLong(Rows.object(row, "blocked"), 0L);
        long notRun = Rows.toLong(Rows.object(row, "not_run"), 0L);
        long executed = Rows.toLong(Rows.object(row, "executed"), 0L);
        return new PlanMetrics(planId, planned, executed, passed, failed, blocked, notRun,
                MetricFormulas.passRate(passed, planned),
                MetricFormulas.executedPassRate(passed, executed),
                MetricFormulas.coverage(executed, planned));
    }

    /** Current definitions (or every version), seeding the four defaults on first access. */
    @Transactional
    public List<DefinitionView> definitions(boolean allVersions) {
        long tenantId = TenantContext.require().tenantId();
        ensureDefaults(tenantId);
        List<MetricDefinitionEntity> rows = definitions.findByTenantIdOrderByCodeAscVersionAsc(tenantId);
        Map<String, DefinitionView> latest = new LinkedHashMap<>();
        List<DefinitionView> all = new ArrayList<>();
        for (MetricDefinitionEntity row : rows) {
            DefinitionView view = toView(row);
            all.add(view);
            latest.put(row.getCode(), view);
        }
        return allVersions ? all : new ArrayList<>(latest.values());
    }

    @Transactional
    public DefinitionView publish(DefinitionCmd cmd) {
        long tenantId = TenantContext.require().tenantId();
        long userId = TenantContext.require().userId();
        if (cmd.code() == null || cmd.code().isBlank() || cmd.formula() == null || cmd.formula().isBlank()) {
            throw new ApiException(400, "VALIDATION_FAILED", "code 与 formula 不能为空", null);
        }
        int nextVersion = definitions.findFirstByTenantIdAndCodeOrderByVersionDesc(
                        tenantId, cmd.code().trim()).map(row -> row.getVersion() + 1).orElse(1);
        MetricDefinitionEntity entity = new MetricDefinitionEntity();
        entity.setTenantId(tenantId);
        entity.setCode(cmd.code().trim());
        entity.setName(cmd.name() == null ? cmd.code().trim() : cmd.name().trim());
        entity.setTopic(cmd.topic() == null ? "DEFECT" : cmd.topic().trim());
        entity.setFormula(cmd.formula().trim());
        entity.setDatasource(cmd.datasource());
        entity.setCaliberNote(cmd.caliberNote());
        entity.setOwnerId(userId);
        entity.setVersion(nextVersion);
        entity.setEffectiveAt(LocalDateTime.now());
        entity.setCreatedBy(userId);
        entity.setUpdatedBy(userId);
        definitions.save(entity);
        return toView(entity);
    }

    /** Idempotent: a tenant with no dictionary gets the four topic defaults exactly once. */
    private void ensureDefaults(long tenantId) {
        for (String[] row : DEFAULTS) {
            if (definitions.findFirstByTenantIdAndCodeOrderByVersionDesc(tenantId, row[0]).isPresent()) {
                continue;
            }
            MetricDefinitionEntity entity = new MetricDefinitionEntity();
            entity.setTenantId(tenantId);
            entity.setCode(row[0]);
            entity.setName(row[1]);
            entity.setTopic(row[2]);
            entity.setFormula(row[3]);
            entity.setDatasource(row[4]);
            entity.setCaliberNote(row[5]);
            entity.setVersion(1);
            entity.setEffectiveAt(LocalDateTime.now());
            try {
                definitions.save(entity);
            } catch (DataIntegrityViolationException concurrent) {
                // Another request seeded the same row first; the unique key did its job.
            }
        }
    }

    private DefinitionView toView(MetricDefinitionEntity entity) {
        return new DefinitionView(entity.getId(), entity.getCode(), entity.getName(), entity.getTopic(),
                entity.getFormula(), entity.getDatasource(), entity.getCaliberNote(),
                entity.getVersion(), entity.getEffectiveAt());
    }

    /** Definition rows as board payload (id/code/name/formula/version only). */
    public List<Map<String, Object>> definitionsForBoard() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (DefinitionView view : definitions(false)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", view.code());
            row.put("name", view.name());
            row.put("formula", view.formula());
            row.put("version", view.version());
            items.add(row);
        }
        return items;
    }

    static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(MetricFormulas.SCALE, java.math.RoundingMode.HALF_UP);
    }
}
