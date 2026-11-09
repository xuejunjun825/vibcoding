# QA 报告 · M6 验收返工（R1 计划批准闸门 / R2 历史单据横幅 / Q-01 空需求集 N/A）

- 需求夹：`docs/teamflow/20261109-r4-m6-r1-r2-rework/`
- 分支：`feat/qa-quality-management`（工作区改动全部属于本需求，未提交）
- 测试日期：2026-11-09
- **结论：可验收** —— AC-1..AC-8 全部以真实栈执行验证通过；R1、R2 两条遗留缺陷均以原复现路径确认修复；全量回归 20 套命令全绿；P0/P1/P2 缺陷 0 条。

---

## 1. 测试范围

| 维度 | 内容 |
|---|---|
| 本轮验收项 | AC-1..AC-8（计划批准闸门 fail-closed、legacy 语义、前端历史横幅、QG-01 空需求集 N/A、回归基线） |
| 回归面 | AC-9..AC-20（quality 域 q0..q7、platform 全量 167 项、负向、单测、lint、环境 verify、前端 typecheck/build） |
| 变更面 | deliver 4 文件（2 新增）+ req 6 文件（4 新增）+ web 2 文件 + q1/q4/q5 三个既有 checker 追加断言；共 20 个文件（14 改 / 6 新） |
| 不在范围 | M6 之外的其它域；浏览器真实渲染（环境限制，见 §7） |

## 2. 环境与执行方式

- 栈：网关 8080 + req 8082 + deliver 8083 + demo 8081 + MySQL + Nacos（`make platform-up` / `scripts/dev-up.sh`），全部健康（`/internal/health` 200，`make verify` 14/14）。
- 构建：`mvn -o -Dmaven.repo.local=$PWD/.m2/repository ...`；deliver 在 qg 证据探针中重建重启，运行中 jar 即当前工作树构建。
- 浏览器：沙箱进程受限（Chromium crashpad 需 `PROCESS_ALL_ACCESS`，被拒），**视为环境限制而非交付缺陷**；前端以 vite SSR 渲染真实组件 + 事件无关断言替代（§3 QA-12），视觉项入人工补测清单。
- 断言纪律：一条命令一个检查；每条命令均记录 exit code（下述全部 exit 0，除特别标注）。

## 3. 用例与结果（全部实际执行）

