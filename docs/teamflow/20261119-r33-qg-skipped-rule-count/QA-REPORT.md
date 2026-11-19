# QA-REPORT · r33 质量门规则四态分桶（复验轮 r2）

- 任务夹：`docs/teamflow/20261119-r33-qg-skipped-rule-count/`　分支：`feat/quality-gate-skipped-count`（HEAD `b48d179`，改动未提交，按 ADR-2026-08-27 由 host 收口）
- 运行：`runId=tf-mwnasdev-57rg81`　时间：2026-11-19 17:30–18:00（Asia/Shanghai）　轮次：**复验轮**（上一轮 1×P1 D-1 + 1×P3 D-2）
- 环境：本机栈 `make platform-up`（网关 8080 / req 8082 / deliver 8083 / Nacos 8848），deliver 与 req 为本次改动后重编的 jar；前端 vite 5173 + 宿主 Chrome CDP 9222（沙箱内无法自启浏览器 → 用 CDP 真实 DOM 断言，非交付缺陷）
- 结论：**可验收**。上轮 D-1（P1）与 D-2（P3）均已闭环并逐条复跑原判据；本轮新增的三个展示面（发布单详情、发布评审页准入、质量门列表）与两处已修面口径一致；未发现新的 P0/P1/P2 缺陷。

## 1. 验收项逐条结果（复验）

| AC | 判据 | 结果 | 证据（命令 / 实测） |
|---|---|---|---|
| AC-1 | 演示链 PASS 卡：`failedRules` 不含 QG-05、`skippedRules` 恰含、`total=5`、四桶和=total | 通过 | `GET /api/req/releases/2768` → `qg={status:PASS,result:PASS,total:5,passedCount:4,waivedCount:0,failedCount:0,skippedCount:1,skippedRules:["QG-05"],failedRules:0,snapshotId:899}`；`bash scripts/release-flow-verify.sh` [41] PASS |
| AC-2 | 造 P0 → `FAIL`、`failedRules` 恰 `[QG-04]`、`reason=1 条门规则未通过`、`skippedRules` 仍含 QG-05；关缺陷复评恢复 PASS | 通过 | 门禁 [42] PASS（临时单造 P0 → freeze FAIL → 关缺陷 → 同单复评 PASS，hash 变、skip 仍 1） |
| AC-3 | 副标题三态文案由单一纯函数产出，node 逐字断言 | 通过 | `node --experimental-strip-types scripts/tests/release-flow/qg-buckets.mjs text` → exit 0，`OK=28 FAIL=0`（较上轮 +5：详情页复用/两页无写死/时间线文案） |
| AC-4 | 徽标只由 `result` 派生；数字由同一规则列表分桶；PASS 快照内无 FAIL 规则 | 通过 | `web/src/components/release/FlowChainPanel.vue` tone 仅由 `status` 派生；`/releases/2768` 实测「已通过 / PASS / 跳过 1 条 · 未通过 0 条 + 跳过：QG-05 未启用…」，快照 `rules[]` 无 FAIL |
| AC-5 | 准入第 6 项语义不变：SKIPPED+PASS 不阻断，FAIL → 409 | 通过 | `/deploy-orders/875` 准入第 6 项 detail 逐字「4 条通过 · 1 条跳过（未启用），质量门 PASS」；门禁 [36] 清空 MR 后 409 `failedRules` 恰 `[MR_FILLED]`、[27][28][29][30] 负向全 PASS |
| AC-6 | 跳过不伪装成通过；质量门页与准入文案同口径，跳过不进分子分母 | 通过（上轮失败项，本轮闭环） | `/quality-gates/2775` 面板与快照时间线**两处**均为「4/4 通过 · 跳过 1」（不含「5/5 通过」）；`grep -rn "5/5 通过" web/src` = 0 命中；`qa-r33-cross-page-counts.mjs` 14/14 |
| AC-7 | 旧形状快照（SKIPPED 混在 `failedRules`）按 status 分桶、文案一致、hash 不变 | 通过 | `qg-buckets.mjs legacy` → exit 0，`OK=12 FAIL=0`；[44] 连读两次 `payloadHash=42dcacb2a983…` 相同；[43] 旧形状回放文案与新数据一致 |
| AC-8 | 门禁只增不减：release-flow 44、五条门禁下限、mvn ≥310 | 通过 | `TOTAL=44 CASE=44 PASS=44 FAIL=0`；194/130/206/113/74 全绿；mvn 392 tests / 0 fail / 0 err；static 11/11 |
| AC-9 | 快照与审批链不动：无迁移、JSON/hash 不变、两级+三级审批不变 | 通过 | `git diff platform-contract/.../GateSnapshot.java` 仅 `waived()/skipped()` + javadoc；`git status` 无迁移（无 V15）；[37][39][40] PASS |
| AC-10 | 文档改「已修正（r33）」+ 新截图，8 步脚本/A.2 事实不动 | 通过 | `git diff docs/teamflow/DEMO-RELEASE-FLOW.md` +18/−10（A.1 遗留块→三桶说明、截图索引、一键回归段改「以脚本自声明 TOTAL 为准」）；`docs/teamflow/demo-shots/r33-qg-skipped-count.png` 新文件 1600×1100，r30/r32 未覆盖 |
| AC-11 | 提交卫生（分支、信息、diff 面、gitignore） | 通过（待 host 提交） | `git status --porcelain` 23 条全为源码/测试/文档，无 `pom.xml`、`web/package.json`、`pnpm-lock`、迁移、`target`/`node_modules`/`dist`/`.run`/`logs` |

