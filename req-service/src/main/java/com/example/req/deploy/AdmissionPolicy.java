package com.example.req.deploy;

import com.example.platform.contract.GateBlocker;
import com.example.platform.contract.GateDecision;
import com.example.platform.contract.quality.GateSnapshot;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 准入的纯判定：输入是"已经取到的数据"，输出是固定顺序的清单（M6 起为六项）。
 *
 * <p>刻意不查询数据库、不调 HTTP——取数在 {@link AdmissionEvaluator}，
 * 这样六项规则可以逐条单测（含反面：服务不可用时必须 FAIL 而不是放行）。</p>
 */
public final class AdmissionPolicy {

    /** RD 达到"测试通过"及之后的状态都算通过（MERGED/DONE 是更后面的状态）。 */
    public static final Set<String> TEST_PASSED_STATUSES = Set.of("TEST_PASSED", "MERGED", "DONE");

    public static final String FIX_SERVICE_DOWN = "缺陷/门禁服务不可用，请稍后重试";

    /** 第 6 项的质量门机器码：{@code detail} 里出现即表示"没拿到结论"，fail-closed。 */
    public static final String GATE_UNAVAILABLE = "GATE_UNAVAILABLE";

    /** 第 6 项：发布质量门。结论来自 deliver 的 {@code QualityGatePort}，req 不重算 5 条规则。 */
    public static final String QUALITY_GATE = AdmissionItem.QUALITY_GATE;

    public static final String FIX_GATE_UNAVAILABLE =
            "质量门服务不可用，请稍后重试；持续失败请联系运维检查 deliver-service";

    public AdmissionPolicy() {
    }

    /**
     * 一条需求的事实快照。
     *
     * @param blockingApprovals 未满足的需求级审批，形如 {@code ["评审:PENDING","版本:未发起"]}
     */
    public record ReqFact(long id, String code, String type, String status, String mrUrl,
                          BigDecimal estimateDays, BigDecimal actualDays, List<String> blockingApprovals) {

        public boolean testPassed() {
            return status != null && TEST_PASSED_STATUSES.contains(status);
        }

        public boolean mrFilled() {
            return mrUrl != null && !mrUrl.isBlank();
        }

        public boolean manDaysFilled() {
            return positive(estimateDays) && positive(actualDays);
        }

        private static boolean positive(BigDecimal value) {
            return value != null && value.compareTo(BigDecimal.ZERO) > 0;
        }
    }

    /**
     * @param gates            需求→门禁结论；{@code null} 表示门禁服务不可用（必须 FAIL）
     * @param openHighDefects  需求→未关闭 P0/P1 数；{@code null} 表示缺陷服务不可用（必须 FAIL）
     * @param gate             第 6 项质量门证据；{@code null} 表示 deliver 未在预算内应答（必须 FAIL）
     */
    public record Inputs(List<ReqFact> requirements, Map<Long, GateDecision> gates,
                         Map<Long, Integer> openHighDefects, DeployViews.GateEvidence gate) {

        /** 五行版构造：无质量门证据（单测与历史调用点用），等价于第 6 项 GATE_UNAVAILABLE。 */
        public Inputs(List<ReqFact> requirements, Map<Long, GateDecision> gates,
                      Map<Long, Integer> openHighDefects) {
            this(requirements, gates, openHighDefects, null);
        }
    }

    public List<AdmissionItem> evaluate(Inputs inputs) {
        List<ReqFact> requirements = inputs.requirements() == null ? List.of() : inputs.requirements();
        List<AdmissionItem> items = new ArrayList<>(6);
        items.add(testPassed(requirements, inputs.gates()));
        items.add(noOpenHighDefect(requirements, inputs.openHighDefects()));
        items.add(mrFilled(requirements));
        items.add(manDaysFilled(requirements));
        items.add(approvalsDone(requirements));
        items.add(qualityGate(inputs.gate()));
        return items;
    }

    // ------------------------------------------------------------------ 1

    private AdmissionItem testPassed(List<ReqFact> requirements, Map<Long, GateDecision> gates) {
        String code = AdmissionItem.REQ_TEST_PASSED;
        String name = AdmissionItem.nameOf(code);
        String fix = "把关联的研发需求流转到「测试通过」，并按需在计划里执行用例";
        if (requirements.isEmpty()) {
            return AdmissionItem.fail(code, name, "发布单未关联任何研发需求", "在版本计划中把需求拆解为研发需求（RD）并关联本发布单");
        }
        if (gates == null) {
            return AdmissionItem.fail(code, name, "测试门禁不可用，无法确认测试结论", FIX_SERVICE_DOWN);
        }
        List<String> statusFailures = new ArrayList<>();
        List<String> gateFailures = new ArrayList<>();
        for (ReqFact req : requirements) {
            if (!req.testPassed()) {
                statusFailures.add(req.code() + "=" + req.status());
            }
            GateDecision gate = gates.get(req.id());
            if (gate != null && !gate.passed()) {
                gateFailures.add(req.code() + "（" + gateReason(gate) + "）");
            }
        }
        if (statusFailures.isEmpty() && gateFailures.isEmpty()) {
            return AdmissionItem.pass(code, name, requirements.size() + " 条研发需求均已测试通过");
        }
        String detail = !statusFailures.isEmpty()
                ? "未到「测试通过」：" + join(statusFailures)
                : "测试门禁未通过：" + join(gateFailures);
        return AdmissionItem.fail(code, name, detail, fix);
    }

