# ACCEPTANCE · r20（r19 三个阻断缺陷修复轮）

- 任务夹：`docs/teamflow/20261114-r20-r19-rbac-blocker-fix/`　runId：`tf-mwfso8sv-t4ipv0`　分支：`feat/rbac-admin`（基线 `a85ccd8`）
- 验收对象：PRD §5 AC-1…AC-12 与 §5.1 基线订正；继承回归 AC-13…AC-22（产品累计 AC 索引）
- 工作区：19 个改动文件 + 本任务夹（未提交，提交按 ADR-2026-08-27 由 host 收口）
- 环境：`gateway:8080` / `req-service:8082` / `web:5173` 均 200；MySQL `devops_platform`，tenant=`t1`，10 角色 × 168 行 = 1680
- **结论：通过**。P0/P1/P2 缺陷 0 条；P3 观察 6 条（文档/环境级，不阻塞），见 §5。

## 1. 验收方法与独立复跑

以 PRD 判据为准，P0 项由本阶段在真实栈上重新执行（不是照抄 QA/DEV 结论）；其余引用 QA 全量复跑，代码树自 QA 后未变（`git status` 一致）。

- 编译/类型：`mvn -o -q -Dmaven.repo.local=$PWD/.m2/repository -DskipTests compile` exit 0；`cd web && npm run typecheck` exit 0
- 门禁：`bash scripts/rbac-verify.sh` exit 0，**111/111**（s0 14 · s1 17 · s2 10 · s3 8 · s4 7 · s5 12 · s6 9 · s7 9 · s8 7 · s9 8 · **s2r 10**，下限全部满足）
- 核心判据：`qa-r19-ui-save-loss.sh` exit 0 —— payload **168 distinct**、PUT 200、`changed=0`、`nonDENY→DENY=0`、还原后 168 行逐字节相等
- 读侧：`qa-r20-all-roles-grants.sh` exit 0（10 角色各 168 subject、30 条未绑定档位逐字节不变、全库仍 1680 行）；`role-grants-payload-gap.sh` exit 0（60 FUNCTION + 36 MENU + 72 ACTION = 168）；`qa-r19-unbound-fn-exposure.sh` exit 0（缺口 0 行 / 0 活档位）
- 前端：`t5-grants-check.mjs` 57/57；`t4-roles-page-contract.mjs` 34/34
- 重置：`r20-reset-drift-probe.sh` 8/8（两次真实 reset：已预设「跳过重写」、改坏 1 格报「差异 1 格」后归位）；失败注入 `GATEWAY_PORT=59999 bash scripts/platform-demo-reset.sh` → **exit 1**，`case=transport，HTTP 000`，已执行 39 / 未执行 18，打印停止项与重跑命令
- 网关：`qa-r20-gateway-retry.sh` 8/8（仅 GET、仅 IOException、1 次、无 4xx/5xx 重试、无写方法）
- 改动面：`git diff -- pom.xml '*/pom.xml' web/package.json` 为空；证据 4 张均 1440×900（`sips` 复核）

## 2. 逐条验收（AC-1…AC-12）

| AC | 判据与实测 | 结论 |
|---|---|---|
| AC-1 编译与启动 | compile exit 0、typecheck exit 0；三服务 health 200；flyway=12（QA 复跑） | ✅ |
| AC-2 写侧同源 | `RoleAdminService.java:238-239` 读写基底均为 `stored(tenantId, roleId)`；`fullGrid()` 仅由 `stored()` 使用（`:341`、`:356`）。写探针：只发 **138 唯一 subject** → changed/added/removed=0，30 条未绑定档位不动，DB 每角色 168 行、全库 1680 未放大 | ✅ |
| AC-3 读侧全集 | `MenuRegistry.java:171` 取差集（60−30=30，保序，唯一权源）；`SysViews.java:86/90` 顶层 `unboundFunctions`；GET 168 subject、`unbound∩bound=0`；gap 探针 0；exposure 探针 0 | ✅ |
| AC-4 端到端不丢档 | `qa-r19-ui-save-loss.sh` exit 0（见 §1）：改变 0、无档位被清、快照逐字节还原 | ✅ |
| AC-5 前端可见可保留 | t5 57/57、t4 34/34、typecheck 0；截图 01 左树「未绑定页面的功能码 16/30」+ 右栏 30 行三档可编辑；`useRoleGrants.ts:64/73` flatten 纳入 30 键（draft/payload 168） | ✅ |
| AC-6 rbac 内部幂等 | `rbac-verify` 111/111；s3/s4/s7 写段之后 **s2r** 仍 10/10、hash=`ea21db0e…`（库=预设） | ✅ |
| AC-7 跨套件幂等 | QA：`access-verify` 152/152 后 `rbac-verify s2` 10/10；证据 04 同一 hash | ✅ |
| AC-8 结束断言库=预设 | `rbac-verify.sh:24` 增 `s2r`、`:38` min 8、`:54` 复用 s2 脚本、`:100` 注入 `RBAC_SEGMENT`；断言 101→111 只增，逐格+规范化 SHA-256 双侧复算 | ✅ |
| AC-9 BUG-3 根因与确定性 | 根因定位为网关 `Connection reset`（对端 JVM 停顿的传输层），不再归因数据逻辑；`PlatformRoutes.java:67` 仅 GET+IOException 重试 1 次；QA 连跑 3 次各 57/57 exit 0（证据 03），本轮 drift probe 8/8 复核 | ✅ |
| AC-10 失败语义 | 注入网关失败 exit 1，归类 `transport`，指出停止项、已/未执行清单、重跑命令；恢复后可直接重跑（重置幂等） | ✅ |
| AC-11 既有门禁不缩水 | 实测 167 / 152 / 30（≥28）/ 22 / 307 / 14 / 74 全绿（QA）；`PermissionMatrixGridTest` 600 未动；collab 256/256（基线 263，环境缺 `sys_tenant id=2` 跳过 7 条条件断言，脚本未改，见 P3-1） | ✅（附注） |
| AC-12 证据与提交 | evidence 4/4（1440×900，逐张目检 §4）；改动面 19 文件、pom/package.json diff 为空。提交尚未发生，按 ADR-2026-08-27 由 host 收口（提交信息模板在 TECHNICAL T-G：runId + 任务夹） | ✅（证据达标；提交待 host） |
| §5.1 基线订正 | r19 PRD/TECHNICAL 各新增 1 行、memory 1 行；167/28/152/101/22/14/74/306/263 落地，历史 168/30 标为漂移；但 r19 两行写「r20 起为 109」，实际 **111**（memory 与 r20 TECHNICAL 已写 111） | ✅（P3-2 数字订正） |

