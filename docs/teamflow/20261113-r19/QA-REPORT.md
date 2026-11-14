# QA-REPORT · 20261113-r19 三层授权（功能码 / 页面 / 动作）收尾 · 复验轮 3

- 分支 `feat/rbac-admin`（HEAD `5b7ba9d`）｜工作区 47 项改动未提交（39 M + 8 ??，按 ADR-2026-08-27 由 host 收口）
- 任务夹 `docs/teamflow/20261113-r19/`（PRD · DESIGN · TECHNICAL · evidence/01–05，夹建后不可变）
- 测试日期 2026-11-14｜测试人 QA（r19 第 3 轮复验）｜运行栈 gateway 8080 · req-service 8082 · deliver-service 8083 · web 5173 · MySQL `devops_platform`（`/internal/health` = UP / db up / flyway 12）
- 本轮开始前先清理了 59 个历史探针租户（`t2-*`，见 OBS-6），库内只剩真实租户 `t1`（10 角色 / 1680 授权行 / 103 用户），故下文库内统计均为**单租户口径**

## 1. 结论

**不可验收：P1 × 1 + P2 × 2 项仍开放，均原样复现（本轮无任何修复落地）。**

- **BUG-1（P1，维持）**：`GET /api/req/admin/roles/{id}/permissions` 只覆盖 **30/60 个 FUNCTION 码**，而 `PUT` 以 `fullGrid()`（168 项全 DENY）整表重写 ⇒ 原样回存即把视图里不存在的档位归零。干净库实测：QA 角色 **16 个活档位** ALLOW→DENY，`qa-r19-ui-save-loss.sh` exit 1。
- **BUG-2（P2，维持且扩大）**：门禁非幂等。本轮除 s3→s2 旧路径外，**新发现 `access-verify` 全量跑完（152/152 绿）之后 s2 必红**（`ACTION 720 格不符 = 0：实际 30`），`demo-reset` 后复绿 —— 污染源不止 rbac 段。
- **BUG-3（P2，新增）**：`scripts/platform-demo-reset.sh` 间歇性 1/57 失败（`[41] 执行器页接口 HTTP 500`），同日两次出现、重跑即绿；失败时 reset 退出码为 1，等于演示态可能停在半重置。
- 修复未落地的客观证据：`RoleAdminService.java`（11-13 18:14）、`useRoleGrants.ts`（18:08）、`scripts/tests/rbac/_lib.sh`（16:27）mtime 全部早于上一轮 QA 报告（11-14 06:50），仓库中不存在 `RoleGrantGrid` / `subjects()` 之类新抽象。
- 其余门禁本轮全绿（含上轮被环境卡住的 `access-verify`）：`rbac 101/101` · `platform-verify 167/167` · `access 152/152` · `demo-reset 57/57` · `lint 22/22` · `negative 28/28` · `verify 14/14` · `analytics 74/74` · `typecheck 0` · `build 0` · `interface 50/50` · `t4 34/34` · `t5 57/57` · `t5-ssr 18/18` · `t1 7/7` · `t2 18/18` · `evidence 5/5`。

## 2. 本轮实测（一命令一检查，均为 exit code 实测）

