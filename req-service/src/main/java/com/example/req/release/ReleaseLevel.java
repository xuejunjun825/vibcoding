package com.example.req.release;

import java.util.List;
import java.util.Locale;

/**
 * 发布等级的<b>唯一语义来源</b>（r30）：三值 {@code NORMAL / EMERGENCY / ROLLBACK}，中文标签也只有这里一份。
 *
 * <p>为什么不用 Java enum：等级要出现在 URL 路径（{@code /admin/flow-bindings/RELEASE/NORMAL}）、
 * 数据库列、流程定义名映射、前端字典四处，任何一处需要"字符串常量"时 enum 都要 {@code name()} 一次。
 * 用 String 常量 + 三个静态方法，调用方拿到的永远是可以直接比较/落库的字符串，不会出现
 * {@code ReleaseLevel.valueOf(...)} 这种把校验失败变成 {@code IllegalArgumentException} 的写法。</p>
 *
 * <p>为什么 {@link #normalise(String)} 把 null/空串当成 NORMAL：创建版本时该字段是可选的
 * （老客户端不传），"没填" 的语义就是常规发布；而非法值必须报错而不是被悄悄改成 NORMAL，
 * 所以 normalise 只做 trim + upper，合法性由 {@link #isValid(String)} 判定后由
 * {@link ReleaseLevelValidator} 抛 400。</p>
 */
public final class ReleaseLevel {

    /** 常规发布：默认值，走常规三级链（产品经理 → PMO → 管理层）。 */
    public static final String NORMAL = "NORMAL";
    /** 紧急修复：必须填原因，走两级链（产品经理 → 管理层）。 */
    public static final String EMERGENCY = "EMERGENCY";
    /** 回滚：必须带上一张已放行/已成功的发布单号，走两级链（PMO → 平台管理员）。 */
    public static final String ROLLBACK = "ROLLBACK";

    /** 全部合法取值，顺序即前端下拉框顺序（常规 → 紧急 → 回滚）。 */
    public static final List<String> ALL = List.of(NORMAL, EMERGENCY, ROLLBACK);

    private ReleaseLevel() {
    }

    /** 严格三值判定；null/空串/未知值一律 false（调用方据此抛 400，不做兜底转换）。 */
    public static boolean isValid(String level) {
        return level != null && ALL.contains(level);
    }

    /** trim + 大写；null/空串 → {@link #NORMAL}。<b>不做</b>合法性兜底：非法值原样返回给 isValid 判。 */
    public static String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            return NORMAL;
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    /** 中文标签（后端统一，前端不自行翻译）：常规 / 紧急修复 / 回滚；未知值退回原始字符串。 */
    public static String label(String level) {
        if (level == null) {
            return NORMAL;
        }
        return switch (level) {
            case NORMAL -> "常规";
            case EMERGENCY -> "紧急修复";
            case ROLLBACK -> "回滚";
            default -> level;
        };
    }
}