    private static String gateReason(GateDecision gate) {
        if (gate.reason() != null && !gate.reason().isBlank()) {
            return gate.reason();
        }
        List<GateBlocker> blockers = gate.blockers() == null ? List.of() : gate.blockers();
        if (blockers.isEmpty()) {
            return "门禁未通过";
        }
        return blockers.size() + " 项阻断";
    }

    // ------------------------------------------------------------------ 2

    private AdmissionItem noOpenHighDefect(List<ReqFact> requirements, Map<Long, Integer> openHighDefects) {
        String code = AdmissionItem.NO_OPEN_HIGH_DEFECT;
        String name = AdmissionItem.nameOf(code);
        if (requirements.isEmpty()) {
            return AdmissionItem.fail(code, name, "发布单未关联任何研发需求", "先关联研发需求");
        }
        if (openHighDefects == null) {
            return AdmissionItem.fail(code, name, "缺陷服务不可用，无法确认 P0/P1 存量", FIX_SERVICE_DOWN);
        }
        int total = 0;
        List<String> perRequirement = new ArrayList<>();
        for (ReqFact req : requirements) {
            Integer count = openHighDefects.get(req.id());
            if (count == null || count <= 0) {
                continue;
            }
            total += count;
            perRequirement.add(req.code() + "=" + count);
        }
        if (total == 0) {
            return AdmissionItem.pass(code, name, "关联需求没有未关闭的 P0/P1 缺陷");
        }
        return AdmissionItem.fail(code, name, "未关闭 P0/P1 共 " + total + " 个：" + join(perRequirement),
                "先修复并关闭 P0/P1 缺陷，或经评审降级后重新提交");
    }

    // ------------------------------------------------------------------ 3

    private AdmissionItem mrFilled(List<ReqFact> requirements) {
        String code = AdmissionItem.MR_FILLED;
        String name = AdmissionItem.nameOf(code);
        List<String> missing = requirements.stream()
                .filter(req -> !req.mrFilled())
                .map(ReqFact::code)
                .toList();
        if (requirements.isEmpty()) {
            return AdmissionItem.fail(code, name, "发布单未关联任何研发需求", "先关联研发需求");
        }
        if (missing.isEmpty()) {
            return AdmissionItem.pass(code, name, requirements.size() + " 条研发需求的 MR 链接均已填写");
        }
        return AdmissionItem.fail(code, name, "未填 MR：" + join(missing),
                "在研发需求详情补填代码合并请求（MR）链接");
    }

    // ------------------------------------------------------------------ 4

    private AdmissionItem manDaysFilled(List<ReqFact> requirements) {
        String code = AdmissionItem.MAN_DAYS_FILLED;
        String name = AdmissionItem.nameOf(code);
        List<String> missing = requirements.stream()
                .filter(req -> !req.manDaysFilled())
                .map(req -> req.code() + "（预估=" + plain(req.estimateDays()) + "，实际=" + plain(req.actualDays()) + "）")
                .toList();
        if (requirements.isEmpty()) {
            return AdmissionItem.fail(code, name, "发布单未关联任何研发需求", "先关联研发需求");
        }
        if (missing.isEmpty()) {
            return AdmissionItem.pass(code, name, requirements.size() + " 条研发需求的预估/实际人天均已填写");
        }
        return AdmissionItem.fail(code, name, "人天缺失或为 0：" + join(missing),
                "在研发需求详情补填预估人天与实际人天（都必须大于 0）");
    }

    // ------------------------------------------------------------------ 5

    private AdmissionItem approvalsDone(List<ReqFact> requirements) {
        String code = AdmissionItem.REQ_APPROVALS_DONE;
        String name = AdmissionItem.nameOf(code);
        if (requirements.isEmpty()) {
            return AdmissionItem.fail(code, name, "发布单未关联任何研发需求", "先关联研发需求");
        }
        List<String> blocking = new ArrayList<>();
        for (ReqFact req : requirements) {
            if (req.blockingApprovals() != null && !req.blockingApprovals().isEmpty()) {
                blocking.add(req.code() + "（" + String.join("/", req.blockingApprovals()) + "）");
            }
        }
        if (blocking.isEmpty()) {
            return AdmissionItem.pass(code, name, "关联需求的评审入池与版本审批均已通过");
        }
        return AdmissionItem.fail(code, name, "审批未通过：" + join(blocking),
                "完成需求的评审入池审批与需求发布审批（审批中心可见在途单）");
    }

    // ------------------------------------------------------------------ 6