| 编号 | 用例 | 命令 | 结果 |
|---|---|---|---|
| QA-01 | 质量域全量套件（q0..q7） | `make quality-verify` | **347/347** PASS（q0 31 / q1 67 / q2 39 / q3 47 / q4 38 / q5 54 / q6 26 / q7 45），较上轮 308 增 39 条 |
| QA-02 | 平台全量验收 AC-1..AC-20 | `make platform-verify` | **167/167** PASS（含 AC-20 需求池 P95 0.217s ≤ 1s） |
| QA-03 | 负向用例 | `make platform-test-negative` | **25/25** 被拒且不落库 |
| QA-04 | 单元测试 | `make platform-unit` | **140** 个全部通过（含 DeployPlanGuardTest 4、AdmissionLegacySemanticsTest 3） |
| QA-05 | 工程 lint（L1..L14） | `make lint` | **16/16** |
| QA-06 | 环境体检（端口仅监听 + HTTP 语义） | `make verify` | **14/14** |
| QA-07 | 前端类型 | `cd web && npm run typecheck` | exit 0，0 error |
| QA-08 | 前端构建 | `cd web && npm run build` | exit 0，`✓ built in 4.47s` |
| QA-09 | R1 主路径（真实栈，q1 内） | `bash scripts/tests/quality/q1-plan-lifecycle.sh` | **67/67**：DRAFT→409 `PLAN_NOT_APPROVED`、`detail.planIds` 命中、`deploy_order`/`deploy_order_requirement`/`audit_log` 行数不变；PENDING_REVIEW→409；批准后 201 且六项快照 `legacy=false`；无计划版本 201 |
| QA-10 | QG-01 空需求集（真实栈，q4 内） | `bash scripts/tests/quality/q4-gate-admission.sh` | **38/38**：`actual` 含「需求覆盖 N/A（无需求可覆盖）」、不含 `100%`、规则仍 FAIL；落库证据 `totalRequirements=0` / `coverageApplicable=false` / `coveragePercent` 为 JSON null |
| QA-11 | R2 legacy API + 前端静态（q5 内） | `bash scripts/tests/quality/q5-waiver-replay.sh` | **54/54**：NULL 快照 `legacy=true`/`gate=null`/`items=0`；五项快照 `legacy=true`、items=5、无 QUALITY_GATE；横幅开关与文案存在、旧不可达条件已删 |
| QA-12 | AC-4 fail-closed（此前无覆盖，QA 自建） | `bash logs/teamflow/tf-mw8i22bb-uu7e5w/scripts/qa-plan-guard-503.sh` | **9/9**：停 deliver → 503 `PLAN_CHECK_UNAVAILABLE` + `detail.reason` 非空 + 发布单/关联/CREATE 审计行数全不变；重启后同请求 **201**（证明是"不可用"而非"永久拒绝"） |
| QA-13 | 内部端点契约/租户/只读 | `bash logs/.../scripts/t1-pending-coverage-check.sh` | **20/20**：命中 plan、空需求短路、跨版本空、异租户空且不返回他租户计划、缺版本 404 `RELEASE_NOT_FOUND`、无令牌 401、`test_plan` 行数不变 |
| QA-14 | QG-01 求值内部面 | `bash logs/.../scripts/t1-qg01-empty-set-check.sh` | **6/6**：zero-需求版本 N/A、规则 status 仍 PASS/FAIL、快照 `total=0/percent=null/applicable=false`；有需求版本旧格式「X%（c/t）」零回归 |
| QA-15 | 前端真实渲染（SSR，QA 重跑） | `npx vite build -c .../vite.probe.config.ts` + `node .../render-panel-entry.js` | **4/4** PASS：legacy+空 items 出横幅、新单 6 项、新单空 items 空态、legacy+有项以数据为准（HEAD 基线 2/4 FAIL，根因可复现） |
| QA-16 | 越界修复（证据 upsert）行为 | `bash logs/.../scripts/qg-evidence-upsert-check.sh` | **15/15**：未配置版本求值后建满 5 行规则、evidence 可解析、二次求值不重复行、配置值不被覆盖 |
| QA-17 | 提交面卫生 | `git status --porcelain` / `git ls-files --others` | 仅 14 改 + 6 新代码 + 本任务夹；无 `target/`、`.m2/`、`node_modules/`、`dist/`、IDE/密钥文件（`.gitignore` 已覆盖） |

## 4. 跨模块接口一致性（纯静态核对，全部命中）

| 调用方 | 被调成员 | 核对结论 |
|---|---|---|
| `req/.../integration/DeliverPlanCoverageClient.java:69-71` | `GET /internal/test-plans/pending-coverage?releaseId&requirementIds(逗号)` | 路径/参数名与 `deliver/.../internal/PlanCoverageInternalController.java` 一致；响应键 `planId/planName/status/requirementIds` 与客户端 `PlanRef` 解析逐字一致 |
| `DeliverPlanCoverageClient` | `InternalTokenClient.deliver(300ms)` | 唯一服务间出口，含内部 token + `X-Tenant-Id`；无第二套 HTTP 客户端 |
| `req/.../deploy/DeployPlanGuard.java:36` | `coverage.pending(long, List<Long>)` / `DeployErrors.planNotApproved` / `planCheckUnavailable` | 签名与参数形状一致；`catch` 仅捕 `PlanCheckUnavailable`（客户端保证所有故障都转该异常） |
| `req/.../deploy/DeployOrderService.java:184` | `planGuard.requireApproved(release.getId(), requirementIds)` | 位于 `resolveRequirementIds`（:183）之后、`repository.save`（:202）与审计（:212）之前；全仓仅此一处调用 |
| `deliver/.../internal/PlanCoverageInternalController.java:44,73` | `PlanLifecycle.pendingStatuses()` | SQL 以 `IN (:pendingStatuses)` 绑定，无 `'DRAFT'` 字面量 |
| `deliver/.../internal/QualityInternalController.java` | `InternalTenantResolver.of(header, releaseId)` | 私有 `tenantOf`/`parseTenant` 已删除（全仓仅 `GateController` 另有同名方法，属不同控制器/不同回退语义，非本模块） |
| `web/src/views/deploy/DeployOrderListView.vue` | `ApiError`（`web/src/api/client.ts:27` export）`.code` / `.detail.planIds` | 导入符号真实导出；409 分支读 `detail.planIds`，与后端 detail 键一致 |
| `web/.../QualityGatePanel.vue` | `props.legacy` / `gateItems` | `DeployOrderDetailView.vue:246` 传 `Boolean(admission?.legacy)`，与 `DeployViews.legacyOf` 单一来源一致，前端不自算 |

