# r30 QA 复验报告 · 发布分级审批链（round 2）

- 任务夹：`docs/teamflow/20261118-r30-release-approval-chain/`　runId：`tf-mwm516ql-s4dqpy`
- 分支：`feat/release-multilevel-approval`，HEAD `79e1f1c`；交付改动 32 改 + 23 新增，全部未提交
- 环境：macOS / JDK 25，网关 8080 · req 8082 · deliver 8083 · web 5173；MySQL `devops_platform`，Flyway **V14**（version=14，success=1）
- 结论：**可验收**。上一轮 1×P0 + 4×P1 + 3×P2 全部实测关闭；新增 3 类面 12 条断言全绿；既有 10 项门禁只增不减。P0/P1/P2 未发现缺陷，P3 观察 3 条（不阻塞），人工补测 4 项。

## 1. 复验方法（round 2）

1. 先按原样重跑上一轮缺陷行的 **Check command / Pass criterion**（不替换等价命令）；
2. 再全量重跑 `logs/teamflow/tf-mwm516ql-s4dqpy/scripts/` 下上一轮的 11 个探针（本需求的回归契约）；
3. 然后只补上一轮**未覆盖**的面（见 §3.3），并说明遗漏原因；
4. 最后重算每类缺陷命中数，与修复摘要对比（无分歧）。

## 2. 上一轮缺陷 · 逐条原样复验

| 编号 | 检查命令（原样） | 本轮实测 | 结论 |
|---|---|---|---|
| D-1 P0 | `kill $(cat .run/req-service.pid) && bash scripts/platform-up.sh` + 健康检查 | EXIT=0，`HEALTH=200`，22:58:12 `Started ReqServiceApplication in 8.69s`；新日志段 `RBAC declaration check failed` = **0** 行 | 已修复 |
| D-2 P1 | pm 登录后 `GET/PUT/DELETE /api/req/admin/flow-bindings`；admin 同路径 | 三个方法均 **403** `{"code":"FORBIDDEN","message":"只有管理员或 PMO 可以维护分级审批绑定"}`（JSON 非 500）；admin GET **200**，6 槽全是 `isDefault=true` | 已修复 |
| D-3 P1 | `bash scripts/release-flow-upgrade.sh` | `RELEASE_FLOW_UPGRADE_TOTAL=12 CASE=12 PASS=12 FAIL=0`（4 定义 + 6 槽；两次 seed 计数与每定义 version 均不增） | 已修复 |
| D-4 P1 | `bash scripts/release-flow-verify.sh` | `RELEASE_FLOW_TOTAL=32 CASE=32 PASS=32 FAIL=0`；S1 6/S2 7/S3 6/S4 7/S5 4/S6 2；[04] 换绑 2 节点而在途 3 级、[12] 末级 PUBLISHED、[13] 发布单两级、[14] 驳回回五字段、[23]-[26] 快照、[31] 无 publish 路由 | 已修复 |
| D-5 P1 | `bash logs/teamflow/tf-mwm516ql-s4dqpy/scripts/r30-qa-ac2-release-approve.sh` | `AC2_TOTAL=4 PASS=4 FAIL=0`：release=2025 三级全 APPROVED → `PUBLISHED`；DB `wf_task … node_key='n2' AND status='PENDING' AND biz_type='RELEASE'` = **0**；PMO ACTIVE = **2** | 已修复 |
| D-6 P2 | `mysql -uroot devops_platform -N -B -e "SELECT detail_json FROM wf_history WHERE action='NODE_APPROVED' ORDER BY id DESC LIMIT 1"` | `{"mode":"ANY","nodeKey":"n3","nodeName":"管理层"}`；n1/n2/n3 三条留痕 nodeName 齐全 | 已修复 |
| D-7 P2 | `bash logs/teamflow/tf-mwm516ql-s4dqpy/scripts/r30-qa-ac3-reject-probe.sh` | `rejectedAt=2026-11-18T22:58:33`（与 DB `rejected_at` 一致）、`rejectedNodeKey=n1`、`rejectedNodeName=产品经理会签`、levels `REJECTED,WAITING,WAITING` | 已修复 |
| D-8 P2 | `bash scripts/release-flow-verify.sh`（[14]/[26]/[27] 三项） | 32/32 内全 PASS，无 405；空 orderId 走 `FIXTURE_MISSING`，反引号已转义 | 已修复 |
| D-9 P3 | `grep -n 审批链绑定 web/src/views/admin/AdminWorkflowsView.vue` + `grep -l 审批链绑定 web/dist/assets/*.js` | `AdminWorkflowsView.vue:322` 入口按钮存在，构建产物含该文案 | 上轮误报，关闭 |
| D-10 P3 | `git status --porcelain` | 32 改 + 23 新，全部为 r30 交付文件；`NO_NOISE`（`.run/ logs/ web/dist target/` 命中 0）；`.gitignore:15 .run/`、`:19 logs/`、`web/.gitignore:2 dist/` 覆盖 | 关闭（收口由验收提交） |

