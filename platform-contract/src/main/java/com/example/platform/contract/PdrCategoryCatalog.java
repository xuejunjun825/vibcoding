package com.example.platform.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PDR 评审意见的九类码表——req 与 deliver 唯一的一份（设计 §4.4）。
 *
 * <p>为什么放在 {@code platform-contract} 而不是各自服务里：{@code req-service} 的
 * {@code /pdr-reviews/stats} 与 {@code deliver-service} 的分析域交叉表必须按同一套分类码聚合，
 * 否则同一个租户在两个页面会看到「同类意见」被拆成两行。码表是编译期常量而不是读
 * {@code sys_dict}：{@code sys_dict} 由 {@code RoleCatalog} 播种、租户可改文案，但<b>合法性</b>
 * 必须认这里，否则同一条意见在一台库上合法、在另一台库上 400。</p>
 *
 * <p>顺序即前端下拉顺序、统计分桶顺序与导出表头顺序（契约的一部分），所以不用
 * {@link Map#copyOf}（不保序）。</p>
 */
public final class PdrCategoryCatalog {

    /** 九类，顺序即前端下拉顺序。 */
    public static final List<String> ALL = List.of("RANGE", "TECH", "SCHEDULE", "RISK",
            "ACCEPTANCE", "UI", "DATA", "COMPLIANCE", "OTHER");

    private static final Map<String, String> LABELS = labels();

    private PdrCategoryCatalog() {
    }

    public static boolean knows(String code) {
        return code != null && LABELS.containsKey(code);
    }

    /** 展示文本；未知码回退成码本身（统计对历史脏数据仍可读）。 */
    public static String label(String code) {
        return code == null ? "" : LABELS.getOrDefault(code, code);
    }

    /** 字典行（code → label），供种子与数据集元信息复用同一份文案。 */
    public static Map<String, String> dictionary() {
        return LABELS;
    }

    private static Map<String, String> labels() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("RANGE", "范围/需求理解");
        map.put("TECH", "技术可行性");
        map.put("SCHEDULE", "工期/排期");
        map.put("RISK", "风险/依赖");
        map.put("ACCEPTANCE", "验收标准");
        map.put("UI", "界面/交互");
        map.put("DATA", "数据/兼容");
        map.put("COMPLIANCE", "合规/安全");
        map.put("OTHER", "其他");
        return Collections.unmodifiableMap(map);
    }
}
