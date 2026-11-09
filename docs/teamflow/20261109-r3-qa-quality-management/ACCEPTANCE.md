# 验收报告 · M6 质量管理补全（20261109-r3）

| 项 | 值 |
|---|---|
| 需求 | 测试计划完整管理 + 质量门闭环：M6「质量管理补全」（10 条 AC） |
| 分支 | `feat/qa-quality-management`（实现未提交；工作树含本任务夹与 `memory.md` 一行） |
| 任务夹 | `docs/teamflow/20261109-r3-qa-quality-management/` |
| 验收依据 | `PRD.md`（AC-1…AC-10、§15 默认值）、`TECHNICAL.md`（质量域架构）、`QA-REPORT.md`（第 2 轮，OPEN 2×P3） |
| 基线依赖 | `docs/teamflow/20261108-r2-devops-req-platform/`（历史任务夹未修改） |
| 验收日期 | 2026-11-09 |

## 1. 验收结论摘要

10 条 AC 逐条核对：AC-2…AC-6、AC-8…AC-10 全部通过；**AC-1 的 1.4「未批准计划不得被发布单引用」在交付树中未实现（P2）**；**AC-7 的 7.5「历史发布单展示质量门未启用」只在 API 层成立、前端提示不可达（P3）**。架构与注入蓝图一致（§5），未发现重复实现、适配器漂移或结构破坏。

验收人独立复跑门禁全绿：`quality-verify` 308/308、`platform-verify` 167/167、`platform-test-negative` 25/25、`platform-unit` 133/133、`lint` 16/16、`verify` 14/14、`web typecheck` 0 error，exit 0。但 308 条质量断言未覆盖 AC-1.4 与 AC-7.5，故不能据「全绿」判通过。**判定：⚠️ 有条件通过**；按 §4 的 R1/R2 修复并复验后转 ✅。

## 2. 独立复跑证据（验收人执行，非引用 QA）

| 命令 | 结果 |
|---|---|
| `bash scripts/quality-verify.sh` | **308/308**（q0 31、q1 52、q2 39、q3 47、q4 29、q5 39、q6 26、q7 45），exit 0 |
| `bash scripts/platform-verify.sh` | **167/167**（AC-1…AC-20），exit 0 |
| `bash scripts/platform-test-negative.sh` | **25/25**，exit 0 |
| `bash scripts/platform-unit.sh` | **133/133**，exit 0 |
| `bash scripts/tests/lint.sh` | **16/16**（含 L13 交付面、L14 报告只经 QualityAggregator），exit 0 |
| `bash scripts/verify.sh` | **14/14**（r1 基线未破坏），exit 0 |
| `cd web && pnpm typecheck` | exit 0，0 error |
| 验收补测（AC-1.4 / AC-7.5） | 见 §4：AC-1.4 复现 **201 落库**；AC-7.5 API 层 `gate=null, legacy=true`，UI 提示不可达 |

补充：栈 8080/8082/8083/5173 均 200；**业务断言之外**结论均来自本轮实跑，不使用 QA 报告替代。

## 3. 逐条 AC 验收

| AC | 关键判据（PRD） | 证据 | 判定 |
|---|---|---|---|
| AC-1 | 七态生命周期、allowedNext 与状态表同源、变更 reason/revision/change_log；**1.4 未批准计划被引用 → 409 PLAN_NOT_APPROVED 且不落单** | q1 52/52（生命周期/变更）；1.4 实测 **201**（§4 R1） | ⚠️ |
| AC-2 | 多轮次历史不可覆盖；开轮唯一键兜并发 409；作用域三态；关轮未执行 409；结论单调下调 | q2 39/39；t3/t4 覆盖 | ✅ |
| AC-3 | 结构化准出条件，未满足 → 409 `unmet[{key,actual,expected,fixHint}]` | q2 39/39；ExitCriteriaEvaluator 单点 | ✅ |
| AC-4 | 报告 8 段、版本化不可变、XLSX/PDF 水印、三级会签顺序与幂等 | q3 47/47；QA 导出深检 14/14、真栈会签 200/200/200、重复签 409 | ✅ |
| AC-5 | 准入第 6 项只透传门禁结论；直连旁路同拒；门禁不可用 fail-closed | q4 29/29（含直连 8082 409 `QUALITY_GATE_FAILED`）；q7 NO-GO 3/3 | ✅ |
| AC-6 | 规则级豁免；P0/P1 与 QG-04 硬红线；1–30 天；强制放行退役 | q5 39/39（QG-04 400、0/31 天 400、单规则不整车放行）；t7 26/26（`FORCE_RELEASE_RETIRED`） | ✅ |
| AC-7 | 快照可回放/diff/不写库；**7.5 历史无门禁单据返回 gate=null 且展示「未启用」，不得伪造 PASS** | q5/t6 回放 200 + diff；7.5 API 正确、**UI 提示不可达**（§4 R2） | ⚠️ |
| AC-8 | 逃逸在 INSERT 判定一次、窗口变更不改史；5 项指标单点计算 | q6 26/26；MetricFormulas 单点 | ✅ |
| AC-9 | 策略版本化、分配差额 400 `ASSIGNMENT_MISMATCH`、环境台账/字典 | q7 45/45；环境字典仅 AVAILABLE/DISABLED/MAINTENANCE | ✅ |
| AC-10 | Go/No-Go 评审记录；NO-GO 需二次确认，未确认 409 `NO_GO_CONFIRM_REQUIRED` | t8 24/24；q7 3/3 | ✅ |

