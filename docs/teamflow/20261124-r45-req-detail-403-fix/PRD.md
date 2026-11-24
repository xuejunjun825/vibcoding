<!-- meta: summary="修复非审批参与人打开需求详情被整页踢到 /403：审批实例等辅助探测改为 silentForbidden + 审批区内联降级，服务端 403 语义不放宽，并用 lint L23 与 access a8 钉死同类残留" -->
基线依赖：docs/teamflow/20261124-r44-null-safe-enums（HEAD=e842192；门禁基线 platform 199 / negative 132 / access 206 / rbac 113 / gate-order 21 / release-flow 44 / demo-reset 80 / editability 36 / change 46 / lint 24 / unit 437，本轮只增不减；审批可见性语义来自 r8/r26，不放宽）

# 需求详情页非审批参与人 403 误跳修复

## 1. 背景与目标

### 1.1 现场

演示时暴露：研发 `dev1`、测试 `qa1`、厂商 `vendor1` 打开**自己可见**的需求详情页，整页被替换成 403 页（提示「无权查看该审批实例」）。后果不是"少看一个卡片"，而是：

- 研发看不到自己负责的需求详情，无法从详情页点「开始开发」；
- 测试无法从详情页点「提交测试 / 测试通过」；
- 厂商看不到自己承接的需求，演示链路在第二个角色就断。

### 1.2 根因（已定位，main=e842192）

这是**把"审批单据可见性"误当成"需求详情页准入"**的三层叠加，服务端语义本身是对的：

1. 服务端：`req-service/src/main/java/com/example/req/approval/ApprovalCenterService.java:155-163` 的 `visibleInstance()` 只允许「同租户 + 发起人/审批人/抄送人/管理员」，其余 403 `无权查看该审批实例`。**这个语义正确，不许放宽。**
2. 前端详情页：`web/src/views/req/RequirementDetailView.vue` 的 `loadApproval()`（当前 :292-313）与 `loadEstimateApproval()`（当前 :345-360）把审批实例当**辅助信息**加载，调用 `getApprovalInstance()`（:298 / :305 / :356）。
3. 请求层：`web/src/api/req.ts:220` 的 `getApprovalInstance()` 没有 `silentForbidden`；`web/src/api/client.ts:180-186` 对**任何** 403 先 `navigate('/403')` 再抛错——页面即使 catch 住，URL 已经换了。

一句话：**403 只该让"审批区"降级，不该让"页面"消失。**

### 1.3 这是一个"约定早已存在、执行没被守住"的缺陷

`docs/teamflow/memory.md` 的 r8 约定已写明：页面内「读不到也不阻断」的调用（集成配置、执行器台账、审批角标、字典）必须声明 `silentForbidden`，403 就地降级、绝不触发全局 `/403`。漏的是**详情页里"审批实例/评估审批"这一类**，而 a5 段的调用点审计没有覆盖它。因此本轮除了修现场，还要把这一类纳入静态门禁，避免下一次换个页面复发。

### 1.4 目标（可核验）

- G1：dev1 / qa1 / vendor1 打开各自可见的需求详情页，URL 保持 `/requirements/{id}`，页面正常渲染（标题、状态、动作按钮），审批区显示内联说明，**不跳转、不白屏、不永久 loading**。
- G2：服务端审批可见性判定与 403 响应**零改动**；非参与人直接调 `/api/req/approvals/instances/{id}` 仍 403。
- G3：详情页同类"辅助探测"裸调用清零，并有能变红的静态门禁（lint L23）与运行时门禁（access a8）。

### 1.5 产品语义

**审批可见性只决定"审批区能不能看"，绝不决定"需求详情能不能打开"。**

本需求不取代任何既有 AC：r8 的约定文字不变，本轮是补执行、补门禁；r43 的可编辑性三数组、r26 的评估门、r17 的动作 403 就地处理均不改变。

## 2. 用户故事与验收标准

> AC 编号仅在本任务夹内有效。每条均给出可执行的通过判据；"静态"= 不启动服务即可验证，"运行时"= 需 `make platform-up && make platform-seed`（必要时 `make platform-demo-reset`）。

