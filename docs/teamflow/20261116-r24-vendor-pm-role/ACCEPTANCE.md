# r24 验收报告 · 厂商项目经理（VENDOR_PM）角色

| 项 | 值 |
|---|---|
| 任务夹 | `docs/teamflow/20261116-r24-vendor-pm-role/` |
| 分支 | `feat/vendor-pm-role` |
| 验收日期 | 2026-11-16（QA 复验后） |
| 验收人 | 产品经理（验收负责人） |
| 结论 | **✅ 通过**：14 条 AC 全部满足，1 条 P3 文案遗留（非阻塞，r25 必修） |

## 1. 验收方式（独立执行，不引用 QA 数字）

真实栈在本机可用（网关 8080 鉴权生效、web 5173、MySQL、headless Chrome CDP 9222），因此以下由验收侧**自己重跑**：

- `bash logs/teamflow/tf-mwiuzjpm-poecia/scripts/qa-r24-acceptance-probe.sh` → **37/37**（AC-1/2、5、6、7、8、9、13 端到端）
- `bash scripts/rbac-verify.sh` → **113/113**；`bash scripts/access-verify.sh` → **165/165**
- `node web/e2e/vendor-pm.spec.mjs` → **19/19**（真实 DOM：侧栏、写按钮禁用且非主色、深链 403、厂商必填聚焦）
- 静态核对 6 个单一真相点（§3）、`git status` 51 条路径无 `target/dist/node_modules/logs`、`pom.xml` / `web/package.json` / `AGENTS.md` diff 均为 0
- 证据图人工抽检 03/07/08：只读抽屉主操作已降级为默认按钮、厂商必填为「红框 + 流内红字 + 光标聚焦 + 弹窗不关」、角色列表首行即 VENDOR_PM 且页头「共 11 个角色」

其余门禁（platform-verify、negative、demo-reset、unit、lint、verify、analytics、collab、typecheck/build/compile）采用 QA 复跑数与门禁脚本自身的下限核对；AC-3/4/11/12/14 由这些门禁与单测承载。

## 2. AC 逐条核对

| AC | 级 | 判据与证据 | 结论 |
|---|---|---|---|
| AC-1 注册四处同源 | P0 | `PermissionMatrix.java:145`、`RoleCatalog`、`SeedService.java:207`、`V13__vendor_pm_role.sql` 均含 `VENDOR_PM`；`/admin/permissions.roles` 含之、builtin=true；新租户 11 角色（platform-verify 173） | 通过 |
| AC-2 168 格逐格 | P0 | 探针实查每租户该角色 168 行：FUNCTION 12 ALLOW/1 RESTRICTED/47 DENY，ACTION 7 ALLOW/65 DENY 且 11 条覆盖逐条命中，MENU 11 页非 DENY、`admin.*` 等 10 页 DENY | 通过 |
| AC-3 判定零改、既有 1680 不变 | P0 | s2 规范化哈希一致（`f0e1137b…`）；11×72 遍历证明覆盖只命中 VENDOR_PM；`PermissionMatrixGridTest` 600→660 且旧 600 逐格不变；rbac 113/113 | 通过 |
| AC-4 升级/新建/幂等 | P0 | `V13` 只幂等建角色行（不内联 168 行），168 行由启动自愈补齐；flyway=13、t1 = 11 角色/1848 行；seed×2、demo-reset×2 行数不变（QA 61/61 连跑两次） | 通过 |
| AC-5 演示账号与文档 | P0 | `vpm1`（VENDOR_PM + VEN-A，`sys_user_role` 恰 1 条）；`SeedResult.java:67` 已补入 loginUsers；`DEMO-BY-ROLE.md:28` 有行；账号 13→14；`vendor1` 仍 PMO | 通过 |
| AC-6 vendor_id 必填 | P0 | `AdminUserController.java:311` 校验在写库前，update 用「将生效角色」(:138)；探针：400 `VALIDATION_FAILED(field=vendorId)`、用户数不变、PUT 清空 400 且原 vendor_id 仍 1、mgr1 不误伤 | 通过 |
| AC-7 厂商隔离 | P0 | vpm1 需求/缺陷/任务/用例可见 3 条，与 vendor1 同口径且 < admin 12；他厂商详情 403 `OUT_OF_SCOPE`；列表本身 200 | 通过 |
| AC-8 版本派生可见性 | P0 | `ScopeSql.java:58-63` 仅对 `release` 派生 EXISTS；`ReleaseService.java:356/378` 列表与详情共用同一 marker 判 403；探针：DEMO-REL-1 列表 0 条/详情 403 → 纳入本厂商需求后 1 条/200 → 夹具回滚 204；admin 基线不变；vendor1 收窄已在 `DEMO-BY-ROLE.md:82` 说明 | 通过 |
| AC-9 写动作边界 | P0 | `/auth/me/permissions.actions` 恰 7 项（schedule + 6 个缺陷处理）；approve/review_*/decompose/assign_vendor/submit、版本建改审、用例写、测试计划建批执行、sprints/tasks、admin 写全部 403 且计数不变；schedule 200 | 通过 |
| AC-10 前端显示与只读收敛 | P0 | 角色列表/下拉含 VENDOR_PM（证据 08）；授权页数据源 168 行且 `RolePermissionsView.vue` 本轮未改动（grid 单测 660）；写按钮全部读 `canAction`（`ReleaseListView:24`、`ReleaseDetailView:83-84`、`TestCaseLibraryView:39`、`TestPlansView:59-61`）；侧栏 11 页、admin 不渲染；用例库例外只认 `VENDOR_PM` 角色码（`auth.ts:125`、`navAccess.ts:59`）；typecheck 0 | 通过（附 §5 建议 1） |
| AC-11 新增负向 ≥5 | P0 | `platform-test-negative.sh` **92/92**（≥54），第 17 段 5 类：跨厂商列表/详情、approve 403、role.*/user.* 403、版本审批 403、无 vendorId 建号 400 不落库 | 通过 |
| AC-12 门禁不缩水 | P0 | 见 §4：全部 ≥ 基线，逐段下限只增（rbac 11 段、access 6 段、negative 17 段），无删断言 | 通过 |
| AC-13 文档与记忆 | P1 | `DEMO-BY-ROLE`：vpm1 行 + 矩阵列 + 4.9 小节 + 版本为空脚注；`memory.md:31` 11 角色/1848 行（同主题替换）；AGENTS 托管区外零 diff；残留 D-6 见 §5 | 通过（P3 残留） |
| AC-14 既有角色不回归 | P1 | vendor1 仍 PMO+VEN-A 且可审批版本；a2 覆盖 9 页 × 8 角色；12 个既有账号页面/需求数/动作集同 r23；`PermissionMatrixGridTest` 660、旧 600 逐格不变 | 通过 |

