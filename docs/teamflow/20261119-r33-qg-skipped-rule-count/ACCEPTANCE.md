# 验收报告 · r33 质量门规则四态分桶（跳过不再算未通过）

- 任务夹：`docs/teamflow/20261119-r33-qg-skipped-rule-count/`　分支：`feat/quality-gate-skipped-count`（HEAD `b48d179`，改动待 host 按 ADR-2026-08-27 收口提交）
- 运行：`runId=tf-mwnasdev-57rg81`　验收时间：2026-11-19 17:58–18:10（Asia/Shanghai）　验收人：产品经理（验收负责人）
- 环境：本机栈（网关 8080 / req 8082 / deliver 8083 / Nacos 8848），deliver 与 req 为本次改动后重编的 jar；前端 vite 5173 + 宿主 Chrome CDP
- 交付事实：14 个文件改动 +408/−87，另新增 2 个实现文件与 4 个测试文件、1 个 node 断言脚本、1 张截图；无迁移、无 `pom.xml`/`web/package.json`/`pnpm-lock` 变更

## 结论摘要

需求目标是把发版链路质量门段的「未通过」口径收窄到真正的 FAIL：SKIPPED 单列为跳过、不再伪装成失败，同时不改变判定与放行条件。逐条核对 AC-1…AC-11 全部通过；服务端与前端各只有**一份**四态分桶实现，与架构蓝图一致；六条既有门禁与单测只增不减且全绿（release-flow 44/44、platform-verify 194/194、negative 130/130、access 206/206、rbac 113/113、demo-reset 74/74、mvn 392/392）。未发现 P0/P1/P2 缺陷。

## 1. 验收项逐条核对

| AC | 判据（PRD） | 实测证据 | 结论 |
|---|---|---|---|
| AC-1 | 演示链 PASS 卡：`failedRules` 不含 QG-05、`skippedRules` 恰含、`total=5`、四桶和=total | `GET /api/req/releases/2775`（PUBLISHED，快照 #900）→ `flowChain.qualityGate={status:PASS,result:PASS,total:5,passedCount:4,waivedCount:0,failedCount:0,skippedCount:1}`；`failedRules=[]`，`skippedRules=[{code:QG-05,status:SKIPPED,required:false}]`；门禁 [41] PASS | 通过 |
| AC-2 | 临时造未关闭 P0 → `QG-04 FAIL`：`result=FAIL`、`failedRules` 恰 `[QG-04]`、`skippedRules` 仍含 QG-05、reason「1 条门规则未通过」；关缺陷复评恢复 PASS | 门禁 [42] PASS：造 P0 冻结后 `failedRules=[QG-04]`、`skippedRules=[QG-05]`、`reason=1 条门规则未通过`；关闭缺陷后同单复评 `PASS`、跳过仍 1、hash 变化 | 通过 |
| AC-3 | 副标题三态文案：跳过 1/未通过 0、未通过 1、无计数；waivers>0 追加；单一纯函数且 node 逐字断言 | `node --experimental-strip-types scripts/tests/release-flow/qg-buckets.mjs text` → exit 0，`OK=28 FAIL=0`，含「跳过 1 条 · 未通过 0 条」「未通过 1 条」「4/4 通过 · 跳过 1」；门禁 [43] 复跑同一脚本 | 通过 |
| AC-4 | 徽标只由快照 `result` 派生；数字由同一规则扫描分桶；PASS 快照内无 FAIL 规则；链路卡与准入第 6 项结论一致 | `FlowChainPanel.vue` 的 `tone` 仅由 `status` 派生（`PASS`/`PASS_WITH_WAIVER`→已通过，`FAIL`→未通过，其余 PENDING）；`bucketGateRules` 一次扫描出数字；`/releases/2775` 实测「已通过 / PASS / 跳过 1 条 · 未通过 0 条」；`GET /deploy-orders/875` 第 6 项同结论 | 通过（库级 SQL 自检因 demo-reset 已清空快照表未复跑，见 M-3） |
| AC-5 | 准入第 6 项语义不变：SKIPPED+PASS 不阻断（9 项、提交 200）；FAIL → 409 `QUALITY_GATE_FAILED` 且 `detail.failedRules` 只含 FAIL；409 未锁定 / 400 / 403 不回归 | `/deploy-orders/875` 准入 200、9 项齐全、第 6 项 PASS；门禁 [36] 清空 MR 后提交 409 且 `failed=[MR_FILLED]`；[27]–[30] 负向（未锁定 409 / 缺原因 400 / 越级 403 / 无跳级后门）全 PASS | 通过 |
| AC-6 | SKIPPED 不伪装成通过：准入 detail 写「4 条通过 · 1 条跳过（未启用），质量门 PASS」；质量门页「4/4 通过 · 跳过 1」，跳过不进分子分母；两页一致 | 准入 detail 逐字一致；`/quality-gates/2775` 面板与时间线均「4/4 通过 · 跳过 1」；`grep -rn "5/5 通过" web/src` = 0；QA 的 CDP 跨页检查 `qa-r33-cross-page-counts.mjs` 14/14 | 通过 |
| AC-7 | 旧形状快照（SKIPPED 混在 `failedRules`）按 status 分桶、文案一致、`payloadHash` 不变；不改写/迁移/补字段 | 门禁 [44] PASS：`qg-buckets.mjs legacy` → `OK=12 FAIL=0`；同一快照连读两次 `payloadHash=3a571e6d0378…` 相同；`FlowChainViewsQualityGateTest` 5 例用旧形状 JSON 反序列化，计数为 `null` 而非 0；前端只按 `status` 分桶 | 通过（r30/r32 行已被 demo-reset 清空，改用冻结旧形状夹具，见 M-3） |
| AC-8 | 门禁只增不减：release-flow 44、五条既有门禁下限、mvn ≥310 | `release-flow-verify`：`TOTAL=44 CASE=44 PASS=44 FAIL=0`，static `11/11`；`platform-verify 194/194`、`platform-test-negative 130/130`、`access-verify 206/206`、`rbac-verify 113/113`、`platform-demo-reset 74/74`；mvn surefire 汇总 392 tests / 0 failures / 0 errors（含新增 `GateRuleBucketsTest` 5、`AdmissionPolicySkippedDetailTest` 4、`QualityGateFailedSkippedTest` 3、`FlowChainViewsQualityGateTest` 5） | 通过 |
| AC-9 | 快照与审批链不动：无迁移（无 V15）、`rules_json`/hash 不变、两级+三级审批不变 | `git diff platform-contract/.../GateSnapshot.java` 仅 javadoc + 非组件方法 `waived()/skipped()`，record 组件与 JSON 形状未变；`git status` 无迁移文件，迁移目录仍 V5–V9；门禁 [37][39][40] 发布单两级、版本三级、PUBLISHED 全 PASS | 通过 |
| AC-10 | DEMO-RELEASE-FLOW.md 遗留段改为「已修正（r33）」+ 三桶说明；8 步脚本 / A.2 事实 / 被拦链不动；新增截图不覆盖旧图 | `git diff docs/teamflow/DEMO-RELEASE-FLOW.md` +18/−10（仅 A.1 遗留块、截图索引、一键回归的 TOTAL 表述）；8 步脚本与 A.2 未改；新图 `docs/teamflow/demo-shots/r33-qg-skipped-count.png` 1600×1100/211838B，r30/r32 旧图 mtime 未变 | 通过 |
| AC-11 | 提交在 feat 分支、信息含 runId 与任务夹、diff 面干净、gitignore 复核、提交后复跑门禁 | `git status --porcelain` 23 条全为源码/测试/文档；无 `pom.xml`、`web/package.json`、`pnpm-lock`、迁移、`target`/`node_modules`/`dist`/`.run`/`logs`；截图在 `docs/teamflow/demo-shots/` 入库 | 通过（提交由 host 收口，见 M-1） |

