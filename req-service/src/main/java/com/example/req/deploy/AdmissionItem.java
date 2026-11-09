package com.example.req.deploy;

import com.example.req.integration.admission.AdmissionItemPolicy;
import com.example.req.integration.admission.AdmissionViews;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 准入清单的一项：{@code code} 是稳定断言键，{@code name} 给人看，
 * {@code fixHint} 是"怎么改"（前端常驻清单左侧红条直接展示，409 里也必须带上）。
 *
 * <p>M7 起为四态（{@link #PASS}/{@link #FAIL}/{@link #SKIP}/{@link #CLOSED}）：
 * {@code passed} 仍然保留，但它<b>只是 {@code status} 的派生值</b>（{@code passed == (status == PASS)}），
 * 历史快照 JSON 没有 {@code status} 字段时按 {@code passed} 反推，保证老单据仍能反序列化。</p>
 *
 * <p>顺序与文案的唯一来源是 {@link AdmissionItemPolicy}（同名常量与 {@link #ORDER} 都委托给它），
 * 本类只做"带状态的清单项"这一件事，不再自己维护第三份名字表。</p>
 */
public record AdmissionItem(String code, String name, int order, String status, boolean passed,
                            String detail, String fixHint, String reasonCode, String evidenceHash,
                            Map<String, Object> evidence, boolean forced) {

    public static final String PASS = AdmissionViews.PASS;
    public static final String FAIL = AdmissionViews.FAIL;
    public static final String SKIP = AdmissionViews.SKIP;
    public static final String CLOSED = AdmissionViews.CLOSED;

    public static final String REQ_TEST_PASSED = AdmissionItemPolicy.REQ_TEST_PASSED;
    public static final String NO_OPEN_HIGH_DEFECT = AdmissionItemPolicy.NO_OPEN_HIGH_DEFECT;
    public static final String REQ_APPROVALS_DONE = AdmissionItemPolicy.REQ_APPROVALS_DONE;
    public static final String MR_FILLED = AdmissionItemPolicy.MR_FILLED;
    public static final String MAN_DAYS_FILLED = AdmissionItemPolicy.MAN_DAYS_FILLED;
    /** M6 的第 6 项：发布质量门（deliver 侧求值，req 只透传结论，不重算规则）。 */
    public static final String QUALITY_GATE = AdmissionItemPolicy.QUALITY_GATE;
    /** M7 追加：⑦ 构建、⑧ Sonar 门禁、⑨ 制品校验。 */
    public static final String BUILD_PASSED = AdmissionItemPolicy.BUILD_PASSED;
    public static final String SONAR_GATE_PASSED = AdmissionItemPolicy.SONAR_GATE_PASSED;
    public static final String ARTIFACT_VERIFIED = AdmissionItemPolicy.ARTIFACT_VERIFIED;

    /** 九项固定顺序（PRD AC-8.1）；前端不排序、不隐藏任何一项。 */
    public static final java.util.List<String> ORDER = AdmissionItemPolicy.order();

    public AdmissionItem {
        if (status == null || status.isBlank()) {
            status = passed ? PASS : FAIL;
        }
        // 不变量：passed 永远由 status 派生，反序列化出一条自相矛盾的旧数据也不会让
        // "跳过/关闭"被算成通过（AC-8.3）。
        passed = PASS.equals(status);
        if (order <= 0) {
            order = AdmissionItemPolicy.orderOf(code);
        }
        if (name == null || name.isBlank()) {
            name = AdmissionItemPolicy.nameOf(code);
        }
        evidence = evidence == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
    }

    public static AdmissionItem pass(String code, String name, String detail) {
        return new AdmissionItem(code, name, 0, PASS, true, detail, null, null, null, Map.of(), false);
    }

    public static AdmissionItem fail(String code, String name, String detail, String fixHint) {
        return new AdmissionItem(code, name, 0, FAIL, false, detail, fixHint, null, null, Map.of(), false);
    }

    /** 由 ⑦⑧⑨ 的本地证据生成清单项（README 里的四态语义在这里落地）。 */
    public static AdmissionItem of(String code, AdmissionViews.Evidence evidence, String fixHint) {
        if (evidence == null) {
            return fail(code, null, "证据读取失败（EVIDENCE_UNAVAILABLE）",
                    fixHint == null ? "稍后重新校验；持续失败请检查 req-service 日志" : fixHint);
        }
        Map<String, Object> facts = new LinkedHashMap<>(evidence.facts() == null ? Map.of() : evidence.facts());
        facts.put("reasonCode", evidence.reasonCode());
        facts.put("hash", evidence.hash());
        return new AdmissionItem(code, null, 0, evidence.status(), evidence.passed(), evidence.detail(),
                evidence.passed() || CLOSED.equals(evidence.status()) ? null : fixHint,
                evidence.reasonCode(), evidence.hash(), facts, false);
    }

    /** 项开关关闭：不参与判定、不阻断、不计入分母（{@code active = 9 - closed}）。 */
    public static AdmissionItem closed(String code, String name, String detail, String reasonCode) {
        return new AdmissionItem(code, name, 0, CLOSED, false, detail, null, reasonCode, null, Map.of(), false);
    }

    public static AdmissionItem skip(String code, String name, String detail, String reasonCode) {
        return new AdmissionItem(code, name, 0, SKIP, false, detail, null, reasonCode, null, Map.of(), false);
    }

    public AdmissionItem withForced(boolean value) {
        return new AdmissionItem(code, name, order, status, passed, detail, fixHint, reasonCode, evidenceHash,
                evidence, value);
    }

    /** 是否参与分母：CLOSED 不计入 {@code active}。 */
    public boolean active() {
        return !CLOSED.equals(status);
    }

    public static String nameOf(String code) {
        return AdmissionItemPolicy.nameOf(code);
    }
}
