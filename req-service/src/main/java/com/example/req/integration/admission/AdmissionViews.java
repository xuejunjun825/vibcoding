package com.example.req.integration.admission;

import java.util.List;

/**
 * 准入相关的视图与枚举收口处：HTTP 面（管理端 9 项开关）与判定面（四态证据）都从这里取类型，
 * 前端与验证脚本因此对齐同一组字段名（TECHNICAL §4.2 / §4.6）。
 */
public final class AdmissionViews {

    /** 四态：PASS 计入通过；SKIP 跳过（永不计通过，提交需确认）；CLOSED 不参与（不进分母）；FAIL 阻断。 */
    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    public static final String SKIP = "SKIP";
    public static final String CLOSED = "CLOSED";

    /**
     * 关闭/跳过的机器码。前端与 QA 断言读这些码，所以集中在这里而不是散落在策略里。
     */
    public static final String ITEM_OFF = "ITEM_OFF";
    public static final String INTEGRATION_DISABLED = "INTEGRATION_DISABLED";
    public static final String NOT_CONFIGURED = "NOT_CONFIGURED";

    private AdmissionViews() {
    }

    /** 管理端的一项开关（恒 9 条，顺序即 {@link AdmissionItemPolicy#order()}）。 */
    public record ItemView(String code, String name, int order, boolean locked, boolean enabled,
                           String description) {
    }

    /** PUT 载荷里的一项：{@code enabled} 必填（省略即 400，避免"PUT 空对象"静默成功）。 */
    public record Toggle(String code, Boolean enabled) {
    }

    public record ToggleCmd(List<Toggle> items) {
    }

    /**
     * ⑦⑧⑨ 的本地证据（零外呼）：状态 + 机器码 + 说明 + 指纹。
     *
     * <p>{@code hash} 参与准入快照签名——构建号/快照 hash/制品 sha256 任何一处变化都会让旧快照
     * 变 stale，页面因此提示"重新校验"，而不是拿上一轮的结论当现行结论。</p>
     */
    public record Evidence(String status, String reasonCode, String detail, String hash,
                           java.util.Map<String, Object> facts) {

        public Evidence(String status, String reasonCode, String detail, String hash) {
            this(status, reasonCode, detail, hash, java.util.Map.of());
        }

        public Evidence {
            facts = facts == null ? java.util.Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(facts));
        }

        public static Evidence pass(String reasonCode, String detail, String hash) {
            return new Evidence(PASS, reasonCode, detail, hash);
        }

        public static Evidence fail(String reasonCode, String detail, String hash) {
            return new Evidence(FAIL, reasonCode, detail, hash);
        }

        public static Evidence skip(String reasonCode, String detail, String hash) {
            return new Evidence(SKIP, reasonCode, detail, hash);
        }

        public static Evidence closed(String reasonCode, String detail) {
            return new Evidence(CLOSED, reasonCode, detail, null);
        }

        public boolean passed() {
            return PASS.equals(status);
        }
    }
}