    /**
     * 第 6 项：发布质量门（AC-5）。结论只透传 deliver 的求值结果，req 侧不重算 QG-01..QG-05，
     * 否则两个服务的阈值与分母必然分叉。
     *
     * <p>fail-closed：{@code gate == null}（超时/异常/未应答）一律 FAIL 并带 {@link #GATE_UNAVAILABLE}，
     * 绝不允许"查不到就视为通过"。</p>
     */
    private AdmissionItem qualityGate(DeployViews.GateEvidence gate) {
        String code = QUALITY_GATE;
        String name = AdmissionItem.nameOf(code);
        if (gate == null) {
            return AdmissionItem.fail(code, name,
                    "质量门不可用（" + GATE_UNAVAILABLE + "）：报告/门禁服务未在预算内应答，本次不放行",
                    FIX_GATE_UNAVAILABLE);
        }
        List<GateSnapshot.GateRuleResult> rules = gate.rules() == null ? List.of() : gate.rules();
        if (gate.passed()) {
            String waiver = GateSnapshot.PASS_WITH_WAIVER.equals(gate.result()) ? "（含规则级豁免）" : "";
            return AdmissionItem.pass(code, name,
                    rules.size() + " 条规则全部通过" + waiver + "，质量门快照 #" + gate.snapshotId());
        }
        List<GateSnapshot.GateRuleResult> failed = gate.failedRules();
        List<String> detail = failed.stream()
                .map(rule -> rule.code() + "=" + nvl(rule.actual()) + "（要求 " + nvl(rule.threshold()) + "）")
                .toList();
        String fixHint = failed.stream()
                .map(GateSnapshot.GateRuleResult::fixHint)
                .filter(hint -> hint != null && !hint.isBlank())
                .findFirst()
                .orElse("按门禁规则修复后重新生成报告并重新会签；P0/P1 与 QG-04 属红线，不可豁免");
        return AdmissionItem.fail(code, name,
                "质量门未通过（快照 #" + gate.snapshotId() + "）："
                        + (detail.isEmpty() ? nvl(gate.result()) : String.join("、", detail)),
                fixHint);
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 快照指纹：需求集合或任何参与判定的字段变了，旧快照即失效（详情页显示"需重新校验"）。
     * 这是"先渲染旧结论再被 409 打回"的反面防线。
     */
    public static String signature(List<ReqFact> requirements) {
        return signature(requirements, null);
    }

    /**
     * 六项版指纹：把第 6 项质量门的 {@code result_digest} 一并算进去，并加 {@code v6} 前缀。
     *
     * <p>M6 之前落库的 5 项指纹没有前缀，必然与新版不等 ⇒ 旧单永远被判 stale+legacy，
     * 不会被误当成"当前结论"（否则新加的第 6 项会被旧快照掩盖）。</p>
     */
    public static String signature(List<ReqFact> requirements, String gateHash) {
        String base = requirements == null || requirements.isEmpty() ? "empty" : factsSignature(requirements);
        return "v6|" + (gateHash == null || gateHash.isBlank() ? "none" : gateHash) + "|" + base;
    }

    private static String factsSignature(List<ReqFact> requirements) {
        StringBuilder builder = new StringBuilder();
        for (ReqFact req : requirements) {
            builder.append(req.id()).append(':')
                    .append(nvl(req.status())).append(':')
                    .append(nvl(req.mrUrl())).append(':')
                    .append(plain(req.estimateDays())).append(':')
                    .append(plain(req.actualDays())).append(':')
                    .append(req.blockingApprovals() == null ? "" : String.join(",", req.blockingApprovals()))
                    .append(';');
        }
        return builder.toString();
    }

    /** 强制放行后：FAIL 项保留（不伪造 PASS），只打上 forced 标记供前端展示"已受控放行"。 */
    public static List<AdmissionItem> markForced(List<AdmissionItem> items, boolean forced) {
        if (!forced || items == null) {
            return items;
        }
        return items.stream().map(item -> item.passed() ? item : item.withForced(true)).toList();
    }

    public static List<AdmissionItem> sort(List<AdmissionItem> items) {
        List<AdmissionItem> sorted = new ArrayList<>(items == null ? List.of() : items);
        sorted.sort((a, b) -> Integer.compare(indexOf(a.code()), indexOf(b.code())));
        return sorted;
    }

    private static int indexOf(String code) {
        int index = AdmissionItem.ORDER.indexOf(code);
        return index < 0 ? AdmissionItem.ORDER.size() : index;
    }

    public static List<String> failedCodes(List<AdmissionItem> items) {
        if (items == null) {
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        for (AdmissionItem item : items) {
            if (!item.passed()) {
                codes.add(item.code());
            }
        }
        return codes;
    }

    public static Map<String, Object> toMap(AdmissionItem item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", item.code());
        map.put("name", item.name());
        map.put("passed", item.passed());
        map.put("detail", item.detail());
        map.put("fixHint", item.fixHint());
        map.put("forced", item.forced());
        return map;
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "-";
        }
        int limit = 6;
        if (values.size() <= limit) {
            return String.join("、", new TreeSet<>(values));
        }
        return String.join("、", new TreeSet<>(values).stream().limit(limit).toList()) + " 等 " + values.size() + " 条";
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private static String plain(BigDecimal value) {
        return value == null ? "未填" : value.stripTrailingZeros().toPlainString();
    }
}
