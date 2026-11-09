package com.example.req.integration;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.req.integration.jenkins.BuildStateMachine;
import com.example.req.integration.jenkins.BuildStateMachine.BuildStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AC-3.3 / AC-3.4：构建状态机是纯函数，所以整张迁移矩阵可以在这里穷举——
 * 不变量是「终态不可回退」与「同态不迁移」，任何一条被破坏都会让 MERGED/SUCCESS 被打回。
 */
class BuildStateMachineTest {

    private static final Set<BuildStatus> TERMINAL =
            Set.of(BuildStatus.SUCCESS, BuildStatus.FAILURE, BuildStatus.ABORTED);

    @Test
    @DisplayName("全部 6×6 组合：只有 7 条前向迁移，其余同态→自身、反向→OUT_OF_ORDER")
    void exhaustiveMatrix() {
        // QUEUED 可以直达终态（构建很快，回调先于轮询），但终态一律不能回到活动态。
        assertEquals(BuildStatus.RUNNING, BuildStateMachine.next(BuildStatus.QUEUED, BuildStatus.RUNNING));
        assertEquals(BuildStatus.SUCCESS, BuildStateMachine.next(BuildStatus.QUEUED, BuildStatus.SUCCESS));
        assertEquals(BuildStatus.FAILURE, BuildStateMachine.next(BuildStatus.QUEUED, BuildStatus.FAILURE));
        assertEquals(BuildStatus.ABORTED, BuildStateMachine.next(BuildStatus.QUEUED, BuildStatus.ABORTED));
        assertEquals(BuildStatus.SUCCESS, BuildStateMachine.next(BuildStatus.RUNNING, BuildStatus.SUCCESS));
        assertEquals(BuildStatus.FAILURE, BuildStateMachine.next(BuildStatus.RUNNING, BuildStatus.FAILURE));
        assertEquals(BuildStatus.ABORTED, BuildStateMachine.next(BuildStatus.RUNNING, BuildStatus.ABORTED));

        for (BuildStatus current : BuildStatus.values()) {
            for (BuildStatus incoming : BuildStatus.values()) {
                BuildStatus next = BuildStateMachine.next(current, incoming);
                if (current == incoming) {
                    assertEquals(incoming, next, "同态不迁移: " + current);
                } else if (TERMINAL.contains(current)) {
                    assertEquals(BuildStatus.OUT_OF_ORDER, next, current + " 是终态，不能被 " + incoming + " 改写");
                } else if (current == BuildStatus.OUT_OF_ORDER) {
                    assertEquals(BuildStatus.OUT_OF_ORDER, next, "OUT_OF_ORDER 是哨兵，不是可迁移状态");
                }
            }
        }
        // RUNNING 不能回到 QUEUED：那是回退，不是重排。
        assertEquals(BuildStatus.OUT_OF_ORDER, BuildStateMachine.next(BuildStatus.RUNNING, BuildStatus.QUEUED));
    }

    @Test
    @DisplayName("终态判定：只有 SUCCESS/FAILURE/ABORTED 落库，OUT_OF_ORDER 永不落库")
    void terminalAndPersisted() {
        for (BuildStatus status : BuildStatus.values()) {
            assertEquals(TERMINAL.contains(status), status.terminal(), status.name());
        }
        assertFalse(BuildStateMachine.persistedStatuses().contains(BuildStatus.OUT_OF_ORDER));
        assertEquals(5, BuildStateMachine.persistedStatuses().size());
        assertTrue(BuildStatus.QUEUED.active());
        assertTrue(BuildStatus.RUNNING.active());
        assertFalse(BuildStatus.SUCCESS.active());
    }

    @Test
    @DisplayName("解析：未知/空值不猜，返回 null 或抛；不把脏数据当 QUEUED")
    void parsing() {
        assertEquals(BuildStatus.RUNNING, BuildStatus.ofOrNull("running"));
        assertEquals(BuildStatus.RUNNING, BuildStatus.ofOrNull(" RUNNING "));
        assertNull(BuildStatus.ofOrNull("UNSTABLE"));
        assertNull(BuildStatus.ofOrNull(null));
        assertEquals(BuildStatus.FAILURE, BuildStatus.of("failure"));
    }

    @Test
    @DisplayName("重复判定：同态才是 DUPLICATE，反向不是（它必须被记成 OUT_OF_ORDER）")
    void duplicateVsOutOfOrder() {
        assertTrue(BuildStateMachine.isDuplicate(BuildStatus.SUCCESS, BuildStatus.SUCCESS));
        assertFalse(BuildStateMachine.isDuplicate(BuildStatus.SUCCESS, BuildStatus.RUNNING));
        assertTrue(BuildStateMachine.isAdvance(BuildStatus.QUEUED, BuildStatus.RUNNING));
        assertFalse(BuildStateMachine.isAdvance(BuildStatus.SUCCESS, BuildStatus.RUNNING));
    }
}