## 3. 用例与结果

### 3.1 上一轮探针回归（逐字 pass 数，全部 EXIT=0）

| 探针 | 结果 |
|---|---|
| `r30-flow-frontend-check.mjs` | 37/37 |
| `dev-r30-out-of-scope-check.sh` | 10/10 |
| `t1-static-contract.sh` | 33/33 |
| `t2-flow-chain-contract.mjs` | 36/36 |
| `probe-positive-chain.sh` | `CHAIN_OK=0`，submit 200 `{"status":"PENDING_APPROVAL"}` |
| `d5-d4-probe.sh` | 换绑后新单 2 节点、reset 恢复默认（PASS） |
| `d8-order-probe.sh` | `create_order=201`（id=669） |
| `T2DigestProbe`（java） | 6/6 |
| `SnapshotE2E`（java） | 16/16（round 连续、hash 64 hex、replay 一致、replay(3)→404、仓储无 update/delete） |
| `r30-qa-ac2-release-approve.sh` | 4/4 |
| `r30-qa-ac3-reject-probe.sh` | PASS（驳回五字段 + 回 PLAN） |

> 探针修缮仅两处，均为 QA 工具自身误判（非交付缺陷）：`t1-static-contract.sh` S4.4 原 `hasnot 'workflow:'` 把 `FlowBindingService.java:62` 注释误判；`t2-flow-chain-contract.mjs` S4.8 原见 `MessageDigest` 即红，已精确到 `stale()` 方法体（段 digest 单点仍在 `FlowChainService.digests()`，payloadHash 的 sha256 例外保留）。

### 3.2 上一轮缺陷命中数复核

| 缺陷类 | 修复摘要声称 | 本轮实测 | 一致 |
|---|---|---|---|
| 启动崩溃（RBAC fail-fast） | 已修 | 重启 EXIT=0、新日志 0 违规 | ✅ |
| 绑定接口越权/500 | 已修 | 未授权 403 JSON、授权 200 | ✅ |
| 升级/门禁红 | 11/12 → 已修、24/32 → 已修 | 12/12、32/32 | ✅ |
| PMO 人数爆炸 | 115→2 | PMO ACTIVE=2，n2 待办 0 | ✅ |
| 留痕/驳回字段缺失 | 已修 | nodeName 三值齐全、rejectedAt 非 null | ✅ |

### 3.3 本轮新增面（上一轮遗漏，`r30-qa-r2-extra-probes.sh` 12/12）

| 断言组 | 覆盖 | 结果 |
|---|---|---|
| AC-7 回滚单号服务端校验 | 创建 ROLLBACK 缺单号 400 `detail.field=previousDeployOrderCode`；PATCH 缺单号/不存在单号/同租户 DRAFT 单号 三者均 400；三次 400 后等级仍 NORMAL（无半写） | 6/6 |
| AC-5 改等级窗口 | 501 字原因 400；PLAN 内改 EMERGENCY 200；`POST /lock` 后改等级 **409 RELEASE_LOCKED** | 3/3 |
| AC-8 缺段降级 | 无计划/报告/门的版本详情仍 **200**，testPlan/signoff/qualityGate 三段均 `MISSING`（不 500、不编造），准入段状态存在 | 3/3 |

遗漏原因：上一轮只验了「紧急原因空 → 400」，未测回滚单号这条**独立校验分支**与锁定后 409 窗口；缺段降级只做了代码走读。

### 3.4 既有门禁回归（下限只增不减，全部 EXIT=0）

| 门禁 | 本轮 | 旧基线 |
|---|---|---|
| `make release-flow-verify` | 32/32 | 新增（自声明 32） |
| `make release-flow-upgrade` | 12/12 | 新增（自声明 12） |
| `scripts/platform-verify.sh` | 194/194 | 193（+1） |
| `scripts/platform-test-negative.sh` | 130/130 | 130 |
| `scripts/access-verify.sh` | 206/206 | 206 |
| `scripts/rbac-verify.sh` | 113/113（CANONICAL_HASH `f0e1137b…`） | 113 |
| `scripts/platform-unit.sh` | 375/375 | 371 |
| `scripts/tests/lint.sh` | 22/22（L9 已绿） | 22 |
| `scripts/quality-verify.sh` | 348/348（q0 31/q1 67/q2 39/q3 47/q4 39/q5 54/q6 26/q7 45） | 348 |
| `scripts/integration-verify.sh` | 231/231（s5-admission 28 已补锁定前置） | 231 |
| `scripts/platform-demo-reset.sh` | 70/70（含新增第 [70] 条账号基线断言） | 69 |
| `cd web && npm run typecheck && npm run build` | EXIT 0，✓ built | EXIT 0 |
| AC-21 `FORCE_RELEASE_RETIRED` | `DeployOrderService.java:579` 恒 409，platform-verify/negative/q4 三条门禁覆盖且全绿 | 保持 |