| # | 检查 | 命令 | 退出码 | 结果 |
|---|---|---|---|---|
| 1 | 三层授权门禁（分段） | `bash scripts/rbac-verify.sh s0 … s9` | 0 | **101/101**（s0 14·s1 17·s2 10·s3 8·s4 7·s5 12·s6 9·s7 9·s8 7·s9 8） |
| 2 | 平台回归 | `bash scripts/platform-verify.sh` | 0 | **167/167**（AC-1..AC-20） |
| 3 | 角色×页面准入 | `bash scripts/access-verify.sh` | 0 | **152/152**（a0 29·a1 8·a2 64·a3 10·a4 16·a5 25）—— 上轮环境阻塞项本轮关闭 |
| 4 | 演示重置 | `bash scripts/platform-demo-reset.sh` | 0 | **57/57**（首次 56/57，见 BUG-3） |
| 5 | 静态规范 | `bash scripts/tests/lint.sh` | 0 | **22/22** |
| 6 | 负向用例 | `bash scripts/platform-test-negative.sh` | 0 | **28/28**（首次因 `curl: (28) timed out 25129 ms` 失败，宿主冻结，见 §7） |
| 7 | 环境体检 | `bash scripts/verify.sh` | 0 | **14/14** |
| 8 | 分析域中文化 | `bash scripts/analytics-i18n-verify.sh` | 0 | **74/74** |
| 9 | 前端类型 | `cd web && npm run typecheck` | 0 | 无 error（宿主冻结 928 s 后完成） |
| 10 | 前端构建 | `cd web && npm run build` | 0 | `✓ 2669 modules transformed` / `built in 6.51s` |
| 11 | 接口一致性（0a） | `bash logs/…/qa-interface-check.sh` | 0 | **50/50** |
| 12 | 角色页静态契约 | `node logs/…/t4-roles-page-contract.mjs` | 0 | **34/34**（表格 942 ≤ 992） |
| 13 | 授权草稿模型 | `node logs/…/t5-grants-check.mjs` | 0 | **57/57**（夹具 168 行，对 BUG-1 免疫，见 OBS-1） |
| 14 | 授权页 SSR 冒烟 | `node logs/…/t5-view-ssr-check.mjs` | 0 | **18/18** |
| 15 | 建租户闭环 | `bash logs/…/t1-new-tenant-login.sh` | 0 | **7/7**（探针租户已清理） |
| 16 | 重置自定义角色 | `bash logs/…/t2-reset-custom-role.sh` | 0 | **18/18** |
| 17 | 证据新鲜度 | `bash logs/…/qa-evidence-freshness.sh` | 0 | **5/5** 图晚于全部前端源码 |
| 18 | **保存丢档位（P1）** | `bash logs/…/qa-r19-ui-save-loss.sh` | **1** | payload 138 subject vs 网格 168；`changed=16`、`nonDENY→DENY=16`；恢复快照逐字节相等 |
| 19 | **门禁幂等（P2）** | `bash logs/…/qa-r19-suite-idempotency.sh` | **1** | s2(before)=0 → s3=0 → s2(after)=**1**（`ACTION 720 格不符 = 0：实际 30`） |
| 20 | 读写键集缺口 | `bash logs/…/role-grants-payload-gap.sh` | **1** | `FUNCTION 36 + MENU 36 + ACTION 72 = 144` vs 库内每角色 168 |
| 21 | **暴露面量化（新增检查）** | `bash logs/…/qa-r19-unbound-fn-exposure.sh` | **1** | 注册表 60 个 FUNCTION 码、GET 覆盖 30、缺口 **30**；缺口行 300 行/租户，其中**活档位 114 行/租户**（占该租户 742 个活档位的 15%） |
| 22 | 提交面卫生（0c） | `git status --porcelain` | 0 | 47 项；无 `target/`/`dist/`/`node_modules/`/`.idea/`/`.env`/`.eslintcache`/`logs/`；`pom.xml`、`package.json` diff 为空 |
| 23 | 重置后等价性 | `bash scripts/tests/rbac/s2-seed-equivalence.sh` | 0 | **10/10**（`CANONICAL_HASH=ea21db0e…`，仅在 reset 之后成立） |

## 3. 缺陷复验对照（列名为轮次级别，非缺陷表表头）

| 编号 | round-2 级 | round-3 级 | 上轮状态 | 本轮实测 | 结论 |
|---|---|---|---|---|---|
| BUG-1 读写键集双推导 | P1 | **P1** | 开放 | 干净单租户库复现：QA 角色 16 个活档位 ALLOW→DENY；缺口 30 码 / 300 行 / 114 活档位 | OPEN（`qa-r19-ui-save-loss.sh` exit 1） |
| BUG-2 门禁非幂等 | P2 | **P2** | 开放 | s3→s2 旧路径仍红；**新增触发面**：`access-verify` 全量后 s2 红、`demo-reset` 后复绿 | OPEN（`qa-r19-suite-idempotency.sh` exit 1） |
| 上轮 BUG-1'（证据失真） | 已关闭 | 关闭 | CLOSED | 5/5 图新鲜、01 列头含「更新时间」 | CLOSED，不复发 |
| demo-reset 间歇 500 | — | **P2（新增）** | 未覆盖 | 同日两次 `[41] 执行器页接口 HTTP 500`，重跑即绿 | OPEN（BUG-3） |