继承回归（累计索引 AC-13…AC-22）：s6 9/9、s8 7/7、s7 9/9（本轮实跑）；AC-16/AC-20 同 AC-11；AC-17 见 §1 重置两条；AC-18/AC-22 改动面 19 文件 + 4 证据；AC-19 审计（QA：无差异保存 459→459 不写；DENY→ALLOW 写 `ROLE_PERMISSION_UPDATE` added=1、改回 removed=1，含 before/after 与 `highRiskGranted`）；AC-21 `rbac-verify` 111/111、11 段、失败可定位。全部通过。

## 3. 架构一致性（M3 质量门）

- **权源单一**：`unboundFunctionCodes` 只在 `MenuRegistry.java:171` 定义；消费方为 `RoleAdminService.java:116` 与 `RbacRegistryTest.java:103`；前端只读 API 字段（`useRoleGrants.ts:73`）、脚本只读 API 响应（`_lib.sh:78`），**没有**第二套 60−30 推导。
- **读写同源与纵深防御**：写侧基底改库内现状而非前端补丁——只发 138 唯一 subject 仍不丢 30 档（实测），每角色仍写满 168 行。
- **结构未被撑破**：未绑定分组是顶层键，`modules[]` 仍 36 页、168 未被撑成 169；`AccessLevel/Access` 双份档位语义未动。
- **门禁单一实现**：`s2` 与 `s2r` 复用同一脚本、只注入段名；`driftCells` 复用 `preset()`（不复制公式），与 s2 的 SQL 推导构成刻意交叉验证；网关重试仅 `PlatformRoutes.java:67` 一处。
- **未破坏既有结构**：`TenantContext` 直连 7 处未动、`RoleGuard.validateSubject` 未削弱、无新依赖、无循环依赖。
- 结论：与架构蓝图一致，未发现重复实现/适配漂移/应抽未抽；唯一偏差是测试脚本 `s3-decision-order.sh`（P3-4，已核断言未削弱）。

## 4. 证据目检

- `01-unbound-group.png`：左树页尾「未绑定页面的功能码 16/30」，右栏 30 行三档 + 就地说明，布局无遮挡，保存栏「无改动」置灰——真实 UI 渲染。
- `02-save-noop.png`：把 `req:review` 置允许后保存，toast「新增 1 / 移除 0 / 调整 0」、分组 17/30；文件名语义与内容不符（零改动保存因 `:disabled="!dirty"` 在 UI 不可达，改由 `qa-r19-ui-save-loss.sh` 在 API 层断言 `changed=0`，AC-12 允许该替代并在此注明）。
- `03-reset-x3.png`：3 次 reset 各 57/57、exit 0、5/5 段，含 [53]–[57] 授权回预设。
- `04-s2-after-access.png`：access-verify 152/152 后 s2 10/10，`CANONICAL_HASH=ea21db0e…` 与 s2r 一致。

## 5. 遗留（P3，不阻塞交付）

- **P3-1** collab 256/256 vs 基线 263：环境缺 `sys_tenant id=2`，s8 第二租户段 7 条条件断言按设计跳过；脚本无改动。建议下次环境准备恢复该租户后复跑一次留证。
- **P3-2** r19 PRD/TECHNICAL 两行订正写「109」，实际 **111**（s2r 实跑 10 条、下限 8）；`memory.md` 与 r20 TECHNICAL 均为 111。dev 自检 `tg-baseline-check.sh` 因此 19/21（另一条为 P3-4）。建议下一轮把该订正行数字改为 111（仍只改该行、不动 AC 文本）。
- **P3-3** `02-save-noop.png` 命名与实际语义（1 处改动保存）不符；已在 §4 注明，零改动路径由 API 断言覆盖。
- **P3-4** `scripts/tests/rbac/s3-decision-order.sh` +47 行，不在 TECHNICAL §1.2 文件清单内；PRD AC-12 改动面文本亦漏 `api-gateway`（BUG-3 修复必需，TECHNICAL §1.2 已列）。已核 diff：只是把 403 契约取证改为在 TTL 窗口内取「拿到 403 的那一次响应」，断言未削弱。
- **P3-5** 探针租户残留 `t2-135114` / `t2-141150`（demo reset 不删租户），不影响 t1 验收。
- **P3-6** 19 文件 + 任务夹仍未提交：按 ADR-2026-08-27 由 host 收口；本验收不将未提交工作区背书为「已交付」。

## 6. 判定

r19 的三个阻断缺陷均已修复且可复跑：读写同源（168 subject，只发 138 行不丢 30 档）、套件内/跨套件双幂等（111/111，s2r 库=预设）、reset 3×57/57 且失败可诊断可重入。架构与蓝图一致、既有门禁不缩水（唯 collab 为环境数据漂移）。遗留均为文档/环境级 P3，不改变交付结论。

验收结论：✅ 通过