## 5. 架构核查（蓝图对照）

- **按蓝图落地**：`PlanLifecycle.pendingStatuses()`、`InternalTenantResolver`（抽取后 `QualityInternalController` 改为委托，私有解析删除）、`PlanCoverageInternalController`（只读、`/*scope:p*/`、空集短路）、`DeliverPlanCoverageClient`（唯一出口，300ms 预算）、`DeployPlanGuard`（三判定单点）、`DeployErrors` 两码、`DeployViews.legacyOf`（空集 true）、`AdmissionEvaluator` 显式 6 参、`DeployOrderService` 单点调用、`QualityGatePanel` 列表外横幅、`DeployOrderListView.planErrorText` —— 与蓝图接口签名逐一吻合。
- **重复实现**：新代码无复制；`legacy` 判定仅 `legacyOf` 一处；计划待批集合仅 `pendingStatuses()` 一处被 SQL 绑定。既有代码残留 1 处字面量（见 D-01，P3，无行为差异）。
- **抽象未抽取/结构破坏**：未发现。跨域方向正确（req 只经内部只读端点，不直连 deliver 表：`req/.../deploy` 包内 `test_plan/quality_gate` 仅命中本地列名与注释，无 SQL）。
- **越界改动**：`QualityGateService.java:365-400` 新增 `recordRuleOutcomes`（upsert 落证据），不在蓝图模块清单内，属上一轮 OUT-OF-SCOPE 建议的自行收口（见 D-02，P3，已有 15 条独立探针）。

## 6. 缺陷表

**未发现缺陷（P0/P1/P2 = 0）**；下列为 P3 观察项，不阻塞验收。

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
|---|---|---|---|---|---|---|---|---|
| D-01 | P3 | 架构（状态集合口径） | 读 `deliver-service/src/main/java/com/example/deliver/quality/plan/PlanLifecycleService.java:165`：`boolean approved = !"DRAFT".equals(status) && !"PENDING_REVIEW".equals(status);` | 蓝图要求"状态集合只允许一个来源"，应改为 `PlanLifecycle.pendingStatuses().contains(status)` | 仍是字面量；与 `pendingStatuses()` 当前取值相同，**无行为差异**，仅未来扩状态时存在漂移风险 | AC-3（间接） | `grep -n '"DRAFT".equals(status)' deliver-service/src/main/java/com/example/deliver/quality/plan/PlanLifecycleService.java` | 修复后该 grep 无输出，且 `make platform-unit` 仍 140/140 |
| D-02 | P3 | 交付范围（越界修复） | 查看 `git diff deliver-service/src/main/java/com/example/deliver/quality/gate/QualityGateService.java`：新增 `INSERT ... ON DUPLICATE KEY UPDATE` 记录规则结果 | 本需求 TECHNICAL/蓝图未列该文件，范围外改动应在验收说明中显式备案 | 已实现且验证通过（15/15），修复了"未 configure 版本证据静默丢失"，但 PRD/技术方案未记录 | AC-7（证据落库，间接） | `bash logs/teamflow/tf-mw8i22bb-uu7e5w/scripts/qg-evidence-upsert-check.sh` | 保持 15/15 通过，并在 ACCEPTANCE 中备案该范围外修复 |
| D-03 | P3 | 测试脚本注释 | 读 `scripts/tests/quality/q4-gate-admission.sh:176` 注释「quality_gate_rule 只在 configure 时插行，未配置的版本求值只算不落证据」 | 注释应反映 upsert 后的真实行为 | 注释已过时（D-02 之后不成立），会误导后续维护者 | AC-7（可维护性） | `sed -n '176p' scripts/tests/quality/q4-gate-admission.sh` | 修正注释后同一行不再出现「只在 configure 时插行」 |