## 4. 架构核查（M3）

- **蓝图落位（抽查 14 项仍成立）**：V12 只 DDL（重放零副作用，1680 行由 Java 常量派生）· `RbacSeeder.seedRegistries/seedTenant/preset` · `RbacBootstrap` 逐租户独立事务 · `TenantProvisioner` 建租户同事务 · `DemoResetService` 回预设 + `rolePermission`/`customRoles` 计数 · tokens 14 个新变量只增不改 · `AdminLayout` 条目 NavGuard 化 + `navVisible` · `SideNav`/`TopNav` 补 `hasMenu` · `AdminPermissionsView` 只读化 · `AdminRolesView` / `RolePermissionsView` + `useRoleGrants`/`AccessSegmented`/`RoleGrantTree`。
- **未提取的抽象（BUG-1 的架构根因，P1）**：同一份「subject 全集」被派生两次且互不校验 —— 读侧 `RoleAdminService.java:75-108` `grants()` 只遍历 `MenuRegistry` 页面绑定（30 个 FUNCTION 码）；写侧 `RoleAdminService.java:328-340` `fullGrid()` = `PermissionMatrix.functions()`(60) + `MenuRegistry.codes()`(36) + `ActionRegistry.codes()`(72) = 168，并在 `saveGrants()` 里作为全 DENY 基底整表重写。缺一个 `RoleGrantGrid.subjects()` 式的唯一全集来源。
- **重复实现**：无其它重复 —— 准入判定只有 `navVisible`，三档控件只有 `AccessSegmented`，写入点只有 `RbacSeeder.seedTenant`，`PermissionMatrix` 运行时代码直连 = 0（`lint L19`）。
- **接口一致性（0a）**：50/50 静态断言通过（每个被调成员的导出/签名/参数形状/蓝图 api 字段均命中）。
- **文档偏差（P3，任务夹不可变，仅记录）**：`TECHNICAL.md:114` 把 `accessForAny` 写成「任一 DENY ⇒ DENY」，实际 ALLOW 优先；DENY 优先属 `PermissionService.merge` 的多角色并集规则。