## 2. 回归矩阵（复验轮实跑，逐条命令与退出码）

| 门禁 | 命令 | 结果 |
|---|---|---|
| 发版链路 | `bash scripts/release-flow-verify.sh` | exit 0，`TOTAL=44 CASE=44 PASS=44 FAIL=0`；S8 [41]–[44] 全 PASS（[41] 分桶+准入文案、[42] 造 P0 复评、[43] node text `OK=28`、[44] 旧形状回放 hash 稳定）；末尾 EXIT trap 已 demo-reset |
| 静态自检 | `bash scripts/release-flow-verify.sh --static-only` | exit 0，`RELEASE_FLOW_STATIC_TOTAL=11 PASS=11 FAIL=0`（较上轮 +1：[07] 两页分桶收敛；TOTAL=44 / section 8 / S7 函数 10 / S8 函数 4） |
| 分桶纯函数 | `node --experimental-strip-types scripts/tests/release-flow/qg-buckets.mjs text` / `legacy` | exit 0，`OK=28 FAIL=0` / `OK=12 FAIL=0` |
| 前端分桶单测 | `node --experimental-strip-types logs/teamflow/tf-mwnasdev-57rg81/scripts/t3-buckets-check.mjs` | exit 0（分桶恒等式/三态文案/label/旧形状回放/未知状态 fail-closed） |
| 平台验收 | `bash scripts/platform-verify.sh` | exit 0，194/194 |
| 负向 | `bash scripts/platform-test-negative.sh` | exit 0，130/130 |
| 权限×页面 | `bash scripts/access-verify.sh` | exit 0，206/206（须在 demo-reset 后跑，见 §5-③） |
| RBAC | `bash scripts/rbac-verify.sh` | exit 0，113/113（11 段） |
| 演示复位 | `bash scripts/platform-demo-reset.sh` | exit 0，74/74（5/5 段） |
| 单元测试 | `mvn -o -Dmaven.repo.local=$PWD/.m2/repository test` | exit 0，`BUILD SUCCESS`，req-service 319 + deliver-service 73 = 392，`Failures: 0, Errors: 0`（新增 `GateRuleBucketsTest` 5、`AdmissionPolicySkippedDetailTest` 4、`FlowChainViewsQualityGateTest` 5、`QualityGateFailedSkippedTest`） |
| 前端编译 | `cd web && npm run typecheck` / `npm run build` | exit 0（vue-tsc 无错误；`✓ built in 7.95s`） |
| Lint | `bash scripts/tests/lint.sh` | exit 0，22/22 |

