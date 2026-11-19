<!-- meta: summary="修正版本发版链路质量门卡片的口径：SKIPPED 不再计入「未通过」而单列「跳过 N 条」，卡片 PASS/FAIL 与副标题计数同源，准入第 6 项 QUALITY_GATE 判定不变。" -->

基线依赖：docs/teamflow/20261119-r32-release-flow-e2e（r32 的演示夹具、8 步脚本、release-flow-verify 40 条与 DEMO-RELEASE-FLOW.md A.3 的既成行为不得回退）

取代：docs/teamflow/20261119-r32-release-flow-e2e#AC-7：仅「质量门段计数口径」一句改为 FAIL 计入未通过、SKIPPED 单列跳过；四段同源、缺段 MISSING、round=1 回放一致性等其余判据不变。

# PRD — 质量门「跳过 ≠ 未通过」口径修正（r33）

## 1. 背景与问题

r32 演示链实测（main=b48d179，截图 `docs/teamflow/demo-shots/r32-published-chain.png`）：版本详情「发版链路」第 ③ 张卡片徽标是 `PASS / 已通过`，副标题却写 `2026-11-19 10:17 · 1 条未通过`——同一张卡自相矛盾，演示时观众第一眼就会问「到底过没过」。

根因（已实测确认）：

- 质量门快照有 5 条规则 `QG-01..QG-05`。本轮 `QG-05` 未接入外部质量源，`GateEvaluator` 给出状态 `SKIPPED`（`deliver-service/.../quality/gate/GateEvaluator.java:502-506`，`skip()` 造出 `SKIPPED` 行）。
- 发版链路聚合 `FlowEvidenceService.qualityGate`（`deliver-service/.../quality/internal/FlowEvidenceService.java:276-318`）用 `if (rule.passed()) continue;` 收集 `failedRules`，而 `GateSnapshot.GateRuleResult.passed()` 只认 `PASS`/`WAIVED`（`platform-contract/.../quality/GateSnapshot.java:68-84`）——`SKIPPED` 被算进 `failedRules`。
- 卡片徽标与结论取的是快照 `result`（`PASS`），副标题取的是 `failedRules.length`（`web/src/components/release/FlowChainPanel.vue:131-147`）——两处口径不同源，于是「一处说 PASS、一处说 1 条未通过」。

同族证据：团队口径本来就是「跳过既不通过也不失败」。`QualityGateService.mapRow` 统计版本门列表时对 `SKIPPED` 直接 `continue`，`passed/total` 的分母不含跳过规则（`deliver-service/.../quality/gate/QualityGateService.java:461-472`）。也就是说，出错的只是发版链路这一处的展示聚合，把它对齐已有口径即可。

r32 文档已把这个矛盾记为交付遗留（`docs/teamflow/DEMO-RELEASE-FLOW.md:311-314` 原文「不在本轮改动范围」）。本需求就是关闭这条例外。

## 2. 目标

- **G1 一个口径**：一条规则只有四种状态，展示固定分三桶——通过（`PASS`/`WAIVED`）、跳过（`SKIPPED`）、未通过（`FAIL`）。「未通过」永远只数 `FAIL`，任何位置都不允许把 `SKIPPED` 计入。
- **G2 一处计算**：同一份快照的徽标与副标题数字来自同一次规则扫描；前端按 `status` 分桶，不按数组长度猜测；后端不再产出语义自相矛盾的 `failedRules`。
- **G3 闸门不动**：准入第 6 项 `QUALITY_GATE` 仍只认快照 `result`（`PASS`/`PASS_WITH_WAIVER` 才通过）；`SKIPPED` 不阻断，但也**不伪装成通过**（不能把跳过写进「全部通过」）。
- **G4 可证**：正（有跳过、无失败）、反（故意一条 FAIL）两场景与历史快照回放，都被门禁/单测断言覆盖，且门禁断言只增不减。

## 3. 用户故事与验收标准