## 3. 架构一致性（M3）

- **蓝图逐项落地**：`ScopeSql.DERIVED_VENDOR` 只登记 `release`（`ScopeSql.java:58-63`）；`ReleaseService` 全部版本写口过 `requireVisible`（:160-333），列表与详情共用 :378 的 `/*scope:r*/`，未出现第二份 SQL。
- **写口单一入口**：`PermissionRouteTable.java:249` 把评审轮次创建从页面码改为 `release.action.update`；探针双向验证 admin 201 / vpm1 403，未误伤内部角色。
- **账号约束在写库前**：`AdminUserController.java:311` 对 create 用请求角色、对 update 用将生效角色（:138），改角色不能绕过 400。
- **单一真相**：`RbacSeeder.java:69` 是 11 条 ACTION 覆盖唯一来源，`PermissionMatrix` 保持「角色×60 功能码」契约；`RoleCatalog.requiresVendor` 为账号校验唯一来源；`navAccess.ts:59` 一套判定被导航/路由/三处 NavContext 共用；`auth.ts:125` 只认角色码。
- **无重复实现/无适配漂移/无该抽未抽**：V13 刻意不内联 168 行、release 谓词只有一条 EXISTS，均与蓝图取舍一致。**架构返工项：0。**

## 4. 门禁

| 门禁 | 实测 | 要求 | 判定 |
|---|---|---|---|
| rbac-verify（本次复跑） | 113/113 | ≥111 | 通过 |
| access-verify（本次复跑） | 165/165 | ≥152 | 通过 |
| platform-verify | 173/173 | ≥168 | 通过 |
| platform-test-negative | 92/92 | ≥54 | 通过 |
| platform-demo-reset | 61/61（连跑两次） | ≥60 | 通过 |
| platform-unit | 338/338 | ≥335 | 通过 |
| lint / verify / analytics / collab | 22 / 14 / 74 / 257 | ≥22 / 14 / 74 / s0–s8 | 通过 |
| typecheck / build / compile | 0 错误 / 绿 / exit 0 | 0 | 通过 |

## 5. 意见与遗留

**P3（不阻塞放行，r25 必修）— 管理端渲染文案仍停留在 10 角色 / 1680 行**

- `web/src/views/admin/AdminPermissionsView.vue:155`「10 个内置角色回到 1680 行预设授权」
- `web/src/views/admin/AdminOrgsView.vue:253`「创建后自动预置 10 角色」
- `web/src/views/admin/AdminLayout.vue:31`「10 角色 × 功能码只读总览」
- 另有注释 6 处同口径（`web/src/api/admin.ts:91/298/304`、`AdminOrgsView.vue:13` + 测试注释 2 处）
- 验收判据：`grep -rn "1680" web/src | wc -l` 与 `grep -rn "10 角色\|10 个内置角色" web/src | wc -l` 均应为 0（现为 3 / 5）。属 PRD §9 点名的「前端文案撒谎」风险；不涉及鉴权、隔离或写边界，故不阻塞。

**建议（非缺陷）**

1. 证据完整性：授权页 168 格矩阵缺一张独立截图（`RolePermissionsView.vue` 未改动 + API 168 行 + grid 单测 660 已证显示正确），建议 r25 补一张。
2. 口径：`me.approvals` 对 vpm1 仍可见（其审批动作全 DENY），与 AC-10 字面「不渲染」有出入，TECHNICAL §0 C2 已记录，建议下版 PRD 固化措辞。
3. PRD §10 五条待澄清已全部按推荐默认落地（不给提报、版本页只读、用例库仅 VENDOR_PM 例外、不新增功能码、版本可见性对所有 VENDOR 域生效），建议下一版 PRD 固化为决定项。

**人工补测（沿用 QA §10，环境限制）**：真实浏览器视觉终检、1440→1920 换行、键盘可达性、暗色对比度。

## 6. 放行

可合入、可演示、可发布；P3 文案随 r25 一并订正即可。

验收结论：✅ 通过