## 2. 架构一致性（对照技术方案的模块蓝图）

- **单一分桶实现（服务端）**：`platform-contract/.../quality/GateRuleBuckets.java` 提供 `of/totalCount/judgedCount/passedLikeCount/hasSkipped`，`PASS→passed`、`WAIVED→waived`、`SKIPPED→skipped`、其余（含未知状态、null）**fail-closed 归 failed**。三个消费点全部经它：`FlowEvidenceService.java:282`（产出段）、`AdmissionPolicy.java:290`（准入文案）、`DeployOrderService.java:435/456`（409 明细与未关闭 P0/P1 计数）。
- **单一分桶实现（前端）**：`web/src/components/release/qualityGateBuckets.ts` 零 import（`grep -c "^import"` = 0），`bucketGateRules/gateCountText/gateCountLabel/failedRuleLines/skippedRuleLines`；被 `FlowChainPanel.vue:94/146`、`QualityGatePanel.vue:99/136`、`QualityGateDetailView.vue:244`、`QualityGatesView.vue` 复用。`grep -rln "function bucketGateRules" web/src` 仅此一处，无第二份。
- **透传不重算**：`FlowChainService.qualityGate(Map)`（186–202 行）逐键透传，未做判定或二次计数；`FlowChainViews.QualityGateSegment` 15 个组件，5 个计数为可空 `Integer`，`missing()` 全 null，旧快照缺字段不伪装 0。
- **旧接口未被误用**：主代码中 `!passed()` 式 `failedRules()` 过滤已无调用点（仅注释提及）；`GateSnapshot.failedRules()` 行为保留但新代码不用，符合「不改旧方法也要让它不再被调用」的蓝图约束。
- **快照零改动**：`GateRuleResult` 只加非组件方法，record 组件、JSON 形状、`payloadHash` 语义不动；`quality_gate_snapshot` 表结构与 `rules_json` 未改，无新迁移。
- **重复实现/结构破损**：未发现。`QualityGateService.java:461-472` 保留自身的 `SKIPPED → continue` 是既有列表 DTO 的只读口径，技术方案明确「对齐它、不替换它」，不构成分桶逻辑的第二份实现。