> AC-4 的导出深检（XLSX 8 sheet 顺序+水印、PDF 3 页 3 水印、STSong-Light CID）与会签真栈证据由 QA 提供，验收人复跑了 q3 47/47、typecheck 与构建；未重复执行依赖固定报告 65/117 的深检脚本。

## 4. 缺陷与返工条件

### R1（P2，阻塞 AC-1.4）发布单创建未校验测试计划批准状态

复现（验收人实测）：
1. 新建版本 167，加入需求 5（该需求有 3 条用例，未被其他版本占用，加入返回 200）。
2. 在版本 167 上建计划 142，覆盖需求 5 的用例，状态 **DRAFT**。
3. `POST /api/req/deploy-orders`，`releaseId=167`、`requirementIds=[5]` → **HTTP 201**，`deploy_order` 行数 115→116。

- 期望：409 `PLAN_NOT_APPROVED`，body 列出 `planIds`，`deploy_order` 行数不变。
- 实际：无任何计划校验。`req-service/src/main/java/com/example/req/deploy/DeployOrderService.java:152` 的 `create(...)` 不查询 `test_plan`/`test_plan_case`；全仓 `PLAN_NOT_APPROVED` 仅出现在 `deliver-service/src/main/java/com/example/deliver/quality/report/ReportService.java:157`（报告生成）。
- 影响：未批准计划可在创建期被发布单引用；提交期仍被 QG-02（要求已签报告）拦截，不能真正发布，但违反 AC-1.4 的准入输入治理，并留下脏关联。
- 修复：create/update 组装需求后按需求查覆盖计划；存在计划且状态 ∉ APPROVED（DRAFT/PENDING_REVIEW）→ 409 `PLAN_NOT_APPROVED` + `planIds`；**无计划保持 r2 行为（允许）**。补回归：已批准计划/无计划 → 201。
- 复验判据：同一夹具返回 409 且 `planIds` 含 142，行数不变；`quality-verify` 全绿。

### R2（P3，AC-7.5 展示）历史 5 项快照不显示「质量门未启用」

- API 正确：5 项快照（无 QUALITY_GATE）→ `legacy=true`、`gate=null`，不伪造 PASS。
- UI 缺陷：`web/src/components/quality/QualityGatePanel.vue:232` 的提示条件是 `item.code === 'QUALITY_GATE' && legacy && !itemHasGateRules(item)`；而 legacy 快照按定义没有 QUALITY_GATE 项（`req-service/src/main/java/com/example/req/deploy/DeployViews.java:82-87`），该提示不可达。另 `AdmissionEvaluator.java:119-120` 对 NULL admission 返回空 items + `legacy=false`，页面也不出现「未启用」。
- 影响：仅展示层；不伪造 PASS，不阻断发布。种子库无 5 项历史快照（`admission_result_json NOT LIKE '%QUALITY_GATE%'` = 0），故为静态判定。
- 修复：legacy 且无 gate 项时渲染合成的「质量门未启用（历史单据）」行/横幅；补一条夹具与断言。

### 观察项（不阻断）