### US-1 研发/测试打开自己负责的需求详情（P0）

**AC-1（P0）详情页不再整页跳 403。** dev1 与 qa1 分别真实登录，打开各自可见、且审批实例对自己 403 的需求详情（含直接深链与刷新）：最终路径始终是 `/requirements/{id}`，页面标题/状态/动作条正常渲染，任何时刻不出现 `/403`；浏览器后退不会被 403 页污染。判据：截图（AC-12）+ a8 静态保证（详情页所有辅助探测均 silent）+ 手工 URL 检查；不得只以"接口 200"冒充页面正常。

**AC-2（P0）403 时审批区内联降级。** 仅当错误 `status === 403`：`approvalLoading` 结束，审批页签内显示逐字「无权查看审批进度（仅发起人/审批人/抄送人可见）」，不弹 toast、不显示"加载失败/请重试"这类技术错误、不残留骨架屏；评估审批（`detail.estimate.instanceId` 直取）403 时同一文案出现在评估块内，不把整个「审批」页签打成错误态。该文案在 `web/src` 内只出现 **1 次**，由同一个常量供两处引用（禁止两处各写一份）。

**AC-3（P0）非 403 错误不被误降级。** 网络错误/500 时仍走统一错误提示（`messageOf` 原文）并保留现有重试能力；只有 403 分支写内联文案。判据：代码审查 + a8 S3 断言 403 分支按 `status === 403` 判定且文案只在该分支赋值。

**AC-4（P1）动作按钮不被误伤。** dev1 在 RD `TODO`（评估已通过）状态下能看到并点击「开始开发」，请求 200、状态前进到 `IN_DEV`、无 `/403`；qa1 在可测状态下「提交测试 / 测试通过」同理。`allowedActions`、`RequirementActionMatrix`、`actOnRequirement`（已 silent）均不改；既有动作用例全绿即为通过。

### US-2 厂商打开自己承接的需求（P0）

**AC-5（P0）vendor1 同策略。** vendor1 打开 VEN-A 承接的需求详情（demo-reset 后可见 3 条）不跳 403，审批区同文案降级；同时 vendor1 对**自己非参与**的内部审批实例仍 403（负向见 AC-6）。判据：a8 R5/R6 + 可选截图；厂商数据隔离不因本轮放宽。

### US-3 权限与安全边界不倒退（P0）

**AC-6（P0）服务端语义零改动，负向保留。** `visibleInstance()` 的可见集合仍为「同租户 + 发起人/审批人/抄送人/管理员」；错误仍为 403 `FORBIDDEN` +「无权查看该审批实例」，不返回实例内容。dev1/qa1/vendor1 各对一个自己非参与的实例 `GET /api/req/approvals/instances/{id}` → 403；跨租户仍 `TENANT_MISMATCH`（既有用例）。判据：`git diff -- req-service/src/main/java` 为空 + a8 N1–N3 + curl 原文（AC-13）。

**AC-7（P0）主数据不静默（防过度降级）。** `getRequirement(id)` 不声明 `silentForbidden`：需求本身无权限时仍按既有语义落到 403 页/明确错误态，不得出现"空壳详情页"。`client.ts` 的 `RequestOptions.silentForbidden` 注释同时写明「辅助探测必须显式声明；主数据请求不得声明」。判据：静态断言（a8 S2 同时检查 `getRequirement` 调用点无 silent）+ 现有越权深链用例。

### US-4 同类问题不再复发（P1）

**AC-8（P1）详情页辅助探测清零 + 注释。** 详情页及其子组件的辅助请求 —— `getApprovalInstance` ×3、`listApprovalMine` ×1（"我的待办"探测）、`listRequirementChanges`（变更计数，已 silent）、字典（已 silent）—— 全部在同一次 http 调用上声明 `silentForbidden: true`；`web/src/api/client.ts` 的 `silentForbidden` 注释升级为约定说明；`getApprovalInstance` 上方注明"可选探测"。范围外且本轮不改：`RequirementChangeDetailView.vue:115`、`ApprovalCenterView.vue:278`（参与人页面，保留默认 403 跳转），列入 P2 备选。

