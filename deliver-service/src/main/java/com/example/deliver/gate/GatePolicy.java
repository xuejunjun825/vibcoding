package com.example.deliver.gate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.example.platform.contract.GateBlocker;
import com.example.platform.contract.GateDecision;

/**
 * The gate rule itself, with no database and no HTTP.
 *
 * <p>STRICT blocks on any non-PASS result: a case that was never executed is exactly as
 * untrustworthy as a failed one. LOOSE blocks only on FAIL, and tolerates BLOCKED/SKIP but only
 * when the executor wrote down why — an unexplained skip is a silent pass, which is the failure
 * mode the loose mode exists to avoid.</p>
 *
 * <p>This class is the <em>only</em> place that decision is made. The endpoint, the requirement
 * transition (through {@code GatePort}) and release admission all read its verdict.</p>
 */
public final class GatePolicy {

    public static final String STRICT = "STRICT";
    public static final String LOOSE = "LOOSE";

    private GatePolicy() {
    }

    /** A plan with no cases can never satisfy the gate, in either mode. */
    public static GateDecision emptyPlan(String mode) {
        String resolved = normaliseMode(mode);
        return GateDecision.block(resolved, "测试计划内没有任何用例，不满足门禁",
                List.of(new GateBlocker("NO_CASES", "测试计划未纳入任何用例",
                        "在测试计划中纳入本次需求相关的用例后再提交测试")));
    }

    /**
     * Evaluate the planned cases.
     *
     * @param mode  STRICT or LOOSE (unknown values fall back to STRICT rather than silently
     *              becoming permissive)
     * @param cases plan rows for this requirement; empty means "nothing to check"
     */
    public static GateDecision evaluate(String mode, List<GateCase> cases) {
        String resolved = normaliseMode(mode);
        if (cases == null || cases.isEmpty()) {
            return emptyPlan(resolved);
        }
        List<GateBlocker> blockers = new ArrayList<>();
        for (GateCase item : cases) {
            String result = item.result();
            if ("PASS".equals(result)) {
                continue;
            }
            if (LOOSE.equals(resolved)) {
                if ("FAIL".equals(result)) {
                    blockers.add(blocker("CASE_FAILED", item,
                            "修复失败用例后重新执行：" + label(item)));
                } else if (("BLOCKED".equals(result) || "SKIP".equals(result))
                        && isBlank(item.note())) {
                    blockers.add(blocker(reasonCode(result), item,
                            "宽松模式下阻塞/跳过必须填写原因：" + label(item)));
                }
                continue;
            }
            switch (result) {
                case "FAIL" -> blockers.add(blocker("CASE_FAILED", item,
                        "修复失败用例后重新执行：" + label(item)));
                case "BLOCKED" -> blockers.add(blocker("CASE_BLOCKED", item,
                        "解除阻塞后重新执行：" + label(item)));
                case "SKIP" -> blockers.add(blocker("CASE_SKIPPED", item,
                        "跳过不被严格模式接受，请执行该用例：" + label(item)));
                default -> blockers.add(blocker("CASE_NOT_RUN", item,
                        "执行该用例后再提交测试：" + label(item)));
            }
        }
        if (blockers.isEmpty()) {
            return GateDecision.pass(resolved, "计划内 " + cases.size() + " 条用例全部通过");
        }
        return GateDecision.block(resolved,
                blockers.size() + " 条用例未通过门禁（计划内共 " + cases.size() + " 条）", blockers);
    }

    public static String normaliseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return STRICT;
        }
        String value = mode.trim().toUpperCase(Locale.ROOT);
        return LOOSE.equals(value) ? LOOSE : STRICT;
    }

    private static String reasonCode(String result) {
        return "BLOCKED".equals(result) ? "CASE_BLOCKED_NO_REASON" : "CASE_SKIPPED_NO_REASON";
    }

    private static GateBlocker blocker(String code, GateCase item, String fix) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("caseId", item.caseId());
        detail.put("caseCode", item.caseCode());
        detail.put("caseVersion", item.caseVersion());
        detail.put("result", item.result());
        if (item.note() != null && !item.note().isBlank()) {
            detail.put("note", item.note());
        }
        if (item.requirementId() != null) {
            detail.put("requirementId", item.requirementId());
        }
        detail.put("planId", item.planId());
        return new GateBlocker(code, resultText(item.result()) + "：" + label(item), fix, detail);
    }

    private static String label(GateCase item) {
        String code = item.caseCode() == null || item.caseCode().isBlank()
                ? "用例 " + item.caseId()
                : item.caseCode();
        return code + " · v" + item.caseVersion();
    }

    private static String resultText(String result) {
        return switch (result) {
            case "PASS" -> "通过";
            case "FAIL" -> "失败";
            case "BLOCKED" -> "阻塞";
            case "SKIP" -> "跳过";
            default -> "未执行";
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