## 7. 人工补测清单（环境限制，非交付缺陷）

| 项 | 验收标准 | 方法/工具 |
|---|---|---|
| 历史横幅视觉 | 横幅在列表外、列表为空时可见、与告警条样式协调、文字对比度 ≥ 4.5:1 | Chrome DevTools 打开发布单详情（legacy 单），检查对比度与间距（沙箱无法启动 Chromium：crashpad 需 `PROCESS_ALL_ACCESS`） |
| 409 表单错误观感 | 长计划号换行不溢出、错误定位在表单内、无 toast 干扰 | 浏览器在新建弹窗提交引用 DRAFT 计划的版本，检查 `409` 文案换行 |
| 503 用户提示 | 服务不可用时提示可理解、可重试 | 停 deliver 后浏览器提交建单，观察 `messageOf` 回退文案 |
| 动效/焦点态 | 横幅入场与既有提示一致、键盘可达 | 人工浏览 + DevTools Accessibility 面板 |

## 8. 验收判定

| AC | 判定 | 依据 |
|---|---|---|
| AC-1 DRAFT/PENDING_REVIEW → 409 + planIds，写库前判定、行数不变 | ✅ 通过 | QA-09（DRAFT、PENDING_REVIEW 两态；行数/审计三重计数不变） |
| AC-2 校验单点、更新路径复用 | ✅ 通过 | 全仓唯一调用点 `DeployOrderService:184`；控制器无 update/PUT 端点 |
| AC-3 已批准或无覆盖计划 → 201 | ✅ 通过 | QA-09（批准后 201；无计划版本 201）；判定集由 `pendingStatuses` 仅 `{DRAFT,PENDING_REVIEW}` 绑定 |
| AC-4 只读内部接口 + 不可用 503 不落单 | ✅ 通过 | QA-12（9/9）、QA-13（20/20，含 401/404/跨租户/只读）、静态核查无直连 deliver 表 |
| AC-5 NULL / 五项快照 legacy=true、gate=null | ✅ 通过 | QA-11（54/54）；六项快照 `legacy=false` 不误伤 |
| AC-6 前端列表外横幅、空 items 可见、不伪造 PASS | ✅ 通过 | QA-15（4/4 真实渲染）+ QA-11 静态断言 |
| AC-7 空需求集 QG-01 N/A、不显示 100%、不掩盖 FAIL | ✅ 通过 | QA-10（38/38）、QA-14（6/6） |
| AC-8 回归基线全绿 + 提交面干净 + R1/R2 关闭 | ✅ 通过 | QA-01..QA-08、QA-17；新增断言 39 条（≥6） |

## 9. 证据脚本位置（随 run 归档保留）

- R1 主路径与行数断言：`scripts/tests/quality/q1-plan-lifecycle.sh`
- 空集 N/A 与证据落库：`scripts/tests/quality/q4-gate-admission.sh`
- legacy API + 前端静态：`scripts/tests/quality/q5-waiver-replay.sh`
- fail-closed 503（QA 新增）：`logs/teamflow/tf-mw8i22bb-uu7e5w/scripts/qa-plan-guard-503.sh`
- 内部端点契约 / QG-01 内部面 / 证据 upsert：`logs/.../scripts/t1-pending-coverage-check.sh`、`t1-qg01-empty-set-check.sh`、`qg-evidence-upsert-check.sh`
- 前端 SSR 渲染探针：`logs/.../scripts/render-panel-cases.ts`、`render-panel-entry.ts`、`vite.probe.config.ts`、`render-panel-probe.md`