- **US-1 演示者**：打开版本详情的发版链路，第 ③ 张卡显示 `PASS / 已通过`，副标题是 `2026-11-19 10:17 · 跳过 1 条 · 未通过 0 条`，不再自相矛盾。（AC-1、AC-3、AC-4）
- **US-2 发布负责人**：当真的有一条规则 FAIL 时，卡片变 `FAIL`，副标题说清「未通过 1 条」，发布单准入第 6 项同样 FAIL 并拦住提交——展示与闸门一致，且不因为「跳过」被误拦。（AC-2、AC-5）
- **US-3 审计/回放**：查看 r30/r32 提交时冻结的 round=1 链路快照，结论与数字仍按新口径正确显示，且快照本身一个字节都没被改写。（AC-7、AC-9）
- **US-4 维护者**：这条口径以后不会再漂移——质量门页、准入文案、发版链路卡三处对同一快照给出同一组数字。（AC-6）
- **US-5 QA**：门禁跑一遍就能同时证明「跳过不算未通过」和「FAIL 仍被拦」。（AC-8）

### 验收标准（AC）

| 编号 | 可测判据 | 证据 |
|---|---|---|
| **AC-1** | 演示版本（`QG-01..QG-04` PASS、`QG-05` SKIPPED）的发版链路质量门段返回：`status=PASS`、`result=PASS`、`failedRules` 长度 **0**（不含 `QG-05`）、`skippedRules` 恰含 `QG-05`（`required=false`）、`total=5`；且恒等式 `passed + waived + failed + skipped == total` 成立。旧实现此处置为 `failedRules` 长度 1。 | `GET /api/req/deploy-orders/{id}/flow-chain` 的 `flowChain.qualityGate`；同快照的 `/api/deliver/quality-gates/snapshots/{id}` 对照 |
| **AC-2** | 故意造一条 `FAIL`（在临时版本的关联需求上留一个未关闭 P0 缺陷 → `QG-04` FAIL）：快照 `result=FAIL`，`failedRules` **恰为 `[QG-04]`**（不得含任何 `status=SKIPPED` 的行），`skippedRules` 仍含 `QG-05`；聚合 `reason` 为「1 条门规则未通过」，不再出现「2 条」。 | 先 `POST /api/deliver/defects` 造 P0，再 `POST /api/deliver/quality-gates/{releaseId}/evaluate`，读链路段 |
| **AC-3** | 副标题计数段文案三态：`skipped=1,failed=0` → `跳过 1 条 · 未通过 0 条`；`skipped=0,failed=1` → `未通过 1 条`；`skipped=0,failed=0` → 不出现计数段（仅时间/豁免）。`waivers>0` 时仍追加 `N 条豁免`。文案由**单一纯函数**产出，门禁可用 `node` 直接断言字符串（不依赖浏览器 DOM）。 | 新增前端纯函数单测 + `release-flow-verify` S8 断言 |
| **AC-4** | 判定与数字同源：卡片 `PASS/FAIL/PENDING` 只由快照 `result` 派生（`PASS`/`PASS_WITH_WAIVER`→PASS，`FAIL`→FAIL，其余→PENDING）；副标题数字由同一规则列表按 `status` 分桶。库级自检：`result IN ('PASS','PASS_WITH_WAIVER')` 的快照中不得存在 `status=FAIL` 的规则；同一 `snapshotId` 在链路卡与准入第 6 项的结论必须一致（同为 PASS 或同为 FAIL）。 | S8 断言 + 一条只读自检 SQL（`quality_gate_snapshot.rules_json`） |
| **AC-5** | 准入第 6 项语义不变：SKIPPED 存在且 `result=PASS` → `QUALITY_GATE` PASS，演示链提交仍 `200`、9 项准入不变；`result=FAIL` → `QUALITY_GATE` FAIL、提交 `409 QUALITY_GATE_FAILED`，`detail.failedRules` 只含 FAIL 规则（不含 `QG-05`）。既有负向不回归：未锁定 `409 RELEASE_NOT_LOCKED`、缺原因 `400`、越级 `403` 且任务仍 `PENDING`。 | 演示链 `submit` + S8 负向 + 既有 negative 门禁 |
| **AC-6** | `SKIPPED` 不伪装成通过：准入第 6 项 PASS 的 `detail` 写「4 条通过 · 1 条跳过（未启用），质量门 PASS」一类表述，不再出现「5 条规则全部通过」；质量门页面（质量域）规则计数为「4/4 通过 · 跳过 1」，`SKIPPED` 既不计入通过也不进分母（与 `QualityGateService` 列表口径一致）。 | 页面实测 + `mvn` 单测断言 detail 文案 |
| **AC-7** | 历史快照兼容：r30/r32 提交时冻结的 round 快照（其 `failedRules` 数组内含 `SKIPPED` 行）回放在新口径下仍显示 `跳过 1 条 · 未通过 0 条`；快照 JSON 与 `digest` 不变，不重写、不迁移、不补字段。实现上按 `status` 分桶，不依赖数组长度或字段是否存在。 | `GET /api/req/deploy-orders/{id}/flow-snapshots/{round}` 对照提交前后 `payloadHash` |
| **AC-8** | 门禁只增不减：`release-flow-verify` `RELEASE_FLOW_TOTAL` 40 → **44**（新增 S8 四条覆盖 AC-1/2/3/7），`CASE==TOTAL`、`PASS==TOTAL`；静态自检同步（`section "S[1-8]` 计数 7→8、S8 函数数、`RELEASE_FLOW_TOTAL=44` 唯一声明、帮助文本）；`platform-verify` 194、`platform-test-negative` 130、`access-verify` 206、`rbac-verify` 113、`platform-demo-reset` 74 全部不低于且全绿；`mvn` 单测 307 → ≥310、0 失败。 | `make release-flow-verify` 等六条回归命令的输出 |
| **AC-9** | 快照与审批链不动：`quality_gate_snapshot` 表结构/列/`rules_json` 结构不变、**无新迁移**（仍无 V15）；快照 `hash` 与 `(deployOrderId,hash)` 复用语义不变；审批两级、版本三级、准入 9 项顺序与 `deploy_flow_snapshot` 字段不变。若实现中发现**必须**改快照数据结构 → 停下并说明，不得擅自改。 | `git diff` 迁移目录为空；replay 与 round 回放断言；审批链既有门禁 |
| **AC-10** | 文档：`DEMO-RELEASE-FLOW.md` 中「截图里 ③ 卡片副标题写着 1 条未通过…不在本轮改动范围」那段改为「已修正（r33）：跳过 1 条 · 未通过 0 条」，并说明新的三桶口径；8 步脚本、A.2 四个实操事实、被拦链均不改。`demo-shots/` 新增 ≥1 张修正后截图（新文件名，不覆盖 r30/r32 旧图）。 | 文档目视 + `git ls-files docs/teamflow/demo-shots` |
| **AC-11** | 提交卫生：在 `feat/quality-gate-skipped-count` 分支提交，信息含 `runId=tf-mwnasdev-57rg81` 与任务夹 `docs/teamflow/20261119-r33-qg-skipped-rule-count/`；`diff` 不含 `pom.xml`、`web/package.json`、`web/pnpm-lock.yaml`、迁移；`.gitignore` 复核通过（`target/`、`node_modules/`、`dist/`、`.run/`、`logs/`、`.env*` 均已被忽略，新截图在 `docs/teamflow/demo-shots/` 必须入库）；提交后复跑 `release-flow-verify` 全绿（含第 32 条 `BASE..HEAD` 区间纯判据）。 | `git show --stat` + 提交后复跑输出 |

