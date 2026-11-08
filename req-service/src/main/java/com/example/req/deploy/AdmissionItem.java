package com.example.req.deploy;

/**
 * 准入清单的一项：{@code code} 是稳定断言键，{@code name} 给人看，
 * {@code fixHint} 是"怎么改"（前端常驻清单左侧红条直接展示，409 里也必须带上）。
 *
 * <p>顺序由 {@link #ORDER} 固定，前端不排序、不隐藏任何一项——逐项 PASS/FAIL 是 AC-12 的验收对象。</p>
 */
public record AdmissionItem(String code, String name, boolean passed, String detail, String fixHint,
                            boolean forced) {

    public static final String REQ_TEST_PASSED = "REQ_TEST_PASSED";
    public static final String NO_OPEN_HIGH_DEFECT = "NO_OPEN_HIGH_DEFECT";
    public static final String MR_FILLED = "MR_FILLED";
    public static final String MAN_DAYS_FILLED = "MAN_DAYS_FILLED";
    public static final String REQ_APPROVALS_DONE = "REQ_APPROVALS_DONE";

    /** 固定顺序：与 PRD §4.9 的五项一一对应，增删都要同步前端与验证脚本。 */
    public static final java.util.List<String> ORDER = java.util.List.of(
            REQ_TEST_PASSED, NO_OPEN_HIGH_DEFECT, MR_FILLED, MAN_DAYS_FILLED, REQ_APPROVALS_DONE);

    public static AdmissionItem pass(String code, String name, String detail) {
        return new AdmissionItem(code, name, true, detail, null, false);
    }

    public static AdmissionItem fail(String code, String name, String detail, String fixHint) {
        return new AdmissionItem(code, name, false, detail, fixHint, false);
    }

    public AdmissionItem withForced(boolean value) {
        return new AdmissionItem(code, name, passed, detail, fixHint, value);
    }

    public static String nameOf(String code) {
        return switch (code) {
            case REQ_TEST_PASSED -> "关联需求测试通过";
            case NO_OPEN_HIGH_DEFECT -> "无未关闭的 P0/P1 缺陷";
            case MR_FILLED -> "研发需求已填 MR 链接";
            case MAN_DAYS_FILLED -> "预估/实际人天已填写";
            case REQ_APPROVALS_DONE -> "需求级审批已通过";
            default -> code;
        };
    }
}
