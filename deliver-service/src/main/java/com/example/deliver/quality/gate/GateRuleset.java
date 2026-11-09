package com.example.deliver.quality.gate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * The five quality rules' static identity: code, name, waivability, default threshold.
 *
 * <p>What lives here is the part that can never be stored per release — the code, the human name,
 * whether a waiver can cover the rule, and the threshold JSON used when a release has no
 * configuration row yet ({@code GET /quality-gates/{releaseId}} must answer without writing). What
 * a release overrides (enabled, threshold) lives in {@code quality_gate_rule}; the evaluation
 * itself lives in {@link GateEvaluator}, so this class never decides pass/fail.</p>
 *
 * <p>Waivability is policy, not configuration: QG-04 (open P0/P1) is the PRD's red line
 * (AC-6.2), so it cannot be waived <em>or</em> disabled. QG-01/QG-02 are waivable because AC-5.4
 * (a {@code CONDITIONAL} conclusion proceeding on an approved waiver) and AC-6.4 (QG-03 still
 * failing while QG-01 is waived) both require it — that is a deliberate reading of the acceptance
 * criteria over the older "QG-01/02 not waivable" table in TECHNICAL, recorded in the dev summary.</p>
 */
@Component
public class GateRuleset {

    public static final String QG_01 = "QG-01";
    public static final String QG_02 = "QG-02";
    public static final String QG_03 = "QG-03";
    public static final String QG_04 = "QG-04";
    public static final String QG_05 = "QG-05";

    /** One rule's immutable identity plus the seed threshold used before a release configures it. */
    public record RuleDef(String code,
                          String name,
                          boolean waivable,
                          boolean defaultEnabled,
                          String defaultJson,
                          String fixHint) {
    }

    private static final List<RuleDef> RULES = List.of(
            new RuleDef(QG_01, "需求覆盖与已会签报告", true, true,
                    "{\"coveragePercent\":100,\"requireSignedReport\":true}",
                    "为每条发布需求关联计划用例，并完成 RELEASE 报告三级会签"),
            new RuleDef(QG_02, "报告有效性与数据新鲜度", true, true,
                    "{\"maxAgeDays\":7,\"conclusions\":[\"PASS\",\"CONDITIONAL\"]}",
                    "重新生成并会签测试报告，使结论、有效期与执行数据一致"),
            new RuleDef(QG_03, "测试通过率", true, true,
                    "{\"minPassRate\":95,\"strictRequiresAll\":true}",
                    "修复失败用例并重跑，或按流程申请 QG-03 豁免"),
            new RuleDef(QG_04, "未关闭 P0/P1", false, true,
                    "{\"maxOpenP0P1\":0}",
                    "关闭或降级未关闭的 P0/P1 缺陷（硬红线，不可豁免）"),
            new RuleDef(QG_05, "外部质量数据源", true, false,
                    "{\"sources\":[\"SONAR\",\"ARTIFACT\",\"BUILD\"]}",
                    "在外部数据接入后启用，或保持关闭"));

    private final ObjectMapper json;

    public GateRuleset(ObjectMapper qualityObjectMapper) {
        this.json = qualityObjectMapper;
    }

    public List<RuleDef> rules() {
        return RULES;
    }

    public List<String> codes() {
        List<String> codes = new ArrayList<>(RULES.size());
        for (RuleDef rule : RULES) {
            codes.add(rule.code());
        }
        return codes;
    }

    /** {@code null} for an unknown code — callers turn that into 400 rather than guessing a rule. */
    public RuleDef find(String code) {
        if (code == null) {
            return null;
        }
        for (RuleDef rule : RULES) {
            if (rule.code().equalsIgnoreCase(code.trim())) {
                return rule;
            }
        }
        return null;
    }

    public boolean waivable(String code) {
        RuleDef rule = find(code);
        return rule != null && rule.waivable();
    }

    public String name(String code) {
        RuleDef rule = find(code);
        return rule == null ? code : rule.name();
    }

    public String fixHint(String code) {
        RuleDef rule = find(code);
        return rule == null ? "" : rule.fixHint();
    }

    /** Default threshold JSON for a rule (used when the release has no {@code quality_gate_rule} row). */
    public String defaultJson(String code) {
        RuleDef rule = find(code);
        return rule == null ? "{}" : rule.defaultJson();
    }

    /** The default config for a release that has never been configured — never persisted. */
    public List<RuleConfig> defaults() {
        List<RuleConfig> configs = new ArrayList<>(RULES.size());
        for (RuleDef rule : RULES) {
            configs.add(new RuleConfig(rule.code(), rule.defaultEnabled(), rule.defaultJson()));
        }
        return configs;
    }

    // ------------------------------------------------------------------ threshold JSON

    /** One rule's effective configuration. */
    public record RuleConfig(String code, boolean enabled, String thresholdJson) {
    }

    public int intOf(String thresholdJson, String key, int fallback) {
        Object value = value(thresholdJson, key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public boolean boolOf(String thresholdJson, String key, boolean fallback) {
        Object value = value(thresholdJson, key);
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text.trim());
        }
        return fallback;
    }

    public List<String> stringsOf(String thresholdJson, String key, List<String> fallback) {
        Object value = value(thresholdJson, key);
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object element : list) {
                if (element != null) {
                    out.add(String.valueOf(element));
                }
            }
            return out.isEmpty() ? fallback : out;
        }
        return fallback;
    }

    /** Human threshold text for the config view — one wording per rule, no page-local strings. */
    public String thresholdText(String code, String thresholdJson) {
        return switch (code == null ? "" : code) {
            case QG_01 -> "覆盖 ≥ " + intOf(thresholdJson, "coveragePercent", 100) + "% 且存在已会签 RELEASE 报告";
            case QG_02 -> "签署 ≤ " + intOf(thresholdJson, "maxAgeDays", 7)
                    + " 天内 · 结论 PASS（CONDITIONAL 需已批豁免）· 与执行数据一致";
            case QG_03 -> "≥ " + intOf(thresholdJson, "minPassRate", 95) + "%（STRICT 版本需 100%）";
            case QG_04 -> "0 个未关闭 P0/P1";
            case QG_05 -> "外部数据源（" + String.join("/", stringsOf(thresholdJson, "sources",
                    List.of("SONAR", "ARTIFACT", "BUILD"))) + "）";
            default -> "—";
        };
    }

    private Object value(String thresholdJson, String key) {
        if (thresholdJson == null || thresholdJson.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> parsed = json.readValue(thresholdJson,
                    new TypeReference<Map<String, Object>>() {
                    });
            return parsed.get(key);
        } catch (Exception ex) {
            // A malformed threshold column must not 500 the gate page: fall back to defaults.
            return null;
        }
    }
}