## 4. 范围

**范围内**：deliver 发版链路质量门段的规则分桶（`failedRules`=FAIL、跳过单列）；req 对链路段的透传（`FlowChainService`/`FlowChainViews`）；`FlowChainPanel.vue` 副标题与明细；质量门页 `QualityGatePanel.vue` 规则计数；准入第 6 项 PASS 时 `detail` 文案；对应的 Java/前端单测；`release-flow-verify` 新增 S8；`DEMO-RELEASE-FLOW.md` 遗留说明与一张新截图。

**非目标（不做）**：

- 不接入 `QG-05` 的真实外部质量源（Sonar/制品/构建采集），`SKIPPED` 仍表示「本轮未接入」。
- 不改准入第 6 项的通过条件、9 项顺序、强制放行/豁免语义。
- 不改 r30 两级/三级审批链、不改 `deploy_flow_snapshot` 结构与 round 语义。
- 不改快照表结构、不加迁移、不重算历史快照。
- 不把演示 MR 的示例地址换成真实内网仓库（准入只判非空，用户另行提供）。
- 不重做质量域页面视觉、不引入 i18n、不改其它与「跳过」无关的文案。

## 4b. anti-goals / 反面目标

即使测试全绿、这些情况发生一次就该打回：

1. **只改文案不改判定，或只改一处**：卡片 ③ 显示 `PASS`，副标题仍出现「未通过 1 条」；或发版链路修了、质量门页里同一快照仍写「5 条全部通过」。用户打开两个页面看到的数字互相对不上。
2. **把跳过伪装成通过**：为了消掉「未通过」，把 `QG-05` 记成 `PASS` 或计入「全部通过」，副标题变成「5 条规则全部通过」——比原来的矛盾更糟，等于把「外部质量源没接入」这件事藏起来。
3. **只对新数据生效**：回放 r30/r32 的 round=1 时又冒出「1 条未通过」；或为了统一而重写旧快照/迁移历史数据，导致旧发布单的结论变脸。
4. **数字对了、闸门松了**：`QUALITY_GATE` 在 `result=FAIL` 时因为「`failedRules` 为空」被放过，或提交不再 409 —— 修展示却动了放行条件。
5. **只有演示库是对的**：换一条含真实 `FAIL` 规则的版本（或含 2 条跳过），副标题仍数错（典型原因：前端继续用 `failedRules.length`，而不是按 `status` 分桶）。

