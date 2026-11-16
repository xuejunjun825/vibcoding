# 验收报告 · 20261113-r19 三层授权（功能码 / 页面 / 动作）收尾

- 验收人：产品经理（验收）｜日期：2026-11-14
- 分支 `feat/rbac-admin`（HEAD `5b7ba9d`，提交说明即「断点存档，编译未绿」）｜工作区 **47 项未提交**（39 M + 8 ??）
- 任务夹 `docs/teamflow/20261113-r19/`：PRD（273 行，AC-1…AC-18）· DESIGN · TECHNICAL · QA-REPORT（第 3 轮复验）· evidence/01–05；本 `ACCEPTANCE.md` 为本次唯一新增
- 判定依据：QA 第 3 轮复验的 exit code 实测 + 本次收口抽检（§4）。**按验收规则未复跑 QA 全量套件**；未由我实测的项在表中逐条标注「依赖 QA 证据」

## 1. 结论：不可验收

三条 P0 验收项未达成，其中 AC-9 是**数据面缺陷**，不是展示问题：

1. **BUG-1（P1，架构级）** 读侧 `grants()` 只遍历 `MenuRegistry` 的页面绑定（30/60 个 FUNCTION 码），写侧 `saveGrants()` 用 `fullGrid()` 168 项「全 DENY 基底」整表重写 ⇒ **用户原样打开授权页点保存，就会把视图里不存在的 30 个 FUNCTION 档位清零**。干净单租户库实测：QA 角色 16 个活档位 ALLOW/RESTRICTED→DENY；单租户暴露 300 行 / 114 个活档位（占该租户活档位 15%）；每个新建租户继承同一缺陷。这不是"要重新登录"，而是保存即失权且无提示。
2. **BUG-2（P2）** 门禁非幂等：s3 跑完再跑 s2 必红；本轮新增触发面 —— `access-verify` 全量跑完（152/152 绿）后 s2 同样红（`ACTION 720 格不符 = 0：实际 30`）。即"跑一次套件就把演示库改成非预设状态"，与 AC-16/AC-17 的"可重复、不缩水"直接冲突。
3. **BUG-3（P2）** `platform-demo-reset.sh` 间歇 1/57 失败（`[41] 执行器页接口 HTTP 500`），退出码 1，失败瞬间演示库处于半重置态，无回滚断言。

另有交付面阻塞：**47 项修复全部未提交**，HEAD `5b7ba9d` 自身编译红（`RoleAdminService.java:212/253` 两处类型错误）。本次判定只对"工作区"成立，对"已交付的提交"不成立。

## 2. 逐条验收（AC-1 … AC-18）