## 3. 缺陷闭环对比（上一轮 2 条，**只作比较，不是当前缺陷表**）

| 编号 | round-1 级 | 功能模块 | 关闭判据（原样复跑） | 本轮实测 | 状态 |
|---|---|---|---|---|---|
| D-1 | P1 | 质量门详情页时间线自算计数，写死「5/5 通过」 | `! grep -q "5/5 通过" web/src/views/quality/QualityGateDetailView.vue` 且 `node logs/teamflow/tf-mwnasdev-57rg81/scripts/qa-r33-gate-timeline-probe.mjs <rel>` exit 0 | 静态命令 exit 0；`grep -q bucketGateRules` 命中（`ruleSummary` → `gateCountLabel(bucketGateRules(snapshot.rules ?? []))`，import 自 `@/components/release/qualityGateBuckets`）；探针 exit 0：`ok 面板口径「4/4 通过 · 跳过 1」/ ok 时间线不得出现「5/5 通过」`，`QA_R33_TIMELINE failures=0` | **已关闭** |
| D-2 | P3 | 列表页 `ruleSummary()` 兜底写死 5 | `grep -n "5/5 通过" web/src/views/quality/QualityGatesView.vue` 无命中 | 无命中；兜底改为只凭 `failedRules` 报「未通过 N 条」/「通过」，不臆造分母（`QualityGatesView.vue:132-138`） | **已关闭** |

命中数复算（同一扫描口径，修复前 → 修复后）：写死「`5/5 通过`」的文件 2 → **0**（`grep -rn "5/5 通过\|[0-9]/5 通过" web/src` = 0）；分桶实现点 2 处（`qualityGateBuckets.ts` + 两视图内联）→ **1 处**（`grep -rln "function bucketGateRules" web/src` 仅 `web/src/components/release/qualityGateBuckets.ts`）。修复摘要与本轮实测一致，无分歧。

## 4. 本轮新增覆盖（上轮未覆盖的面）与结论

上轮只验了「质量门详情页」一处两套计数；同一快照还在其它页面被复述，故新增 CDP 检查器 `logs/teamflow/tf-mwnasdev-57rg81/scripts/qa-r33-cross-page-counts.mjs`（14 条断言，exit 0）：

| 面 | 断言 | 实测（版本 2775 / 发布单 875） |
|---|---|---|
| `/releases/<rel>` 发布单详情 | 卡片副标题、无「1 条未通过」 | `时间 · 跳过 1 条 · 未通过 0 条` + 明细 `跳过：QG-05 未启用（本轮无外部质量数据源接入）` |
| `/deploy-orders/<order>` 发布评审（准入 9 项） | 第 6 项 detail、不说「5 条规则全部通过」 | `4 条通过 · 1 条跳过（未启用），质量门 PASS` |
| `/quality-gates/<rel>` 详情 | 面板与时间线均「4/4 通过 · 跳过 1」、无「5/5 通过」 | 两处命中，无「5/5 通过」 |
| `/quality-gates` 列表 | 行计数 `4/4 通过`、无「未通过 1 条」/「5/5 通过」 | 行显示 `4/4 通过`（服务端 `passedRules/totalRules`，跳过不进分子分母） |

> 过程纠正（写入余留证据）：该检查器初版把「准入第 6 项」断言打在 `/releases/<rel>`，报 2 条 FAIL；实查发现准入 9 项只在 `/deploy-orders/<id>` 渲染（`qa-r33-xpage-text-probe.mjs` 诊断），改为正确页面后 14/14 全绿——**属探针打错页面，不是交付缺陷**。

## 5. 缺陷表（本轮仍 OPEN）

未发现缺陷。

## 6. 架构与接口一致性（静态，逐调用点；含蓝图核对）

