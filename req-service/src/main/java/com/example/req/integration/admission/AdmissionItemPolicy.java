package com.example.req.integration.admission;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 准入 9 项的<b>唯一常量源</b>：代码、固定顺序、名称、是否锁定、说明（TECHNICAL §7.1）。
 *
 * <p>为什么只在这里写一次：同一份顺序与名称同时被三处消费——判定策略（{@code AdmissionPolicy}）、
 * 管理端开关接口（{@code /integration/admission-items}）与前端展示。三处各写一份，改一项就要
 * 追着改三处，漏掉任何一处都会让"清单顺序/名称"与验收脚本对不上。前端只做 code → 文案的展示映射，
 * 不自己定义第 10 项。</p>
 *
 * <p>锁定项（①②③⑥）的开关写入口直接 409 {@code ITEM_LOCKED}：把"不能关"做成常量判断，
 * 数据库里即使有 {@code enabled=0} 的脏行也关不掉测试通过/无高危缺陷/需求审批/质量门。</p>
 */
public final class AdmissionItemPolicy {

    public static final String REQ_TEST_PASSED = "REQ_TEST_PASSED";
    public static final String NO_OPEN_HIGH_DEFECT = "NO_OPEN_HIGH_DEFECT";
    public static final String REQ_APPROVALS_DONE = "REQ_APPROVALS_DONE";
    public static final String MR_FILLED = "MR_FILLED";
    public static final String MAN_DAYS_FILLED = "MAN_DAYS_FILLED";
    /** M6 的第 6 项：发布质量门（deliver 侧求值，req 只透传结论）。 */
    public static final String QUALITY_GATE = "QUALITY_GATE";
    /** M7 追加：构建、Sonar 质量门、制品校验（默认关闭 ⇒ CLOSED，不阻断提交）。 */
    public static final String BUILD_PASSED = "BUILD_PASSED";
    public static final String SONAR_GATE_PASSED = "SONAR_GATE_PASSED";
    public static final String ARTIFACT_VERIFIED = "ARTIFACT_VERIFIED";

    /**
     * PRD AC-8.1 的固定顺序。注意 ③④⑤ 相对 M6 的 {@code [test,noP0,mr,manDays,approvals,gate]}
     * 已调换（需求审批前移到第 3 项），第 6 项仍是质量门；⑦⑧⑨ 追加在末尾。
     */
    private static final List<String> ORDER = List.of(
            REQ_TEST_PASSED, NO_OPEN_HIGH_DEFECT, REQ_APPROVALS_DONE, MR_FILLED, MAN_DAYS_FILLED,
            QUALITY_GATE, BUILD_PASSED, SONAR_GATE_PASSED, ARTIFACT_VERIFIED);

    /** ①②③⑥ 由代码锁死：任何租户都不能关掉"测试通过/无高危缺陷/需求审批/质量门"。 */
    private static final Set<String> LOCKED = Set.of(
            REQ_TEST_PASSED, NO_OPEN_HIGH_DEFECT, REQ_APPROVALS_DONE, QUALITY_GATE);

    private static final Map<String, String> NAMES;
    private static final Map<String, String> DESCRIPTIONS;

    static {
        Map<String, String> names = new LinkedHashMap<>();
        names.put(REQ_TEST_PASSED, "关联需求测试通过");
        names.put(NO_OPEN_HIGH_DEFECT, "无未关闭的 P0/P1 缺陷");
        names.put(REQ_APPROVALS_DONE, "需求级审批已通过");
        names.put(MR_FILLED, "研发需求已填 MR 链接");
        names.put(MAN_DAYS_FILLED, "预估/实际人天已填写");
        names.put(QUALITY_GATE, "发布质量门通过");
        names.put(BUILD_PASSED, "构建已通过");
        names.put(SONAR_GATE_PASSED, "Sonar 质量门通过");
        names.put(ARTIFACT_VERIFIED, "制品已校验");
        NAMES = Map.copyOf(names);

        Map<String, String> descriptions = new LinkedHashMap<>();
        descriptions.put(REQ_TEST_PASSED, "关联研发需求全部到达「测试通过」，且测试门禁无阻断");
        descriptions.put(NO_OPEN_HIGH_DEFECT, "关联需求没有未关闭的 P0/P1 缺陷");
        descriptions.put(REQ_APPROVALS_DONE, "需求的提报审批与评审审批均已通过");
        descriptions.put(MR_FILLED, "每条研发需求都填了代码合并请求（MR）链接");
        descriptions.put(MAN_DAYS_FILLED, "预估人天与实际人天都已填写且大于 0");
        descriptions.put(QUALITY_GATE, "发布质量门（QG-01..QG-05）结论为通过或带豁免通过");
        descriptions.put(BUILD_PASSED, "最近一次 Jenkins 构建为成功；集成未启用时自动关闭");
        descriptions.put(SONAR_GATE_PASSED, "Sonar 快照门禁为 OK；未绑定 projectKey 时跳过并说明原因");
        descriptions.put(ARTIFACT_VERIFIED, "制品 sha256 已校验并登记；集成未启用时自动关闭");
        DESCRIPTIONS = Map.copyOf(descriptions);
    }

    private AdmissionItemPolicy() {
    }

    /** 9 项固定顺序（不可变）。 */
    public static List<String> order() {
        return ORDER;
    }

    /** ① ② ③ ⑥ 由代码锁定，接口层据此拒绝关闭。 */
    public static boolean locked(String code) {
        return code != null && LOCKED.contains(code);
    }

    public static boolean known(String code) {
        return code != null && NAMES.containsKey(code);
    }

    public static String nameOf(String code) {
        return NAMES.getOrDefault(code, code);
    }

    public static String descriptionOf(String code) {
        return DESCRIPTIONS.getOrDefault(code, "");
    }

    /** 1-based 序号；未知 code 排在末尾（历史快照里出现已下线的项时不至于 NPE）。 */
    public static int orderOf(String code) {
        int index = ORDER.indexOf(code);
        return index < 0 ? ORDER.size() + 1 : index + 1;
    }

    /** ⑦⑧⑨ 是集成项：集成关闭时按 CLOSED 处理而不是 FAIL。 */
    public static boolean integrationItem(String code) {
        return BUILD_PASSED.equals(code) || SONAR_GATE_PASSED.equals(code) || ARTIFACT_VERIFIED.equals(code);
    }
}
