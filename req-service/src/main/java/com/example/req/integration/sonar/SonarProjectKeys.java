package com.example.req.integration.sonar;

import java.util.Map;
import java.util.regex.Pattern;

import com.example.platform.common.error.ApiException;

/**
 * Sonar projectKey 的唯一校验/规范化入口。
 *
 * <p>这个键有三条写入路径——版本默认值（{@code PUT /releases/{id}/integration-settings}）、
 * 需求自带值（需求编辑接口的 {@code sonarProjectKey}）、以及读取时 Sonar 快照刷新前的
 * 兜底校验——如果各自写一份正则，某一处放宽就会把坏 key 送去外呼，或者让「需求 &gt; 版本」
 * 的优先级在两处不一致。因此正则、错误码与「空格串等于清除」的语义都只在这里实现一次。</p>
 */
public final class SonarProjectKeys {

    /** AC-6.1：字母数字与 _ . : - ，长度 1-200。 */
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_.:\\-]{1,200}$");

    /** PRD 指定的机器码；前端按此判定 400 的字段级提示。 */
    public static final String CODE_INVALID = "SONAR_KEY_INVALID";

    private static final String MESSAGE = "Sonar 项目键只能是字母数字与 _ . : -，长度 1-200";

    private SonarProjectKeys() {
    }

    /**
     * 规范化并校验一个键：空白串视为「清除」，返回 {@code null}；非法字符抛
     * 400 {@link #CODE_INVALID}，绝不把坏 key 传给 Nexus/Sonar。
     *
     * @param raw 调用方给的原值（可为 null）
     * @param field 错误 detail 里的字段名（{@code sonarProjectKey} / {@code defaultSonarProjectKey}）
     */
    public static String normalize(String raw, String field) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (!KEY.matcher(value).matches()) {
            throw new ApiException(400, CODE_INVALID, MESSAGE,
                    Map.of("field", field == null ? "sonarProjectKey" : field, "value", value));
        }
        return value;
    }

    /** 只判定是否合法，不抛异常（读取路径用：脏数据按「无 key」降级而不是炸页面）。 */
    public static boolean isValid(String raw) {
        return raw != null && KEY.matcher(raw.trim()).matches();
    }
}