- **蓝图落实**：服务端唯一分桶 `platform-contract/.../quality/GateRuleBuckets.java`（`of/totalCount/judgedCount/passedLikeCount/hasSkipped`），deliver 产出（`FlowEvidenceService`）、req 准入（`AdmissionPolicy`）、req 409 明细（`DeployOrderService`）全部经它；前端唯一分桶 `web/src/components/release/qualityGateBuckets.ts`（`grep -c "^import"` = 0，可被 `node --experimental-strip-types` 直载）。快照结构零改动（AC-9）。
- **旧接口不再被误用**：`.failedRules()` 在 main 代码中已无 `!passed()` 语义调用点，全部为 `GateRuleBuckets.of(...).failedRules()`（`FlowEvidenceService.java:282`、`AdmissionPolicy.java:275`、`DeployOrderService.java:435/455`）；仅剩两处注释提及，属说明性文字。
- **签名对齐**：`GateRuleBuckets.of(List<GateSnapshot.GateRuleResult>)` 三个调用点入参一致；`FlowChainViews.QualityGateSegment` 15 组件与 `FlowChainService.qualityGate(Map)` 透传键名逐一核对（`skippedRules/total/passedCount/waivedCount/failedCount/skippedCount`）；前端 `bucketGateRules/gateCountText/gateCountLabel/failedRuleLines/skippedRuleLines` 与两处组件调用形状由 `vue-tsc` 编译验证。
- **重复实现**：0 处（上轮 D-1 的内联计数已删除，兜底不再写死分母）。

## 7. 工艺观察（P3，不阻塞）

- 质量门列表行只写「4/4 通过」，不展开「· 跳过 1」；分母口径与详情页一致（AC-6 括注即要求对齐列表口径），但同一快照在列表与详情的信息密度不同，读者需进详情才知道「有 1 条被跳过」。
- 时间线「#899 4/4 通过 · 跳过 1」一行无 hover/focus 说明该计数口径（详情页已有结论文案，缺一个 tooltip）。
- 详情页「5 条门禁规则」标题写死 5（本需求外，规则集版本变化时会与计数脱节）。

## 8. 人工补测清单（环境限制，非交付缺陷）

| 项 | 方法 | 工具 |
|---|---|---|
| 卡片/时间线的悬停、聚焦、动效手感 | 人工打开 `/releases/2775`、`/quality-gates/2775` 逐项操作 | 本机 Chrome |
| 色彩对比与「跳过」行可读性 | 人眼核对 `docs/teamflow/demo-shots/r33-qg-skipped-count.png` | 看图 |
| 屏幕阅读器播报状态徽标 | 非本需求范围；如需由可访问性专项覆盖 | NVDA / VoiceOver |

## 9. 结论

- **可验收**：AC-1…AC-11 全部通过；上轮 D-1（P1）、D-2（P3）已按原判据逐条复跑关闭；本轮新增的三个展示面与既有两面同口径，未发现 P0/P1/P2 缺陷。
- 复验期间发现并确认的一处**非缺陷但值得记录**的现象：演示链跑完后再跑 `access-verify a7` 曾报 `STALE approved=5 proposed=5`；根因是种子父需求 `DEMO-R-02` 人天 8 与两子项之和 3+2 不一致，`deliver` 触发父子滚算后偏差 37.5% 超 20% 阈值。dev 已在 `req-service/.../seed/DemoFixture.java` 把父需求人天改为 5（与子项和一致）。本轮实测：`r33-demo-chain.sh up` + `finish` 到 PUBLISHED 之后**不 reset 直接**跑 `bash scripts/access-verify.sh a7` → 16/16 exit 0（此前同序为红），该漂移已从根上消除。
- 演示数据现保留在版本 2775 / 发布单 875（PUBLISHED，快照 1 轮，QG-05 SKIPPED），可直接验收复看；需要干净库执行 `bash scripts/platform-demo-reset.sh`（74/74）。
- 提交仍待 host 收口（分支 `feat/quality-gate-skipped-count`）；提交后按 AC-11 复跑 `bash scripts/release-flow-verify.sh` 使第 [32] 条的区间判据覆盖真实提交区间。