**AC-9（P0）lint L23 静态门禁。** `scripts/tests/lint.sh` 新增 L23（1 条 `run_check`，内含 3 个判据）：(a) 详情页 `getApprovalInstance(` 调用点数量 == 带 `silentForbidden` 的数量（零裸调用）；(b) 详情页不出现 `http.get` / `http.post` 裸调用（页面只走 api 层）；(c) 负向自检——内存夹具含一处裸调用时判据必须返回非 0，自检失败即本条判红。lint 24 → ≥25，`make lint` 25/25。

**AC-10（P0）access 新增 a8 段（13 条）。** 新增 `scripts/tests/access/a8-detail-optional-probe.mjs`，`ALL_SEGMENTS` 增加 `a8`，`segment_min a8=13`，总量下限 206 → 219；a8 未产出计数行时聚合器判红（同 a5/a6 口径，不把"0 条"当通过）。13 条构成：

| 编号 | 类型 | 断言 |
|---|---|---|
| S1 | 静态 | `api/req.ts` 的 `getApprovalInstance` 声明可选 options 且第三参透传给 `http.get`（不传时默认仍跳 403） |
| S2 | 静态 | 详情页 3 个 `getApprovalInstance` + `listApprovalMine` 全带 `silentForbidden`；`getRequirement` 不带 |
| S3 | 静态 | 403 分支按 `status === 403` 判定，内联文案字面量唯一 |
| S4 | 静态 | `client.ts` 注释锚点存在；详情页无 `navigate('/403')` |
| R1–R2 | 运行时 | dev1 真实登录：可见需求列表 ≥1；任取其一详情 API 200 |
| R3–R4 | 运行时 | qa1 同上 |
| R5–R6 | 运行时 | vendor1 同上 |
| N1–N3 | 运行时 | dev1/qa1/vendor1 对"自己非参与"的审批实例 GET → 403 `FORBIDDEN` 且 message 逐字 |

运行时若某角色无可见需求，该段直接判红并输出「夹具缺失：先 make platform-demo-reset」，**禁止 skip、禁止删断言**。账号可用 `A8_DEV_USER` / `A8_QA_USER` / `A8_VENDOR_USER` 覆盖，默认 dev1/qa1/vendor1，密码沿用演示口令。

### US-5 交付可核验（P1）

**AC-11（P0）门禁不缩水。** 全量复跑：`platform-verify` ≥199、`platform-test-negative` ≥132、`access` ≥219、`rbac` 113、`gate-order` ≥21、`release-flow` 44、`demo-reset` 80（连跑两次逐键一致）、`editability` 36、`change` 46、`lint` ≥25、`platform-unit` ≥437、`web` typecheck+build 0 error；两次连跑总数一致。不得改小分母、删断言或靠 skip 通过。

**AC-12（P1）截图证据。** `docs/teamflow/20261124-r45-req-detail-403-fix/evidence/dev1-detail.png`、`qa1-detail.png`：1280×800（或同比例），**同一条需求**，画面同时能看见需求标题/状态/动作条 + 审批页签内的逐字文案；能辨认账号身份（页头用户名或账号区），不得用 admin 代截，不得只截局部。截图所用需求必须真实命中一次审批实例 403——用正常审批卡片冒充视为无效。vendor1 可选；不截则以 a8 R5/R6 为准。

**AC-13（P1）curl 证据。** `evidence/negative-403.txt`：≥3 段真实输出（dev1/qa1/vendor1 各一），含 HTTP 状态行、`"code":"FORBIDDEN"` 与「无权查看该审批实例」，并记录所用账号、需求 id、实例 id；token 脱敏。只存必要原文，不制造命令输出 dump。