| AC | 判据（摘要） | 结果 | 证据（来源） |
|---|---|---|---|
| AC-1 编译与启动 | 工作区 `mvn compile` exit 0；`fail-fast=true` 启动，violation=0 | 通过（仅工作区） | dev T1 mvn exit 0；QA `platform-verify 167/167`（gateway/req/deliver/web 在跑，flyway=12）；HEAD 仍红 + 修复未提交（本次 `git status`） |
| AC-2 V12 形状 | 只 DDL、7 列 + `uk_rp`、三表、版本 12 | 通过 | V12 7032B / 6 条 CREATE·ALTER（本次）；`s0` exit 0 / 14 项（本次复跑）；QA s0 14 |
| AC-3 种子数量与幂等 | 每租户 1680 = 600+360+720，每角色 168，INSERT IGNORE | 通过（预设数据） | QA s0/s2/s3；单租户 `t1` 1680 行；但保存路径会破坏其中 30 个 FUNCTION 行（见 BUG-1） |
| AC-4 升级行为等价 | 600 格逐格 = 矩阵；360/720 布尔等价 | 通过（仅 reset 后） | QA `s2 10/10`（`CANONICAL_HASH=ea21db0e…`）；`PermissionMatrixGridTest` 600 未改；非幂等见 BUG-2 |
| AC-5 单一判定与三序 | 唯一入口、运行时直连 = 0、RESTRICTED 只收窄 DataScope | 通过 | QA s3 8/8；lint L19 直连 0 |
| AC-6 缓存/epoch/时效 | TTL ≤ 60s、撤销后第 1 次请求 403 | 通过 | QA s4 7/7；evidence/04（撤销后就地提示、不跳 /403，本次目视） |
| AC-7 写端点动作声明 | s9 全 0、fail-fast 默认 true | 通过 | QA s9 8/8（161 写端点由 `PermissionRouteTable` 覆盖） |
| AC-8 角色 CRUD 与停用 | 五端点、内置不可删、停用下次请求失权 | 通过 | QA s5 12/12；t4 34/34（表格 942 ≤ 992） |
| AC-9 授权读写与管理页 | GET 覆盖三类、PUT 全量口径、回存不丢档 | **未通过** | BUG-1：`qa-r19-ui-save-loss.sh` exit 1（payload 138 subject vs 网格 168，changed=16，nonDENY→DENY=16）；`role-grants-payload-gap.sh` 144 vs 168；`qa-r19-unbound-fn-exposure.sh` 缺口 30 码 / 300 行 / 114 活档位 |
| AC-10 防锁死与高危 | 409 `ADMIN_LOCKOUT_RISK` 且库不变；高危二次确认 + 审计 | 通过 | QA s5 12/12 |
| AC-11 导航/按钮/会话 | 两入口、`hasMenu` 双条件、a3 三向一致 | 通过 | QA `access 152/152`（a3 10 / a4 16）；`a3` `SIDEBAR_BASELINE=37`（本次）；typecheck/build 0 |
| AC-12 只读权限总览 | 600 格只读、无写映射、同源刷新 | 通过 | lint L20；t4 34/34；`AdminPermissionsView` 无写控件（QA §4） |
| AC-13 人员与组织闭环 | 校验后写、409、move-users 单事务 | 通过 | QA s6 9/9；t1 7/7；t2 18/18 |
| AC-14 审计 | ROLE_/USER_/ORG_ + highRiskGranted，无差异不写 | 通过 | QA s8 7/7 |
| AC-15 负向 9 例 | 9 条全部按预期被拒 | 通过 | QA s7 9/9；negative 28/28 |
| AC-16 门禁全绿不缩水 | s0–s9 ≥ 60、既有门禁不回归、r17 契约不回归 | **未通过** | rbac 101/101（s0 14·s1 17·s2 10·s3 8·s4 7·s5 12·s6 9·s7 9·s8 7·s9 8）≥ 60；但套件非幂等（BUG-2）。**基线数字偏差**：PRD 写 `platform-verify 168` / `negative 30`，实际 167 / 28；本次确认这两个脚本本轮未被修改（`git diff` 无该文件），属既有基线漂移而非本轮删断言，仍须订正 PRD/记忆。`platform-unit(306)`、`collab s0–s8` 本轮未复跑（QA §7）→ 该两项未验证 |
| AC-17 重置与预设幂等 | 连跑两次幂等、预设 1680 行 | **未通过** | BUG-2（跑一次套件即污染，需 reset 才能复绿）；BUG-3（reset 首次 56/57、间歇 HTTP 500） |
| AC-18 改动面与证据 | 限定目录、pom/package.json diff 空、≥4 图、ACCEPTANCE | 部分通过 | 47 项未提交（交付未落 commit）；pom/package.json diff 空（本次核）；5 张图 + `qa-evidence-freshness.sh` exit 0（本次核）；ACCEPTANCE 本次补写 |

合计：通过 14 · 未通过 3（AC-9/16/17）· 部分通过 1（AC-18）。

## 3. 仍开放的阻断缺陷

| 编号 | 严重级 | 模块 | 破坏了什么 | 检测命令（当前 exit 1） | 通过判据 |
|---|---|---|---|---|---|
| BUG-1 | **P1** | 架构：`RoleAdminService` 读写键集双推导（`RoleAdminService.java:75-108` vs `:226`/`:328-340`） | AC-9(P0)、AC-3/AC-4 的数据完整性：原样保存即清零 30 个不可见 FUNCTION 档位（QA 角色 16 个活档位）；全库/新租户同一暴露面；无审计可见的"被清零"提示 | `bash logs/teamflow/tf-mwencgru-xkthj3/scripts/qa-r19-ui-save-loss.sh` | exit 0：payload subject = 168（或改为只写改动格），`changed=0`、`nonDENY→DENY=0`，恢复快照与基线逐字节相等 |
| BUG-2 | **P2** | 门禁脚本（`scripts/tests/rbac/_lib.sh` 及 s2/s3/s5/s7、`access-verify` a1–a5） | AC-16(P0)、AC-17(P0)：同一份数据上任一段跑完后 s2 不再成立；套件本身改变演示库 | `bash logs/teamflow/tf-mwencgru-xkthj3/scripts/qa-r19-suite-idempotency.sh` | exit 0：s2(before)/s2(after) 均 0，收尾 reset 后 s2 仍 0 |
| BUG-3 | **P2** | 演示重置（`scripts/platform-demo-reset.sh` / `DemoResetService`） | AC-17(P0)：reset 非确定成功，失败时演示态半重置、无回滚 | `bash logs/teamflow/tf-mwencgru-xkthj3/scripts/t2-reset-custom-role.sh`（exit 0）→ 连续 3 次 `bash scripts/platform-demo-reset.sh` | 3 次均 exit 0 / 57/57，`[41]` 不再 HTTP 500 |

## 4. 本次抽检记录（未复跑 QA 全量套件）