## 3. 回归矩阵（本次验收实跑）

| 门禁 | 命令 | 结果 |
|---|---|---|
| 发版链路 | `bash scripts/release-flow-verify.sh` | exit 0，`TOTAL=44 CASE=44 PASS=44 FAIL=0`；S8 [41]–[44] 全 PASS |
| 静态自检 | `bash scripts/release-flow-verify.sh --static-only` | exit 0，`11/11`（TOTAL=44 / 8 段 / S7 10 函数 / S8 4 函数 / 两页无写死分母） |
| 分桶纯函数 | `node --experimental-strip-types scripts/tests/release-flow/qg-buckets.mjs text`、`legacy` | exit 0，`OK=28 FAIL=0` / `OK=12 FAIL=0` |
| 平台验收 | `bash scripts/platform-verify.sh` | exit 0，194/194 |
| 负向 | `bash scripts/platform-test-negative.sh` | exit 0，130/130 |
| 权限×页面 | `bash scripts/access-verify.sh`（demo-reset 之后） | exit 0，206/206 |
| RBAC | `bash scripts/rbac-verify.sh` | exit 0，113/113 |
| 演示复位 | `bash scripts/platform-demo-reset.sh` | exit 0，74/74 |
| 单元测试 | mvn surefire 报告汇总 | 392 tests / 0 failures / 0 errors |

## 4. 反例与负向证据（防止「数字对了但放行条件变了」）

- 造 P0 → 快照 `result=FAIL`、`failedRules` 恰为 `[QG-04]`（不含 SKIPPED 行）、`reason` 写「1 条门规则未通过」而非「2 条」；关闭缺陷后同单复评恢复 `PASS` 且跳过仍 1 条（门禁 [42]）。
- 清空 MR 提交 → 409 `ADMISSION_FAILED`、`failed=[MR_FILLED]`（门禁 [36]）；未锁定 409、缺原因 400、越级 403 均由 [27]–[30] 守住。
- 前端断言直接比对字符串：「跳过 1 条 · 未通过 0 条」「未通过 1 条」「4/4 通过 · 跳过 1」；未知状态 fail-closed 归不合格，不会被当成通过。

## 5. 意见与遗留

- **M-1（流程，非缺陷）**：按 ADR-2026-08-27，提交由 host 在 run 收口时执行；AC-11 的提交信息含 `runId=tf-mwnasdev-57rg81` 与任务夹路径、提交后复跑第 [32] 条区间判据，均在提交动作发生时完成。r32 验收同样以 host 收口方式通过，不阻塞本轮结论。
- **M-2（实现范围说明）**：`req-service/.../seed/DemoFixture.java` 把 `DEMO-R-02` 人天 8→5，使其等于子项 3+2，修复 `access-verify a7` 的 STALE 漂移（8→5 属演示夹具一致性，不触碰判定逻辑与产品数据）。
- **M-3（证据替代，已披露）**：`platform-demo-reset` 会清空 `quality_gate_snapshot`，r30/r32 的冻结行在当前库已不存在；AC-7 因此用「旧形状 JSON 夹具 + 连读 payloadHash 稳定性」验证，前端按 `status` 分桶的代码路径与真实旧行完全一致。AC-4 的库级 SQL 自检同理未复跑，改由门禁 [41]/[42] 的接口断言与 QA 的 SQL 结论支撑。
- **M-4（运行顺序）**：`access-verify` 必须在 `platform-demo-reset` 之后运行。本次验收首轮把它排在 `platform-test-negative` 之后，负向用例在 `DEMO-R-02` 下新增子项使 rollup 变 8.5，a7 报 `STALE approved=5 proposed=5`（15/16）；reset 后复跑 16/16、全量 206/206，确认是数据顺序而非交付缺陷。
- **P3 观察（不阻塞）**：质量门列表行只写「4/4 通过」不展开「· 跳过 1」；时间线计数行缺口径 tooltip；详情页标题「5 条门禁规则」写死 5（规则集版本变化时会与计数脱节）。均已在 QA 报告记录，留作后续打磨。
- QA 上轮 D-1（P1，详情页时间线写死「5/5 通过」）与 D-2（P3，列表兜底写死 5）已按原判据复跑关闭，跨页 14 条 CDP 断言全绿。

## 6. 验收结论

AC-1…AC-11 全部通过；架构与蓝图一致（服务端/前端各一份分桶实现、透传不重算、快照与审批链零改动）；门禁与单测只增不减且全绿。M-1 属既定的 host 提交收口流程，不构成功能或结构遗留。

验收结论：✅ 通过
