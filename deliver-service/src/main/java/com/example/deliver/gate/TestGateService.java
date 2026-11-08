package com.example.deliver.gate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.deliver.reqread.ReqReadModel;
import com.example.deliver.reqread.RequirementSnapshot;
import com.example.deliver.support.Rows;
import com.example.platform.common.error.ApiException;
import com.example.platform.common.scope.ScopedJdbc;
import com.example.platform.common.tenant.TenantContext;
import com.example.platform.contract.DataScope;
import com.example.platform.contract.GateDecision;
import com.example.platform.contract.GatePort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single implementation of the test gate (AC-16).
 *
 * <p>Everything that needs a gate verdict comes through here: the {@code /test-gate} endpoint the
 * UI greys the button with, the requirement transition in req-service (via {@code GatePort} and
 * the internal endpoints), and release admission's batch call. A second implementation in
 * req-service is exactly how "the button was blocked but the API let it through" happens, so
 * there is none.</p>
 *
 * <p>Scope rule: the requirement is read through {@link ReqReadModel} (vendor-scoped), and the
 * plan rows are read with the {@code test_plan} scope marker, so a vendor never sees another
 * vendor's plan results.</p>
 */
@Service
public class TestGateService implements GatePort {

    private static final String PASS_NO_RELEASE =
            "需求未关联版本，无测试计划，按快速测试记录放行";
    private static final String PASS_NO_PLAN =
            "未找到该需求的测试计划，按快速测试记录放行";

    private final ReqReadModel requirements;
    private final ScopedJdbc jdbc;

    public TestGateService(ReqReadModel requirements, ScopedJdbc jdbc) {
        this.requirements = requirements;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public GateDecision evaluate(long requirementId) {
        TenantContext.require();
        RequirementSnapshot requirement = requirements.get(requirementId);
        if (requirement == null) {
            throw new ApiException(404, "REQUIREMENT_NOT_FOUND",
                    "需求不存在或不在你的数据范围内", Map.of("requirementId", requirementId));
        }
        Long releaseId = requirement.releaseId();
        if (releaseId == null) {
            return GateDecision.pass(GatePolicy.STRICT, PASS_NO_RELEASE);
        }
        String mode = releaseMode(releaseId);
        if (plans(releaseId, requirementId).isEmpty()) {
            return GateDecision.pass(mode, PASS_NO_PLAN);
        }
        List<GateCase> cases = cases(releaseId, requirementId);
        if (cases.isEmpty()) {
            return GatePolicy.emptyPlan(mode);
        }
        return GatePolicy.evaluate(mode, cases);
    }

    /**
     * Batch evaluation for release admission: one round trip for all requirements of a release.
     *
     * <p>Per-requirement queries are intentional — the N+1 cost is bounded by the requirements of
     * one release and the alternative (one giant join) is where gate and matrix drift apart.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public Map<Long, GateDecision> evaluateAll(Collection<Long> requirementIds) {
        Map<Long, GateDecision> decisions = new LinkedHashMap<>();
        if (requirementIds == null) {
            return decisions;
        }
        for (Long id : requirementIds) {
            if (id == null) {
                continue;
            }
            decisions.put(id, evaluate(id));
        }
        return decisions;
    }

    // ------------------------------------------------------------------ queries

    private String releaseMode(long releaseId) {
        Map<String, Object> row = jdbc.one("""
                SELECT COALESCE(rel.gate_mode, 'STRICT') AS gate_mode
                FROM `release` rel
                WHERE rel.id = :releaseId AND /*scope:rel*/
                """, Map.of("releaseId", releaseId));
        String mode = row == null ? null : Rows.str(row, "gate_mode");
        return GatePolicy.normaliseMode(mode);
    }

    private List<Map<String, Object>> plans(long releaseId, long requirementId) {
        return jdbc.list("""
                SELECT p.id AS plan_id, COALESCE(p.gate_mode, '') AS plan_gate_mode
                FROM test_plan p
                WHERE /*scope:p*/
                  AND p.release_id = :releaseId
                  AND EXISTS (SELECT 1 FROM test_plan_case pc
                              WHERE pc.plan_id = p.id AND pc.requirement_id = :requirementId)
                ORDER BY p.id
                """, Map.of("releaseId", releaseId, "requirementId", requirementId));
    }

    private List<GateCase> cases(long releaseId, long requirementId) {
        List<Map<String, Object>> rows = jdbc.list("""
                SELECT pc.case_id AS case_id, c.code AS case_code,
                       pc.case_version AS case_version, pc.result AS result,
                       pc.note AS note, pc.requirement_id AS requirement_id,
                       pc.plan_id AS plan_id
                FROM test_plan_case pc
                JOIN test_plan p ON p.id = pc.plan_id
                LEFT JOIN test_case c ON c.id = pc.case_id
                WHERE /*scope:p*/
                  AND p.release_id = :releaseId
                  AND pc.requirement_id = :requirementId
                ORDER BY c.code, pc.case_id
                """, Map.of("releaseId", releaseId, "requirementId", requirementId));
        List<GateCase> cases = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            cases.add(new GateCase(
                    Rows.toLong(Rows.object(row, "case_id"), 0L),
                    Rows.str(row, "case_code"),
                    Rows.toInt(Rows.object(row, "case_version"), 0),
                    Rows.str(row, "result"),
                    Rows.str(row, "note"),
                    Rows.toLong(Rows.object(row, "requirement_id"), 0L),
                    Rows.toLong(Rows.object(row, "plan_id"), 0L)));
        }
        return cases;
    }
}