| # | 命令/动作 | 结果 | 用途 |
|---|---|---|---|
| 1 | `git status --porcelain \| wc -l` / `git log -1` | 47 / HEAD 5b7ba9d | 交付面未提交、HEAD 编译红 |
| 2 | `bash scripts/tests/rbac/s0-schema-migration.sh` | exit 0（14/14） | V12 形状在库内成立 |
| 3 | `ls -l V12*.sql` + DDL 计数 | 7032B / 6 | 迁移存在且只做 DDL |
| 4 | `grep -n "fullGrid\|grants(" req-service/.../sys/RoleAdminService.java` | `:75` / `:226` / `:328-340` | BUG-1 根因在代码中可直接定位 |
| 5 | `bash logs/.../qa-evidence-freshness.sh` | exit 0（5/5） | 证据图晚于全部前端源码 |
| 6 | `git diff --stat -- pom.xml */pom.xml web/package.json` | 空 | AC-18 依赖面未变 |
| 7 | 目视 evidence/02 与 04（1440×900） | — | 两栏完整、无横滚迹象；页面 DENY 时动作标「不生效」；保存条与黄警同屏，未遮挡 |

## 5. 架构一致性（M3）

- **蓝图落位抽查 14 项成立**（QA §4）：V12 只 DDL（重放零副作用，1680 行由 Java 常量派生）· `RbacSeeder.seedRegistries/seedTenant/preset` · `RbacBootstrap` 逐租户独立事务 · `TenantProvisioner` 建租户同事务 · `DemoResetService` 回预设 · tokens 14 个新变量只增不改 · `AdminLayout` 条目 NavGuard 化 · `SideNav`/`TopNav` 补 `hasMenu` · `AdminPermissionsView` 只读化 · 两页 + `useRoleGrants`/`AccessSegmented`。
- **缺失抽象（P1 根因，必须返工）**：同一份「subject 全集」被派生两次且互不校验 —— 读 `grants()`（`MenuRegistry` 页面绑定 = 30 个 FUNCTION 码），写 `fullGrid()` = `PermissionMatrix.functions()`(60) + `MenuRegistry.codes()`(36) + `ActionRegistry.codes()`(72) = 168，并在 `saveGrants()` 里作为全 DENY 基底整表重写。缺一个 `RoleGrantGrid.subjects()` 式的唯一全集来源。这属于「应抽取未抽取」，不是小修。
- 无其它重复实现（准入只有 `navVisible`、三档只有 `AccessSegmented`、写入点只有 `RbacSeeder.seedTenant`）；接口一致性 50/50。
- 文档偏差（P3，任务夹不可变仅记录）：`TECHNICAL.md:114` 把 `accessForAny` 写成「任一 DENY ⇒ DENY」，实际 ALLOW 优先；DENY 优先是 `PermissionService.merge` 的多角色并集规则。

## 6. 未覆盖 / 环境限制（非交付缺陷，但影响证据强度）

- `platform-unit(306)`、`collab-verify s0–s8` 本轮未复跑（QA §7）；AC-16 对应两项按「未验证」计。
- 真实浏览器不可用（沙箱令牌致 Chromium crashpad 起不来），`t6-evidence-shots.mjs` 未执行；UI 点击路径由 t4/t5/t5-ssr 静态与 SSR 断言 + 本次截图目视替代，M1–M5 人工补测仍待做（1440 无横滚、≤3 次点击、409 内联、内置角色 tooltip 等）。
- 宿主间歇冻结：`curl: (28) timed out 25129 ms`、typecheck 卡 928s（QA OBS-7）；`access-verify` 上轮因冻结未跑完、本轮已完成 152/152。
- 数据卫生：探针租户 `t2-*` 曾累积 59 个（QA 已清理），"全库"类统计此前被放大约 60 倍；本报告一律采用单租户口径。

## 7. 决策请求（人工拍板，本报告不代替产品决策）

- **A 返工后复验（建议优先评估）**：修 BUG-1（抽取 `RoleGrantGrid.subjects()` 单一全集，GET 回 168 行 / 写侧同源或改增量写），同时修 BUG-2 幂等与 BUG-3 reset 事务/重试；复验命令：`rbac-verify s0–s9` + `qa-r19-suite-idempotency.sh` + 连续 3 次 `platform-demo-reset.sh` + `qa-r19-ui-save-loss.sh`，并要求 47 项修复落成一个提交。
- **B 带已知问题接受**：须书面确认风险 ——「在授权页保存会把 30 个未展示的 FUNCTION 档位（含 16 个活档位）清零」，并约定生产环境禁用该保存路径；同时接受门禁非幂等与 reset 间歇失败。此选项与 AC-9/16/17 的 P0 定位冲突，需变更验收口径。
- **C 弃单/回退**：回到 r18 断点（保留骨架，不交付两页与新种子），缺陷转入 backlog。
- 说明：本次为 QA 返工轮次耗尽后的"已知问题验收"，host 已将结果强制为 needs-human；本轮不提交、不合并，本场景不存在"通过"结论。

## 8. 交付边界

- 仅新增本文件；任务夹内 PRD/DESIGN/TECHNICAL/QA-REPORT/evidence 均未改动，`memory.md` 未改（无新团队约定；`TECHNICAL.md:114` 的偏差已在 §5 记录，待返工轮顺带订正）。
- 47 项未提交改动仍按 ADR-2026-08-27 由 host 收口；本验收不对未提交工作区背书为"已交付"。

验收结论：❌ 不通过