## 5. 交互与数据流（摘要）

1. 评估：`GateEvaluator` 产出 5 条规则（`PASS`/`WAIVED`/`FAIL`/`SKIPPED`）与快照 `result`（FAIL 主导；有 WAIVED 则 `PASS_WITH_WAIVER`；`SKIPPED` 不参与结论）。
2. 分桶：deliver 侧一次扫描分出「未通过（FAIL）」「跳过（SKIPPED）」两组，`failedRules` 只装 FAIL，跳过单列（含 `code/required/actual/fixHint` 与跳过原因）。
3. 透传：req 的 `flow-chain` 段原样透传，不自行重算（沿用 r30 的「req 只透传 deliver 事实」原则）。
4. 渲染：卡片徽标取 `result`；副标题取同一次扫描的计数 → `跳过 N 条 · 未通过 M 条`（`M=0` 时不省略「未通过 0 条」，以免读者以为漏算）；明细列 FAIL 规则，跳过规则以「跳过：原因」列出。
5. 闸门：发布单准入第 6 项仍只读快照 `result`；展示口径的变化不影响 `QUALITY_GATE` 的 PASS/FAIL。

## 6. 优先级

| 优先级 | 内容 |
|---|---|
| **P0** | AC-1、AC-2、AC-4、AC-5、AC-9（口径与判定正确、快照不动、闸门不变） |
| **P1** | AC-3、AC-6、AC-7、AC-8（文案三态、其它页面一致、历史回放、门禁只增不减） |
| **P2** | AC-10（文档与截图） |
| **P0（交付纪律）** | AC-11（分支/提交信息/卫生/提交后复跑） |

## 7. 依赖与风险

**依赖**

- 依赖 `docs/teamflow/20261119-r32-release-flow-e2e` 的演示夹具与 S1–S7 基线：演示版本只挂 `DEMO-R-04`，`QG-01` 才能 100%。
- 依赖 `QualityGateService` 已有的「跳过不进 passed/total」先例作为口径依据，不另立标准。

**风险与对策**

