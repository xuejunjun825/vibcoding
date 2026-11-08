package com.example.req.seed;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 种子结果：{seeded, skipped, counts}。
 *
 * <p>counts 里的每个数字都是**从库里 SELECT COUNT(*) 读出来的**，不是本次插入的行数——
 * 所以连跑两遍时两次响应的 counts 必须逐键相等（AC-19 的断言对象）。</p>
 */
public final class SeedResult {

    private final boolean seeded;
    private final boolean skipped;
    private final Map<String, Object> counts;

    private SeedResult(boolean seeded, boolean skipped, Map<String, Object> counts) {
        this.seeded = seeded;
        this.skipped = skipped;
        this.counts = counts;
    }

    public static SeedResult seeded(Map<String, Object> counts) {
        return new SeedResult(true, false, counts);
    }

    public static SeedResult skipped(Map<String, Object> counts) {
        return new SeedResult(false, true, counts);
    }

    public boolean isSeeded() {
        return seeded;
    }

    public boolean isSkipped() {
        return skipped;
    }

    public Map<String, Object> getCounts() {
        return counts;
    }

    public static Map<String, Object> newCounts() {
        return new LinkedHashMap<>();
    }

    public static List<String> loginUsers() {
        return List.of(
                "admin:Admin@12345",
                "pm:Passw0rd!",
                "tl1:Passw0rd!",
                "po1:Passw0rd!",
                "dev1:Passw0rd!",
                "dev2:Passw0rd!",
                "qa1:Passw0rd!",
                "qa2:Passw0rd!",
                "vendor1:Passw0rd!",
                "vendor2:Passw0rd!");
    }
}