**AC-14（P0）改动面与提交。** 分支 `feat/detail-approval-silent`（已在该分支，HEAD=e842192），不另起分支；现有未提交项仅 `?? docs/teamflow/20261124-r45-req-detail-403-fix/`，随本轮提交。diff 白名单：`web/src/api/req.ts`、`web/src/api/client.ts`（注释）、`web/src/views/req/RequirementDetailView.vue`（及必要的 `views/req/tabs/*`）、`scripts/access-verify.sh`、`scripts/tests/access/a8-*.mjs`、`scripts/tests/lint.sh`、`docs/teamflow/20261124-r45-req-detail-403-fix/**`、`docs/teamflow/{memory.md,REQUIREMENT-FLOW.md,DELIVERED-MODULES.md}`。红线：`pom.xml`、`web/package.json`、任何 lock、`db/migration/**` 的 diff 为空；`req-service/src/main/java` diff 为空。提交信息含 runId `tf-mwues0su-fpi3ea` 与任务夹路径；不 amend、不 force push。`.gitignore` 复核：`target/`、`.nacos/`、`node_modules/`、`dist/`、`.env*`、`logs/`、`*.log` 已覆盖，`docs/teamflow/**/evidence/*.png` 不被忽略（现有规则已注明）；无新增规则则以"复核记录 + diff 为空"通过。

**AC-15（P1）文档与记忆。** `docs/teamflow/REQUIREMENT-FLOW.md` 增一小节「审批可见性 ≠ 页面准入」（非参与人审批区降级、不跳 /403、服务端仍 403、文案逐字）；`DELIVERED-MODULES.md` 补一行 r45（改动面 + 219/25 门禁 + 分支）；`memory.md` **同主题替换** r8 那行（把"审批实例/评估审批"纳入必静默清单，登记 lint L23 与 a8），不新增 changelog 行；`AGENTS.md` 除托管区外 0 diff。

## 3. 范围与非目标

**In scope**

- 前端：`getApprovalInstance` 可选 `silentForbidden`；详情页 3 处调用 + `listApprovalMine` 静默；403 内联文案与错误区分；`client.ts` 约定注释。
- 门禁：lint L23；access 新 a8 段 13 条；既有门禁不降。
- 证据与文档：两张截图、curl 原文、REQUIREMENT-FLOW / DELIVERED-MODULES / memory 同主题更新。

**Non-goals**

- 不放宽服务端审批可见性（不加 owner/assignee 例外，不改 403 code/message/detail）。
- 不新增只读"审批进度"接口（C 备选，默认不做；见 §9）。
- 不改主数据 `getRequirement` 的 403 语义，不把整页错误态改成静默。
- 不改动作矩阵、评估门、可编辑性三数组、变更单链路。
- 不重构审批中心、变更详情页、详情页 Tab 架构；不做视觉改版。
- 不引入新的前端依赖（无 Playwright/Vitest 新增包）。

**P2 备选（未纳入本轮验收）**：只读可见性接口（Scope + 租户 + 负向用例）；`RequirementChangeDetailView.vue` 同策略扩展。

## 4. 反面目标（anti-goals）

> 技术全绿但仍会被人判失败的结果，逐条可核验。

- **AG-1「不跳 403 了，但审批区转圈到死或一片空白」**：用户无法区分"没权限"与"系统坏了"。核验：403 后 loading 在 1s 内结束，页签内有标题 + 逐字文案，无骨架残留、无"加载失败"。
- **AG-2「为了让研发看到进度，顺手放宽了服务端可见性」**：非参与人能在详情里看到审批人姓名、意见或节点。核验：dev1 对他人实例仍 403，页面不渲染任何审批内容；`visibleInstance()` 与 403 响应零 diff。
- **AG-3「把 silentForbidden 加到主数据上」**：需求本身无权限时不再跳 403、不提示，页面变成空壳，越权边界被静默吞掉。核验：无权限深链仍落 403/明确错误态；`getRequirement` 调用点无 silent。
- **AG-4「门禁假绿」**：L23/a8 只 grep 一个字符串，把另一处调用的 `silentForbidden` 删掉仍然绿。核验：`R45_MUTATE=bare-call`（或等价负向夹具）抽掉一处后，lint L23 与 a8 S2 必须变红；修复后恢复绿。

## 5. 交互流程摘要