- **Q-01（P3，QA 遗留）**：`deliver-service/src/main/java/com/example/deliver/quality/gate/GateEvaluator.java:350` 用 `total == 0 ? 100` 计算覆盖，空需求集合 QG-01 判 100%。PRD 只要求「每个需求覆盖」，空集属真空满足；建议明确为 N/A 或 FAIL 并补断言（下一轮质量项）。
- **Q-02（QA 报 OPEN，交付树已修）**：`web/src/views/quality/QualityGatesView.vue:246-247`、`305-306` 已有 EmptyState（门禁/豁免空态）；QA 报告该条结论滞后。

## 5. 架构一致性（强制项）

| 检查 | 结论 | 证据 |
|---|---|---|
| 域落位与模块顺序 | 符合蓝图 | 蓝图 32 个文件全部存在；质量域集中在 `deliver-service/.../quality/{aggregate,plan,report,gate,metric,quality,strategy,env,workbench}`；req 仅保留 Go/No-Go 与准入第 6 项 |
| 门禁单点 | 无漂移 | 唯一算法 `GateEvaluator`；req 只透传（`req-service/.../deploy/AdmissionPolicy.java:225` 注释与 `qualityGate()` 实现无 QG 重算） |
| result_digest 单点 | 无漂移 | 质量域唯一 `MessageDigest` 在 `deliver-service/.../quality/aggregate/QualityDigest.java:76`；其余命中为 r2 既有密码/附件类 |
| 聚合单点 | 无漂移 | `QualityAggregator` 被 PlanLifecycleService、GateEvaluator、ReportService、TestPlanQuery 共用；L14 断言报告取数只经该实现 |
| 指标公式单点 | 无漂移 | 5 项指标/比率统一走 `MetricFormulas`，12 处调用；controller/前端无二次除法 |
| 状态清单点 | 无漂移 | `StateTables` 定义 PLAN/ROUND/REPORT/GATE/WAIVER/REVIEW；allowedNext 与回填校验同源 |
| 迁移 | 符合 | `V5__quality_management.sql` 13 张新表 + 5 表增列 + 5 指标行 + 幂等回填；`V6` 仅扩 status 宽度（V5 不可原地改） |
| 权限 | 符合 | `PermissionMatrix` 26→41 码（+15 质量码）、410 格；`PermissionRouteTable` 质量规则插在 `/test-plans`、`/releases`、`/deploy-orders` 兜底之前 |
| 前端接线 | 符合 | `web/src/router/routes/quality.routes.ts` 8 条路由；11 个质量页面均有构建 chunk；`dict.ts` 统一状态文案 |
| 既有结构 | 未破坏 | `platform-verify` 167/167、`verify` 14/14；r2 契约/权限/门禁未被重写 |

**结论**：实现与架构蓝图一致，未发现重复实现、适配器漂移或「应抽未抽」；本次条件项是功能缺口，不是架构返工。

## 6. 未覆盖与风险（不阻断）

1. **真浏览器视觉**：QA 未在任务夹保存截图，验收人未做像素级核对；上线前按 `QA-REPORT.md` §8 人工清单过一遍（PDF 水印、XLSX 8 sheet、空态/焦点/键盘）。
2. **AC-7.5 无真实夹具**：结论基于代码路径；R2 修复时需补 5 项历史快照夹具。
3. **验收探针数据**：dev 库新增 3 张 DRAFT 发布单（#114–#116）及版本 165–167、计划 140–142，未删除；不影响断言与交付面。
4. **QA 套件缺口**：308 条断言未覆盖 AC-1.4/7.5；建议把 §4 的复现步骤并入 q1/q5，避免下一轮再漏。
5. **深业务流**：仍以 mock E2E 为主（QA 已说明），真栈仅冒烟 6 项；跨页深流程建议后续补 E2E。

## 7. 验收意见

交付主体（计划生命周期、轮次、报告/会签/导出、门禁/豁免/回放、准入第 6 项、生产回流、指标、Go/No-Go、前端 8 页）达到 PRD 的可复现标准；负向目标（绕过按钮、直连旁路、P0/P1 豁免、强制放行复活）均有断言守住。不能直接判 ✅ 的原因只有 AC-1.4 创建期计划批准校验缺失，另 AC-7.5 展示未落地。两项均为窄改，建议按 §4 R1/R2 修复后复跑 `quality-verify` 与本文补测夹具，再由验收人复验转 ✅。

验收结论：⚠️ 有条件通过
