package com.example.req.deploy;

import com.example.platform.contract.GateBlocker;
import com.example.platform.contract.GateDecision;
import com.example.platform.contract.quality.GateSnapshot;
import com.example.req.integration.admission.AdmissionItemPolicy;
import com.example.req.integration.admission.AdmissionViews;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 准入的纯判定：输入是"已经取到的数据"，输出是固定顺序的九项清单（M7 起）。
 *
 * <p>刻意不查询数据库、不调 HTTP——取数在 {@link AdmissionEvaluator}，
 * 九项规则因此可以逐条单测（含反面：服务不可用时必须 FAIL 而不是放行）。</p>
 *
 * <p>四态（PRD AC-8.2）：PASS 通过 / FAIL 阻断 / SKIP 跳过（永不计数为通过，提交需等值确认）/
 * CLOSED 关闭（项开关关闭或集成未启用，不阻断、不计入分母）。</p>
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

    public static final String FIX_BUILD =
            "在发布单详情触发构建，或修复失败原因后重新构建；2 小时未结束的构建会被判超时";

    public static final String FIX_SONAR =
            "在需求详情补填 Sonar projectKey（或版本默认 key），修复未达标指标后刷新 Sonar 快照";

    public static final String FIX_ARTIFACT =
            "在发布单绑定制品（Nexus 检索或手工登记），确保 sha256 与源端一致并完成校验";

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
     * @param closedCodes      被管理员关闭的准入项（不含锁定项）；这些项输出 CLOSED 且不参与分母
     * @param build            ⑦ 构建证据（本地读）；{@code null} 表示读取失败（必须 FAIL）
     * @param sonar            ⑧ Sonar 证据（本地读）
     * @param artifact         ⑨ 制品证据（本地读）
     */
    public record Inputs(List<ReqFact> requirements, Map<Long, GateDecision> gates,
                         Map<Long, Integer> openHighDefects, DeployViews.GateEvidence gate,
                         Set<String> closedCodes, AdmissionViews.Evidence build,
                         AdmissionViews.Evidence sonar, AdmissionViews.Evidence artifact) {

        /** 四项版构造：M6 的调用点（无集成证据 ⇒ ⑦⑧⑨ 一律 FAIL，fail-closed）。 */
        public Inputs(List<ReqFact> requirements, Map<Long, GateDecision> gates,
                      Map<Long, Integer> openHighDefects, DeployViews.GateEvidence gate) {
            this(requirements, gates, openHighDefects, gate, Set.of(), null, null, null);
        }

        /** 三项版构造：单测与 M6 之前的历史调用点。 */
        public Inputs(List<ReqFact> requirements, Map<Long, GateDecision> gates,
                      Map<Long, Integer> openHighDefects) {
            this(requirements, gates, openHighDefects, null, Set.of(), null, null, null);
        }
    }

    /** 九项固定顺序（PRD AC-8.1）：①②③ 需求/审批，④⑤ 资料，⑥ 质量门，⑦⑧⑨ 集成。 */
    public List<AdmissionItem> evaluate(Inputs inputs) {
        List<ReqFact> requirements = inputs.requirements() == null ? List.of() : inputs.requirements();
        Set<String> closed = inputs.closedCodes() == null ? Set.of() : inputs.closedCodes();
        List<AdmissionItem> items = new ArrayList<>(9);
        items.add(switched(testPassed(requirements, inputs.gates()), closed));
        items.add(switched(noOpenHighDefect(requirements, inputs.openHighDefects()), closed));
        items.add(switched(approvalsDone(requirements), closed));
        items.add(switched(mrFilled(requirements), closed));
        items.add(switched(manDaysFilled(requirements), closed));
        items.add(switched(qualityGate(inputs.gate()), closed));
        items.add(integration(AdmissionItem.BUILD_PASSED, inputs.build(), closed, FIX_BUILD));
        items.add(integration(AdmissionItem.SONAR_GATE_PASSED, inputs.sonar(), closed, FIX_SONAR));
        items.add(integration(AdmissionItem.ARTIFACT_VERIFIED, inputs.artifact(), closed, FIX_ARTIFACT));
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

    // ------------------------------------------------------------------ 4

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

    // ------------------------------------------------------------------ 5

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

    // ------------------------------------------------------------ 7 / 8 / 9

    /** 项开关关闭 ⇒ CLOSED（不阻断、不计分母）；证据缺失 ⇒ FAIL（fail-closed）。 */
    private static AdmissionItem switched(AdmissionItem item, Set<String> closed) {
        if (closed.contains(item.code()) && !AdmissionItemPolicy.locked(item.code())) {
            return AdmissionItem.closed(item.code(), item.name(),
                    "该项已被管理员关闭，不参与本次准入判定（关闭原因：ITEM_OFF）", AdmissionViews.ITEM_OFF);
        }
        return item;
    }

    private static AdmissionItem integration(String code, AdmissionViews.Evidence evidence, Set<String> closed,
                                             String fixHint) {
        if (closed.contains(code)) {
            return AdmissionItem.closed(code, AdmissionItem.nameOf(code),
                    "该项已被管理员关闭，不参与本次准入判定（关闭原因：ITEM_OFF）", AdmissionViews.ITEM_OFF);
        }
        if (evidence == null) {
            return AdmissionItem.fail(code, AdmissionItem.nameOf(code),
                    "证据读取失败（EVIDENCE_UNAVAILABLE）：本次不放行", fixHint);
        }
        return AdmissionItem.of(code, evidence, fixHint);
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 快照指纹（M7 起 v9）：需求事实 + 质量门 hash + ⑦⑧⑨ 证据 hash + 关闭项集合，
     * 任何一处变化都会让旧快照变 stale（页面提示"重新校验"），而不是拿上一轮结论当现行结论。
     */
    public static String signature(List<ReqFact> requirements) {
        return signature(requirements, null);
    }

    public static String signature(List<ReqFact> requirements, String gateHash) {
        return signature(requirements, gateHash, null);
    }

    public static String signature(List<ReqFact> requirements, String gateHash, String integrationDigest) {
        return "v9|" + blankTo(gateHash, "none") + "|" + blankTo(integrationDigest, "none") + "|"
                + factsDigest(requirements);
    }

    /** 需求事实的摘要：详情页判断 {@code stale} 只比这一段（不重新取集成证据）。 */
    public static String factsDigest(List<ReqFact> requirements) {
        String base = requirements == null || requirements.isEmpty() ? "empty" : factsSignature(requirements);
        return sha256(base);
    }

    /** ⑦⑧⑨ 的证据摘要：只由这三项自己的 status/证据 hash 与关闭项集合决定。 */
    public static String integrationDigest(List<AdmissionItem> items, Set<String> closedCodes) {
        StringBuilder builder = new StringBuilder();
        if (closedCodes != null) {
            new TreeSet<>(closedCodes).forEach(code -> builder.append("off:").append(code).append(';'));
        }
        if (items != null) {
            for (AdmissionItem item : items) {
                if (!AdmissionItemPolicy.integrationItem(item.code())) {
                    continue;
                }
                builder.append(item.code()).append(':').append(nvl(item.status())).append(':')
                        .append(blankTo(item.evidenceHash(), "none")).append(';');
            }
        }
        return sha256(builder.toString());
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

    /** 阻断码：只有 FAIL 才算失败；SKIP/CLOSED 不计（PRD AC-8.3 / AC-8.5）。 */
    public static List<String> failedCodes(List<AdmissionItem> items) {
        if (items == null) {
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        for (AdmissionItem item : items) {
            if (AdmissionItem.FAIL.equals(item.status())) {
                codes.add(item.code());
            }
        }
        return codes;
    }

    public static Map<String, Object> toMap(AdmissionItem item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", item.code());
        map.put("name", item.name());
        map.put("order", item.order());
        map.put("status", item.status());
        map.put("passed", item.passed());
        map.put("detail", item.detail());
        map.put("fixHint", item.fixHint());
        map.put("reasonCode", item.reasonCode());
        map.put("evidence", item.evidence());
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

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private static String plain(BigDecimal value) {
        return value == null ? "未填" : value.stripTrailingZeros().toPlainString();
    }
}