### 3.5 架构核查（0）与接口一致性（0a）

- **聚合单点**：`new FlowChainViews.FlowChain(` 仅 `FlowChainService.java` 2 处；`new FlowChainViews.ApprovalLevel(` 仅 `ApprovalLevelProjector.java` 1 处 → 无第二套聚合/等级推导。
- **门判定单点**：req 侧无 `QG-0x`/`PASS_WITH_WAIVER` 判定代码（仅 `ReleaseEntity.java:67` 注释）；准入统一 `AdmissionPolicy.sort` 与 `admission.evaluate/snapshot`。
- **无后门**：`ReleaseController`/`DeployOrderController` 无 publish 路由；快照仓储无 update/delete。
- **迁移受控**：migration 目录仅新增 `V14__release_flow_chain.sql`；`git diff --stat HEAD -- pom.xml web/package.json` 为空。
- **接口逐字符比对全部匹配**：`admin.ts:214/222/226` ↔ `FlowBindingController`（GET/PUT/DELETE，`SaveCmd.definitionId`）；`req.ts:337` PATCH `/releases/{id}/level` ↔ `ReleaseController.java:89`，payload ↔ `ReleaseService.java:830 record UpdateLevel(...)`；`req.ts:387/392` ↔ `DeployOrderController.java:100/106`；`DeliverFlowEvidenceClient.java:31` 路径/参数名 ↔ `FlowEvidenceController.java:39`。
- **执行证据（0a2）**：`release-flow-verify.sh` 在真栈走完「建版本 → 挂需求 → 计划 PASS → 三签 → 评门 → 建单 9 项准入 → submit 固化快照 → 两级/三级审批 → PUBLISHED」，32 条断言 PASS==TOTAL；非存在性检查。

## 4. 缺陷表（仅仍开放项）

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| O-1 | P3 | 前端/工艺 | 打开 `/admin/flow-bindings`，鼠标悬停卡片按钮 | 有 hover 反馈 | 无局部 `:hover`，仅全局 `:focus-visible`（与既有 `AdminWorkflowsView.vue` 一致，全局无 button hover 规则） | AC-6/AC-9 | - | - |
| O-2 | P3 | 前端/工艺 | 版本未发起审批时看侧栏审批阶梯 | 空态给出「发起审批」入口 | 仅一行「尚未发起审批 · 发起后逐级推进」，入口需回到侧栏按钮 | AC-9 | - | - |
| O-3 | P3 | 前端/工艺 | 查 `web/src/styles/tokens.css` | r30 新令牌登记进产品级令牌表 | 5 个令牌（`--h-chain-seg/--w-node-key/--h-level-row/--w-hash/--h-seg-grid`）未收口，三组件自持声明；`--h-seg-grid` 未被引用 | AC-9 | - | - |

**P0/P1/P2：未发现缺陷。**

## 5. 人工补测清单（环境限制，非交付缺陷）

| 项 | 验收标准 | 方法 / 工具 |
|---|---|---|
| AC-9 1280×800 四段可读无横滚 | `document.documentElement.scrollWidth <= clientWidth`，四段同屏 | Chrome DevTools 设备模式 1280×800，打开版本详情；沙箱无法启动浏览器 |
| AC-18 ≥6 张证据截图 | `DEMO-RELEASE-FLOW.md` 8 个截图位至少 6 张入 `evidence/` | 按 DEMO 8 步操作后截图；任务夹当前无 `evidence/` 目录 |
| 视觉/动效 | 快照卡 hash 行 600ms 闪烁、MISSING 虚线、连接符层级 | 人眼目测（`prefers-reduced-motion` 已按代码关闭动画） |
| 无障碍朗读 | `aria-current="step"`、`aria-live` 只读条可被读出 | VoiceOver / NVDA |

复验过程中的两条**非交付**记录：① 首次 `rbac-verify` 被我加 `\| tail -5` 管道并中途 kill，残留探针角色 `S7ROLE_18615` 使下一次 s0/s2 红；`platform-demo-reset` 后 113/113 全绿——门禁须在干净态跑、禁止管道截断。② `quality-verify` 首次调用 q0 段一次性 rc≠0 且无输出，单跑与二次全量均 31/31、348/348，判为环境抖动。

## 6. 结论

- **可验收**：P0/P1/P2 = 0；上一轮 8 项缺陷全部关闭，D-9 误报、D-10 提交面无噪声。
- 新增面 12/12、回归探针 11 项全绿、13 项门禁全绿且只增不减；架构与接口一致性核查无偏差。
- 待人工：1280×800 视觉、≥6 张演示截图、动效目测、屏幕阅读器；交付后演示数据已复位（`platform-demo-reset` 70/70）。