- **链路 API 契约扩展**：链路段要新增跳过计数/列表，属于 `FlowChainViews` 的字段新增（向后兼容）；前端必须同批更新，避免旧前端读不到新字段。对策：新字段可缺省、前端对缺失字段按 `status` 从既有数组回退分桶（AC-7）。
- **旧快照回放**：r30/r32 的冻结 JSON 里 `SKIPPED` 仍在 `failedRules` 中且没有跳过字段。对策：渲染一律按 `status` 分桶，**禁止**用数组长度；不迁移、不重写（AC-7）。
- **负向场景污染演示数据**：造 P0 缺陷会影响缺陷看板/`openHigh` 指标。对策：负向用一次性临时版本 + 临时缺陷，断言后删除并复核 `platform-demo-reset` 仍 74/74。
- **门禁分母漂移**：`release-flow-verify` 的分段/总数/函数数三处自检互相咬合，改一处漏一处就红。对策：S8 与静态自检同批改，提交后复跑（含第 32 条区间判据）。
- **文案改动波及单测**：`AdmissionPolicyTest` 现存「5 条规则全 PASS」夹具，改文案可能触发断言调整；只改「SKIPPED 存在时」的表述，全 PASS 时保持原句。

## 8. 里程碑建议

- **M1 服务端分桶**：deliver 段分桶 + req 透传 + 契约访问器；单测覆盖「有跳过无失败」「有失败有跳过」两种快照。
- **M2 前端口径**：纯函数 + 卡片副标题/明细 + 质量门页计数；`pnpm typecheck`/`build` 通过。
- **M3 门禁**：`release-flow-verify` S8 四条 + 静态自检同步（44/8 段）；六条回归全绿。
- **M4 文档与收尾**：A.3 说明与新截图；提交（含 runId 与任务夹）后复跑区间判据。

## 9. 工程约束（逐字保留原始指令）

- 分支：`feat/quality-gate-skipped-count`（宿主已建，当前即在该分支）。**不要另起分支、不要切回 main。**
- 工作区当前只有 1 处未提交改动：`?? docs/teamflow/20261119-r33-qg-skipped-rule-count/`——直接在本分支实现并随本轮一起提交；不 stash、不丢弃。
- 提交信息包含 `runId=tf-mwnasdev-57rg81` 与任务夹路径 `docs/teamflow/20261119-r33-qg-skipped-rule-count/`。
- **硬约束（原文）**：不动 r30/r32 的审批链、准入项顺序、快照不可变语义；若发现必须改快照数据结构，先停下来说明再做。
- **硬约束（原文）**：准入第 6 项 `QUALITY_GATE`「必须 PASS 才通过」的判定不变；SKIPPED 规则不阻断、也不伪装成通过。
- **硬约束（原文）**：受影响的门禁（release-flow-verify 40 条、platform-verify 194、platform-test-negative 130、access-verify 206、rbac-verify 113、platform-demo-reset 74）只增不减，新增断言覆盖正反两种口径。
- 演示 MR 链接仍是示例地址（准入只判非空），本轮不改。
- **`.gitignore` 复核**（提交前）：现有规则已覆盖 Maven `target/`、前端 `node_modules/`、`dist/`、`.vite/`、`.pnpm-store/`、运行态 `.run/`、`.nacos/`、`logs/`、`*.log`、`.env*`、IDE、`*.xlsx`/`*.csv`；本轮若新增截图必须落在 `docs/teamflow/demo-shots/`（**必须入库**，不得被忽略），提交前确认 `git status` 只有本轮预期文件、无 `target/`、`node_modules/`、`.run/` 噪音。

## 10. 假设与待澄清

- **Q1（偏好）**：当既没有跳过也没有未通过时（全部 PASS/WAIVED），副标题是否仍写「未通过 0 条」？**默认**：不显示计数段（只留评估时间与豁免数），避免每张卡都挂一个无信息量的「0」。
- **Q2（偏好）**：本次口径统一的范围是否包含「质量门页规则计数」与「准入第 6 项 PASS 文案」（AC-6）？**默认**：包含——否则同一份快照在两个页面仍会出现「4/4 通过」与「5 条全部通过」两种说法，等于把这次的不一致挪了个位置。
- **Q3（偏好）**：卡片明细（副标题下的规则行）是否列出被跳过规则的编码与原因（如 `QG-05 外部质量源未接入`）？**默认**：列出——用户看到「跳过 1 条」时应能立刻知道是哪条、为什么，而不是再点进质量门页找。
- **事实类待澄清**：无。快照结构、判定语义、演示地址、门禁下限均已由需求给定或可从仓库确认。