## 5. 缺陷表（仅本轮仍 OPEN 项）

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| BUG-1 | P1 | 架构（`RoleAdminService` 读写键集双推导） | 1) `POST /api/req/auth/login`（tenantCode=t1, admin）；2) `GET /api/req/admin/roles/7/permissions` → 页面树 36 页，仅覆盖 30 个 FUNCTION 码；3) 原样 `PUT` 同内容（confirmHighRisk=false）；4) 查 `sys_role_permission` 中 role_id=7 的 FUNCTION 行 | 保存只落地用户实际改动的格；未出现在视图里的 30 个 FUNCTION subject 保持原档位 | `saveGrants()` 以 `fullGrid()` 168 项全 DENY 基底整表重写，视图只有 30/60 个 FUNCTION 码 ⇒ 30 个不可见码归零；QA 角色 16 个活档位（CASE_REVIEW_DECIDE/SUBMIT/VIEW、defect:handle、defect:report、export:detail、notification:read、plan:approve/assign/edit、proddefect:report、report:export、review:manage、TEST_EXECUTOR_MANAGE、test:quick、waiver:apply）ALLOW/RESTRICTED→DENY；单租户暴露面 300 行中 114 个活档位，每个新建租户同样继承 | AC-9、AC-4、AC-3、AC-21 | `bash logs/teamflow/tf-mwencgru-xkthj3/scripts/qa-r19-ui-save-loss.sh`（当前 exit 1） | exit 0：payload subject 数 = 168（或保存改为增量只写改动格）、`changed=0`、`nonDENY→DENY=0`，且恢复快照与基线逐字节相等 |
| BUG-2 | P2 | 门禁脚本（`_lib.sh grants_of` 与 s2/s3/s5/s7、access a1–a5） | 1) `bash scripts/platform-demo-reset.sh` 回预设；2) `bash scripts/rbac-verify.sh s3`（exit 0）；3) 再跑 `bash scripts/tests/rbac/s2-seed-equivalence.sh` | 同一份数据上任何一段跑完后，s2 的 360+720 格等价恒绿 | s3 用同一份 30-FUNCTION-码视图恢复，把 16 个活档位写成 DENY；s2 由 FUNCTION 行推导 ACTION 期望随即红：`ACTION 720 格不符 = 0：实际 30`。本轮新增触发面：`bash scripts/access-verify.sh` 全量跑完（152/152 绿）后 s2 同样红，`demo-reset` 后复绿 —— 套件跑一次就把演示库改成非预设 | AC-21、AC-16 | `bash logs/teamflow/tf-mwencgru-xkthj3/scripts/qa-r19-suite-idempotency.sh`（当前 exit 1） | exit 0：s2(before)、s2(after) 均 exit 0，且收尾 reset 后 s2 仍 exit 0 |
| BUG-3 | P2 | 演示重置（`scripts/platform-demo-reset.sh` / `DemoResetService`） | 1) 连续执行 `bash scripts/platform-demo-reset.sh`；2) 观察第 41 项「执行器页接口」 | 重置恒 57/57；失败时不改变演示态或明确回滚 | 同日两次出现 `[41] 执行器页接口（HTTP 500，null 条）`，退出码 1（57 项中 1 项失败），紧接重跑即 57/57 全绿；失败瞬间演示库处于半重置态（业务夹具已恢复、授权已回预设，无断言证明一致） | AC-17、AC-16 | `bash logs/teamflow/tf-mwencgru-xkthj3/scripts/t2-reset-custom-role.sh`（exit 0）→ 连续 3 次 `bash scripts/platform-demo-reset.sh`（当前至少一次 exit 1） | 连续 3 次 `bash scripts/platform-demo-reset.sh` 全部 exit 0、57/57；且 `[41]` 不再出现 HTTP 500 |

## 6. P3 观察（craft / 测试保真度，不阻断）

| 编号 | round-3 级 | 元素 | 观察 |
|---|---|---|---|
| OBS-1 | P3 | `t5-grants-fixture.mjs` / `t5-grants-check.mjs` | 夹具按 DESIGN 伪造 168 行，自检断言 draft=168，而真实 GET 只给 144 行/30 个 FUNCTION 码 —— 自检对 BUG-1 完全免疫。建议夹具回放真实响应或直读服务端。 |
| OBS-2 | P3 | `web/src/views/admin/role-permissions/useRoleGrants.ts:57-69` | 注释写「物化 168 项草稿」，实现只物化视图内的项；注释与实现相反，会误导维护者。 |
| OBS-3 | P3 | `scripts/rbac-verify.sh` 全量跑 | 无全局超时：本轮全量单命令在 s4 处 >25 min 无输出（宿主冻结）；建议每段加外层超时，冻结时快速失败。 |
| OBS-4 | P3 | 授权页两栏几何 | `--w-perm-tree:320px` + `--w-perm-panel:692px` = 1012px，正文 1024px 仅余 12px 留白，1024 断点观感偏挤（需人眼复核，见 M1）。 |
| OBS-5 | P3 | 证据图 `02-permission-tree-warning.png` 保存条 | 文案把「与保存一致」与「1 个页面有不生效动作」叠在一句，建议拆两段（沿用上轮观察）。 |
| OBS-6 | P3 | 测试数据卫生（`t2-reset-custom-role.sh`） | 该探针每次运行新建 `t2-<HHMMSS>` 租户但从不删除，历史累积 **59 个**；每个 1680 行，使「全库行数/全库活档位」类统计被放大 60 倍（上轮 6,612 个活档位中 98% 来自探针租户）。本轮已手工清理（`DELETE … WHERE tenant_code LIKE 't2-%'`，库内恢复为单租户 t1 / 1680 行），建议探针补 finally 清理。 |
| OBS-7 | P3 | 宿主冻结 | `platform-test-negative` 首次 `curl: (28) timed out after 25129 ms`、a2 单跑同样 25 s 超时、typecheck 卡 928 s —— 同一台机间歇冻结，属环境限制，非交付缺陷。 |