打开详情 → `getRequirement` 200（主数据，不静默）→ 并行 `loadApproval` / `loadEstimateApproval` / `loadChangeCounts` → 审批实例 403 被 silent 接住 → 审批区渲染内联说明，页面其余部分不受影响。

| 场景 | 审批实例请求 | 审批区 | 页面 |
|---|---|---|---|
| 发起人/审批人/抄送人/管理员 | 200 | 正常卡片 + 时间线 | 正常 |
| 非参与人（本次修复对象） | 403 `FORBIDDEN` | 标题 + 逐字内联文案，无重试按钮 | 正常，URL 不变 |
| 网络错误 / 500 | 其他错误 | 统一错误提示 + 重试 | 正常 |
| 需求本身无权限 | 详情 403 | — | 仍按既有语义落 403 页/错误态（不放宽） |

## 6. 优先级

| 优先级 | AC | 说明 |
|---|---|---|
| P0 | AC-1 / AC-2 / AC-6 / AC-9 / AC-10 / AC-11 / AC-14 | 现场修复 + 负向不放宽 + 门禁可红 + 交付面 |
| P1 | AC-3 / AC-4 / AC-5 / AC-7 / AC-8 / AC-12 / AC-13 / AC-15 | 错误区分、动作回归、厂商、防过度降级、证据与文档 |
| P2 | （无 AC） | 只读可见性接口、变更详情页扩展，见 §3 备选 |

## 7. 依赖与风险

| 项 | 内容 | 缓解 |
|---|---|---|
| 依赖 | 演示栈与夹具（`make platform-up && make platform-seed`，必要时 `platform-demo-reset`） | a8 运行时缺夹具即判红并提示重置，不 skip |
| 依赖 | 截图需浏览器（可用已装的 Playwright，或手工浏览器） | 不可用则手工截图，不得跳过证据 |
| 风险 | 夹具中 dev1/qa1 不一定能同看一条需求，或某角色没有"命中 403"的样本 | 先重置；仍不足则调整归属夹具（只改归属、不改计数）；验收报告注明 |
| 风险 | `getApprovalInstance` 新增可选参数后，旧调用点默认值被改 | 默认 `false` 保持旧行为；a8 S1 检查不传时仍跳 403；审批中心/变更详情零改动 |
| 风险 | 详情页 `direct` 分支的 `approvalInstanceId` 疑似死路径（后端只在审计 detail 写入，未见详情投影） | 不围绕它设计门禁；3 个调用点一律静默，`estimate.instanceId` 为实际触发路径 |
| 风险 | 过度静默吞掉真实越权 | AC-7 + a8 S2 同时检查 `getRequirement` 无 silent；既有越权深链用例兜底 |
| 风险 | 文案在 1024/1280 宽度下换行被截断 | 截图按 1280×800，另在 1024 宽度目检一次 |

## 8. 里程碑建议

- **M1 前端修复（AC-1/2/3/4/5/7/8）**：api 可选参数、3 处调用 + `listApprovalMine` 静默、403 内联、`client.ts` 注释；`npm run typecheck` 0。
- **M2 门禁（AC-6/9/10/11）**：lint L23（含负向自检）、access a8 13 条、分母更新；全量门禁复跑。
- **M3 收尾（AC-12/13/14/15）**：截图、curl 原文、三份文档、memory 同主题替换、按白名单提交。

## 9. 假设与待澄清

- **（a · 事实）截图要求的"同一条需求"对 dev1 与 qa1 是否同时可见，dev1/qa1/vendor1 是否各有能命中审批实例 403 的样本。** 依据：vendor1 可见 3 条有记载，dev1/qa1 的归属需 demo-reset 后实测。默认：以 `platform-demo-reset` 后的数据为准；若两人确实无法同看一条，则各自截一条并在验收报告注明原因，**不得用 admin 代截**；若某角色完全没有"能命中 403"的样本，需调整夹具归属（不改断言计数）。
- **（b · 口味）是否为研发/测试新增只读"审批进度"接口（原需求 C 可选）。** 默认：**不新增**——本轮只做前端降级，审批区显示内联文案；新增接口会扩大后端权限面与负向用例范围，列 P2 单独立项。若选择新增，则须遵守既有 Scope 与租户边界并补负向用例。
- **（b · 口味）内联提示只放在审批页签内，还是同时在页头加一条横幅。** 默认：**仅审批页签内**——页头已有编辑/变更/评估门等多类提示，再加一条会稀释注意力；用户点开"审批"时看到原因即可。
- **（b · 口味）变更详情页 `RequirementChangeDetailView.vue` 是否同样静默。** 默认：**不扩展**——该页仅变更发起人/审批人进入，二者都是审批参与人；待未来允许研发查看变更单时再扩。

