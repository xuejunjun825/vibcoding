package com.example.req.pdr;

import java.util.List;
import java.util.Map;

/**
 * 评审意见的九类字典（设计 §4.4）。
 *
 * <p>码表与文案的唯一来源是 {@link com.example.platform.contract.PdrCategoryCatalog}
 * （{@code platform-contract}）——{@code deliver-service} 的分析域交叉表按同一套码聚合，
 * 两侧复制字符串数组迟早会漂移。本类只保留 req 侧沿用的静态入口名，委托给共享码表。</p>
 *
 * <p>码表是编译期常量而不是只读库：{@code sys_dict} 里的同九行由 {@code RoleCatalog} 播种（租户可改
 * 文案），但<b>合法性判定</b>认这里——否则某个租户删了一行字典，同一条意见在一台库上 400、
 * 在另一台库上 201。</p>
 *
 * <p>{@code label} 同时用于错误 detail、统计分桶与转任务的描述前缀，三处文案因此不可能分叉。</p>
 */
public final class PdrCategories {

    /** 九类，顺序即前端下拉顺序。 */
    public static final List<String> ALL = com.example.platform.contract.PdrCategoryCatalog.ALL;

    private static final Map<String, String> LABELS =
            com.example.platform.contract.PdrCategoryCatalog.dictionary();

    private PdrCategories() {
    }

    public static boolean knows(String code) {
        return com.example.platform.contract.PdrCategoryCatalog.knows(code);
    }

    /** 展示文本；未知码回退成码本身（统计对历史脏数据仍可读）。 */
    public static String label(String code) {
        return com.example.platform.contract.PdrCategoryCatalog.label(code);
    }

    /** 字典行（code → label），供种子与 `/pdr-reviews/datasets` 复用同一份文案。 */
    public static Map<String, String> dictionary() {
        return LABELS;
    }
}