## 7. 未覆盖 / 环境限制（非交付缺陷）

- **真实浏览器不可用**：沙箱受限令牌导致 Chromium crashpad 起不来（`--no-sandbox`/`--disable-breakpad` 无效），故 `t6-evidence-shots.mjs`（CDP 点击取证）本轮未执行；UI 点击路径以 `t4`/`t5`/`t5-ssr` 静态与 SSR 断言 + `platform-verify` 中的导航断言替代，人眼项转 §8。
- **未重跑**：`platform-unit`（306）、`collab-verify`（263）—— 自上一轮全绿后相关代码未变（`git status` 无对应模块差异），且 `platform-verify 167/167` 已覆盖集成面；若修复轮改动后端须补跑。
- **`access-verify` 全量跑完会污染授权库**（BUG-2 新触发面），清理唯一手段是 `platform-demo-reset`；本轮末尾已 reset，库内 s2 10/10 绿。

## 8. 人工补测清单（环境限制，非交付缺陷）

| # | 项 | 方法 | 工具 | 通过判据 |
|---|---|---|---|---|
| M1 | 授权页 1440×900 无横向滚动 | 打开 `/admin/roles/1/permissions`，比较 `scrollWidth <= clientWidth` | 浏览器/DevTools | 无横滚；320+692 两栏完整可见（一并复核 OBS-4） |
| M2 | 三级授权「≤3 次点击」 | 列表点「授权」→ 默认选中含动作页 → 改一格 → 保存 | 浏览器 | ≤3 次点击到可改态；保存仅 1 个 PUT |
| M3 | 页面 DENY 的黄警与动作「不生效」 | 某页置「拒绝」后看动作行 | 浏览器 | 黄警出现、标「不生效」、保存后按钮真 403 |
| M4 | 409/400 内联而非 toast | 新建重名角色、停用占用中角色、删内置角色 | 浏览器 | 表头 banner / 抽屉红条，无 `ElMessage.error` |
| M5 | 内置角色禁用态与 tooltip | hover 内置角色行操作列 | 浏览器 | 停用/删除灰化、tooltip 出现、点击无反应 |
| M6 | 键盘可达与焦点环 | Tab 遍历授权页三档控件 | 浏览器 + axe | 焦点可见、方向键切档、aria-checked 正确 |
| M7 | 5 张证据图与设计一致性 | 人眼比对 `evidence/01–05` 与 DESIGN §3 | 人眼 | 布局/色板/文案与设计一致（OBS-4/OBS-5 一并复核） |

## 9. 复验方式（修复轮）

1. 修 BUG-1：让 GET 视图与写入键集同源（`RoleGrantGrid.subjects()` 返回全部 168 个 subject，或 PUT 改「基线 + 差异」只写改动格）。
2. `bash logs/teamflow/tf-mwencgru-xkthj3/scripts/qa-r19-ui-save-loss.sh` → exit 0（payload=168 或 changed=0）。
3. `bash logs/teamflow/tf-mwencgru-xkthj3/scripts/qa-r19-suite-idempotency.sh` → exit 0（s2 两次均绿），并补一条 `access-verify → s2` 的幂等断言（本轮新触发面）。
4. `bash logs/teamflow/tf-mwencgru-xkthj3/scripts/qa-r19-unbound-fn-exposure.sh` → exit 0（缺口码上无活档位）。
5. 连续 3 次 `bash scripts/platform-demo-reset.sh` 全 57/57（BUG-3）。
6. 重跑 `bash scripts/rbac-verify.sh`（101/101）、`platform-verify`（167/167）、`access-verify`（152/152）、`lint`（22/22）、`cd web && npm run typecheck`、`npm run build`。
7. 不得改动 `pom.xml`/`package.json`，不得下调任何 `segment_min`，不得为凑绿修改 `s2` 期望值或删除断言。