## 10. 工程约束（原样保留）

1. 「分支：feat/detail-approval-silent。未提交改动 1 处：?? docs/teamflow/20261124-r45-req-detail-403-fix/」→ 在该分支实施，基线 main=`e842192`；不另起分支，不改写历史。现有未提交项随本轮一并提交。
2. 「服务端不动权限语义」/「语义本身正确，不要放宽」→ `req-service/src/main/java` 零 diff；不得给 `visibleInstance()` 加任何角色例外。
3. 「提交信息含 runId 与任务夹」→ runId `tf-mwues0su-fpi3ea`，任务夹 `docs/teamflow/20261124-r45-req-detail-403-fix`。
4. 「diff 允许 web/src/ 与 scripts/、docs/，不含 pom/package/lock/迁移（除非确需新增只读接口并说明）」→ 见 AC-14 白名单与红线。
5. 「用脚本或 lint 断言保证 RequirementDetailView.vue 内不再出现裸调用 `getApprovalInstance(`」→ lint L23 (a) + a8 S2 双重钉住。
6. 「`scripts/access-verify.sh` 增补 ≥4 条断言——dev1/qa1/vendor1 打开自己可见的需求详情页不出现 /403 跳转（可用 Playwright/CDP 或静态断言 + 接口组合），非参与人访问他人需求的审批实例仍 403（负向不能丢）」→ a8 共 13 条，静态 4 + 运行时 6 + 负向 3。
7. 「`scripts/tests/lint.sh` 增加静态检查（详情页可选探测不允许裸 `http.get` 无 silentForbidden）」→ L23 (a)(b)(c)。
8. 「若新增[只读接口]，请遵守既有 Scope 与租户边界，且必须有对应负向用例」→ 本轮默认不新增；若启用则须补 Scope/租户/负向三条断言并说明 diff。
9. 「验收证据：dev1 与 qa1 打开同一条需求详情页的截图（页面正常渲染 + 审批区内联提示）、以及"非参与人直接调 `/api/req/approvals/instances/{id}` 仍 403"的 curl 输出」→ AC-12/AC-13。
10. 「.gitignore 复核」→ 按 AC-14 末段执行：覆盖构建产物、依赖缓存、IDE、本地 env 与日志；确认 evidence PNG 不被忽略；无新增规则则记录复核结论。

## 11. 验收方式（一页清单）

| 命令 / 证据 | 期望 |
|---|---|
| `make lint` | 25/25（含 L23） |
| `make access-verify`（九段） | 219/219，a8 = 13/13 |
| `make platform-verify` | ≥199 |
| `make platform-test-negative` | ≥132 |
| `make rbac-verify` | 113/113 |
| `make gate-order-verify` | ≥21 |
| `make release-flow-verify` | 44/44 |
| `make platform-demo-reset` ×2 | 80/80 且两次逐键一致 |
| `make editability-verify` / `make change-verify` | 36/36 / 46/46 |
| `make platform-unit` | ≥437 |
| `cd web && npm run typecheck && npm run build` | 0 error |
| `evidence/dev1-detail.png`、`qa1-detail.png` | 同一需求、页面正常 + 审批区文案、账号可辨 |
| `evidence/negative-403.txt` | 三角色各一段 403 `FORBIDDEN` 原文 |
| `git diff --stat` | 仅 AC-14 白名单；pom/package/lock/迁移/req-service 为空 |
