package com.example.platform.contract;

/**
 * 「这一页 + 这个动作，调用者拿到什么档位」——运行期授权的唯一查询端口（r22）。
 *
 * <p>r22 之前 {@link RequirementActionMatrix} 自己持有一张 {@code action → 功能码} 的编译期
 * 常量表，再用 {@link PermissionMatrix#accessOf} 现场算档位。问题是：{@code sys_action} 的档位
 * 真源早已搬到 {@code ActionRegistry} → {@code RbacSeeder} → {@code sys_role_permission}
 * （r18/r21），运行期却还在读常量矩阵——管理员在角色授权页把 {@code req.action.submit_test}
 * 改成 DENY，需求详情页的按钮和执行判定都看不见，于是又回到「UI 上勾了、库里写了、请求还是
 * 403」的老毛病，只是这次换成「库里改了、按钮还在」。</p>
 *
 * <p>本接口把那一半职责切出来：<b>判据（关系语义）留在矩阵，档位查询交给本端口</b>。矩阵因此
 * 不再知道 {@code sys_role_permission} 存在，实现也不再需要知道需求状态机存在。</p>
 *
 * <p><b>为什么接口在 {@code platform-contract}、实现在 {@code platform-common}</b>：依赖方向是
 * {@code platform-contract ← platform-common}，contract 有意零依赖（只有 JDK 类型），这样网关、
 * 两个服务与前端契约能共用同一份定义。{@link GatePort} 与 {@code ApprovalCallback} 是同一个
 * 先例：contract 出接口，服务侧实现，引擎不 import 业务类。唯一实现是
 * {@code PermissionService}（三序判定 + 每租户快照 + epoch 失效 + 请求内 memo）。</p>
 *
 * <p><b>为什么是 {@code @FunctionalInterface}</b>：授权的 8 条既有单测要能用 lambda 桩跑矩阵的
 * 关系语义，不必起 Spring、也不必伪造 {@code TenantContext}。接线的正确性（真实现等价性）由另一
 * 个测试用真的 {@code PermissionService} 覆盖——桩永远测不出「roles 从上下文来、vendorId 从
 * Caller 来」这半条路径。</p>
 *
 * <p><b>实现方约束</b>：{@link #decide} 必须返回非 {@code null}。矩阵把 {@code null} 当 DENY
 * 处理（fail-closed），但这个兜底不是实现方可以依赖的契约。</p>
 */
@FunctionalInterface
public interface ActionPermissionResolver {

    /**
     * 页面 + 动作的三序判定：页面非 DENY → 动作非 DENY → 取较窄档位。
     *
     * <p>{@code menuCode} 为空白表示「只看动作档位」；{@code actionCode} 为空白表示「只看页面」，
     * 此时返回页面档位。语义与 {@code PermissionService.decide} 完全一致——这是同一个实现，不是
     * 另一套推导。</p>
     *
     * @param menuCode   页面码（如 {@code req.pool}），空白视为 ALLOW
     * @param actionCode 动作码（如 {@code req.action.submit}），空白时只判页面
     * @return DENY / RESTRICTED / ALLOW，<b>必须非 null</b>；RESTRICTED 是「通过但收窄范围」
     */
    PermissionMatrix.Access decide(String menuCode, String actionCode);
}
