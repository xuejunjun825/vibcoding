package com.example.req.integration.jenkins;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 构建状态机（AC-3.3）：纯函数，没有 IO，也不认识数据库。
 *
 * <p>为什么放在 Java 而不是 DB：一张表上的 CHECK 约束表达不了「不许回退」，只能表达
 * 「值在集合里」。回退（RUNNING → QUEUED）本身是<b>合法值</b>的非法<b>迁移</b>——只有
 * 迁移矩阵能判定。轮询与 webhook 两条通道都调这一个函数，因此两条通道不可能对同一份
 * 事实给出两种结论。</p>
 *
 * <p>三条规则：</p>
 * <ol>
 *   <li><b>同态 = 重复</b>（{@link #isDuplicate}）：由调用方在调用 {@link #next} 之前判定，
 *       因为「重复」不是状态机的一次迁移，而是同一条事实的重投（AC-3.4 幂等 200）。</li>
 *   <li><b>只能向前</b>：QUEUED → RUNNING → 终态。允许跳过 RUNNING（构建太快、回调只带了
 *       终态），因为那是「追赶」；反向一律 {@link BuildStatus#OUT_OF_ORDER}。</li>
 *   <li><b>终态是吸收态</b>：任何从终态出发的迁移都是 OUT_OF_ORDER，SUCCESS 永远不会被
 *       后来的 RUNNING 打回。</li>
 * </ol>
 */
public final class BuildStateMachine {

    /** 构建状态。{@code OUT_OF_ORDER} 是判定结果而非可落库状态，见 {@link #persisted()}。 */
    public enum BuildStatus {
        QUEUED,
        RUNNING,
        SUCCESS,
        FAILURE,
        ABORTED,
        /** 判定哨兵：非法迁移的返回值，绝不能写进 build_record。 */
        OUT_OF_ORDER;

        public boolean terminal() {
            return this == SUCCESS || this == FAILURE || this == ABORTED;
        }

        public boolean active() {
            return this == QUEUED || this == RUNNING;
        }

        /** {@code false} 只对哨兵成立：调用方据此拒绝落库。 */
        public boolean persisted() {
            return this != OUT_OF_ORDER;
        }

        /** 宽松解析：{@code null}/空/未知都返回 {@code null}，由调用方决定是 IGNORED 还是 400。 */
        public static BuildStatus ofOrNull(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String value = raw.trim().toUpperCase(Locale.ROOT);
            for (BuildStatus status : values()) {
                if (status.name().equals(value)) {
                    return status;
                }
            }
            return null;
        }

        /** 严格解析：未知值抛 {@link IllegalArgumentException}（webhook 载荷用）。 */
        public static BuildStatus of(String raw) {
            BuildStatus status = ofOrNull(raw);
            if (status == null) {
                throw new IllegalArgumentException("unknown build status: " + raw);
            }
            return status;
        }
    }

    /** 迁移矩阵：唯一的一份可穷举来源（5×5 表，测试逐格断言）。 */
    private static final Map<BuildStatus, Set<BuildStatus>> ALLOWED = allowed();

    private BuildStateMachine() {
    }

    private static Map<BuildStatus, Set<BuildStatus>> allowed() {
        Map<BuildStatus, Set<BuildStatus>> map = new EnumMap<>(BuildStatus.class);
        map.put(BuildStatus.QUEUED, EnumSet.of(
                BuildStatus.RUNNING, BuildStatus.SUCCESS, BuildStatus.FAILURE, BuildStatus.ABORTED));
        map.put(BuildStatus.RUNNING, EnumSet.of(
                BuildStatus.SUCCESS, BuildStatus.FAILURE, BuildStatus.ABORTED));
        map.put(BuildStatus.SUCCESS, EnumSet.noneOf(BuildStatus.class));
        map.put(BuildStatus.FAILURE, EnumSet.noneOf(BuildStatus.class));
        map.put(BuildStatus.ABORTED, EnumSet.noneOf(BuildStatus.class));
        map.put(BuildStatus.OUT_OF_ORDER, EnumSet.noneOf(BuildStatus.class));
        return map;
    }

    /**
     * 一次迁移的目标状态；非法迁移返回 {@link BuildStatus#OUT_OF_ORDER}。
     *
     * <p>重复（{@code current == incoming}）返回 {@code incoming} 本身——调用方必须先用
     * {@link #isDuplicate} 判掉，否则会把「重复」当成一次成功的迁移去落库（虽然幂等，
     * 但会多写一次 updated_at 与一条审计）。</p>
     */
    public static BuildStatus next(BuildStatus current, BuildStatus incoming) {
        if (current == null || incoming == null) {
            return BuildStatus.OUT_OF_ORDER;
        }
        if (current == incoming) {
            return incoming;
        }
        return ALLOWED.getOrDefault(current, Set.of()).contains(incoming)
                ? incoming : BuildStatus.OUT_OF_ORDER;
    }

    /** 同态 = 同一条事实的重复投递（AC-3.4「同一 buildId+status 幂等 200」）。 */
    public static boolean isDuplicate(BuildStatus current, BuildStatus incoming) {
        return current != null && current == incoming;
    }

    /** 该迁移是否可以落库（用于把判定结果统一成一个布尔，避免各处自己比哨兵）。 */
    public static boolean isAdvance(BuildStatus current, BuildStatus incoming) {
        return next(current, incoming) != BuildStatus.OUT_OF_ORDER;
    }

    /** 只读的矩阵快照：测试与文档用，调用方不得修改。 */
    public static Set<BuildStatus> targets(BuildStatus from) {
        return java.util.Collections.unmodifiableSet(
                ALLOWED.getOrDefault(from, Set.of()));
    }

    /** 所有可落库状态（不含哨兵）——DB 枚举校验与演示脚本共用。 */
    public static Set<BuildStatus> persistedStatuses() {
        return EnumSet.of(BuildStatus.QUEUED, BuildStatus.RUNNING,
                BuildStatus.SUCCESS, BuildStatus.FAILURE, BuildStatus.ABORTED);
    }
}
