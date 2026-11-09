# 验收报告 · M6 验收返工（R1 计划批准闸门 / R2 历史单据横幅 / Q-01 空需求集 N/A）

- 需求夹：`docs/teamflow/20261109-r4-m6-r1-r2-rework/`
- 分支：`feat/qa-quality-management`（修复未提交，符合 PRD §8「先修再合并」；工作区仅 14 改 + 6 新 + 本任务夹）
- 验收日期：2026-11-09
- 验收依据：本夹 `PRD.md` AC-1..AC-8 + 基线 `docs/teamflow/20261109-r3-qa-quality-management/` 的 AC 语义不得回归
- 验收角色：产品经理（验收负责人）

## 一、结论

**通过。** AC-1..AC-8 全部满足：R1（计划批准闸门）、R2（历史单据可见）、Q-01（空需求集 N/A）均关闭；全量回归与提交面卫生达标；M6 验收结论由「有条件通过」转为「通过」。
架构核查：蓝图模块逐项落地，无重复实现、无跨库直连、无结构破坏；仅 3 条 P3 观察项留作后续跟进，不阻塞验收。

## 二、独立复验（本轮实际执行，非引用 QA 结论）

| 命令 | 结果 |
|---|---|
| `make quality-verify` | **347/347**（q0 31 / q1 67 / q2 39 / q3 47 / q4 38 / q5 54 / q6 26 / q7 45；较上轮 308 增 39，≥6） |
| `make platform-verify` | **167/167**（AC-1..AC-20；AC-20 需求池 P95 0.222s ≤ 1s） |
| `make platform-test-negative` | **25/25** 被拒且不落库 |
| `make platform-unit` | **140** 个单元测试通过（含 DeployPlanGuardTest、AdmissionLegacySemanticsTest） |
| `make lint` | **16/16** |
| `make verify` | **14/14** |
| `cd web && npm run typecheck` / `npm run build` | exit 0 / exit 0（`✓ built in 4.52s`） |
| `bash logs/teamflow/tf-mw8i22bb-uu7e5w/scripts/qa-plan-guard-503.sh` | **9/9**：停 deliver → 503 且发布单/关联/CREATE 审计行数全不变；恢复后同一请求 201 |
| `bash logs/teamflow/tf-mw8i22bb-uu7e5w/scripts/t1-pending-coverage-check.sh` | **20/20**：命中/空集/跨版本/跨租户/404/401/只读 |

另对 13 个蓝图模块与关键调用点做了只读源码核对（见 §4）。

## 三、AC 逐条核对

| AC | 判据 | 判定 | 验收证据 |
|---|---|---|---|
| AC-1 创建 409、写库前判定 | DRAFT 与 PENDING_REVIEW 覆盖计划均 409 `PLAN_NOT_APPROVED`；`detail.planIds` 命中；三处行数（发布单/需求关联/CREATE 审计）不变 | ✅ | q1 67/67（`q1-plan-lifecycle.sh:137-176`，两态分别断言 + 计数） |
| AC-2 更新同规则、校验单点 | 全仓唯一校验点；更新写库前可复用；无第二份判断 | ✅ | `requireApproved` 全仓仅 `DeployOrderService.java:184` 一处调用；`DeployOrderController` 无 PUT/PATCH（只有 create/evaluate/submit/force-release/execution） |
| AC-3 已批准/无计划 201 | `APPROVED/IN_PROGRESS/COMPLETED/CLOSED` 与无覆盖计划均 201；六项快照 `legacy=false` | ✅ | q1（批准后 201、无计划版本 201）；`pendingStatuses()` 仅 `{DRAFT,PENDING_REVIEW}`，由 SQL 集合参数绑定 |
| AC-4 只读边界 + fail-closed | 只经内部只读端点、不直连 deliver 表；不可用 503 且不落单；不写 deliver | ✅ | 503 探针 9/9；端点探针 20/20（含 404/401/跨租户/只读行数不变）；req `deploy` 包内无 deliver 表 SQL（仅本地列名与注释） |
| AC-5 快照语义 | NULL 与 5 项快照 `legacy=true`、`gate=null`；5 项仍 5 项不伪造第 6 项；6 项 `legacy=false` | ✅ | q5 54/54；`DeployViews.legacyOf` 空集改 `true`；`AdmissionEvaluator` NULL 分支显式 6 参 `legacy=true/gate=null` |
| AC-6 前端提示可达 | 「legacy 且无 QUALITY_GATE 项」在列表外渲染「质量门未启用（历史单据）」；空 items 可达；不伪造 PASS/hash；正常快照不出现 | ✅ | 代码核对（`legacyNotice` 在 header 与列表之间；旧不可达段与死类已删）+ typecheck/build exit 0 + SSR 真实渲染 4/4（HEAD 基线 2/4 FAIL） |
| AC-7 空集 N/A | `totalRequirements=0` → 覆盖子项 N/A、无 100%、不计分母/FAIL；报告子项独立（无报告仍 FAIL）；非空口径不变 | ✅ | q4 38/38；QG-01 内部面 6/6：`coverageApplicable=false`、`coveragePercent=null`、`total=0`、规则仍 FAIL；`document()` 同步 `null` |
| AC-8 合入门禁 | 原 308 条全绿 + 新增 ≥6（实增 39）；其余套件全绿；R1/R2 转 ✅；提交面干净 | ✅ | §二全部命令；`git status` 仅 14 改 + 6 新 + 任务夹，无 `target/`、`.m2/`、`node_modules/`、`dist/` |

**基线回归（r3 的 AC-2..AC-10 与 M6 其它能力）**：`quality-verify` 347/347 + `platform-verify` 167/167，q2/q3/q6/q7 与平台 20 条 AC 均未回归。✅

## 四、架构一致性核查（蓝图对照）

| 蓝图要求 | 核查结论 |
|---|---|
| `PlanLifecycle.pendingStatuses()` 唯一待批口径 | ✅ 新增；`PlanCoverageInternalController` SQL 以 `IN (:pendingStatuses)` 绑定，无状态字面量 |
| `InternalTenantResolver`（头优先→release 回退） | ✅ 新增；`QualityInternalController` 私有 `tenantOf` 已删除并委托，行为/错误码不变 |
| `PlanCoverageInternalController` 只读端点 | ✅ `GET /internal/test-plans/pending-coverage`；保留 `/*scope:p*/`；空需求短路；`DISTINCT` + 升序去重；无写操作 |
| `DeliverPlanCoverageClient` 唯一出口 | ✅ 走 `InternalTokenClient.deliver(300ms)`，`Map` 解析；连接/超时/非 2xx/载荷异常一律 `PlanCheckUnavailable` |
| `DeployPlanGuard` 单点三判定 | ✅ 无需求放行 / 不可用 503 / 命中 409；`DeployOrderService.java:184` 位于 resolve（:183）之后、`save`（:202）与审计之前 |
| `DeployErrors` 两码与 detail 形状 | ✅ `PLAN_NOT_APPROVED` detail=`{planIds,plans,requirementIds,hint}`；`PLAN_CHECK_UNAVAILABLE` detail=`{reason}`；与 ReportService 同码不同上下文按设计不合并 |
| `DeployViews.legacyOf` 单一来源 | ✅ 空集 `true`；前端只消费 API 的 `legacy`，不自算 |
| `AdmissionEvaluator` NULL 分支 | ✅ 显式 6 参 `legacy=true/gate=null`，不依赖构造器派生 |
| `QualityGatePanel` 列表外横幅 | ✅ `hasQualityGateItem`/`legacyNotice`；横幅在列表外；空 items 与历史单均可达 |
| `DeployOrderListView` 409 文案 | ✅ `ApiError.code==='PLAN_NOT_APPROVED'` 读 `detail.planIds`；503 回退 `messageOf`；409 不弹 toast 约定不变 |
| checker 追加不新建 | ✅ 仅 q1/q4/q5 内追加；新增 39 条可在 347−308 核对 |

**无重复实现 / 无适配漂移**：未复制 `GateEvaluator`/`QualityAggregator`/`MetricFormulas`/`ScopeSql`；`legacy` 判定仅 `legacyOf`；req 侧无 deliver 表 SQL。
**偏差（P3，非本次引入，不阻塞）**：`PlanLifecycleService.java:165` 仍用 `!"DRAFT".equals(status) && !"PENDING_REVIEW".equals(status)` 字面量；与 `pendingStatuses()` 当前取值一致、无行为差异，建议下轮改为集合引用以彻底落实「唯一口径」。

## 五、遗留、备案与人工补测（均不阻塞）

1. **P3-1 状态字面量**：`PlanLifecycleService.java:165`（见 §四），建议下轮一行清理；当前无行为差异。
2. **P3-2 范围外修复备案**：`QualityGateService` 新增规则结果 upsert（证据落库）不在 TECHNICAL 模块清单，但 AC-7 的证据断言依赖它；已有独立探针 15/15，行为正向（未配置版本不再静默丢证据）。建议后续技术文档补一行说明，本轮不视为偏差。
3. **P3-3 过时注释**：`scripts/tests/quality/q4-gate-admission.sh:176` 仍写「只在 configure 时插行」，upsert 后已不成立；建议随下轮脚本改动修正。
4. **人工视觉补测（环境限制，非交付缺陷）**：横幅样式/对比度、409 长计划号换行、503 提示可理解性、键盘可达性。浏览器沙箱 crashpad 需 `PROCESS_ALL_ACCESS` 被拒；PRD AC-6 已允许「静态断言 + typecheck/build + 人工补测清单」作为证据下限，本条不影响判定。

## 六、M6 结论转换声明

- 基线 r3 §4 的两条缺陷：**R1 ✅ 已修复**（写库前 409、行数不变、fail-closed 503）、**R2 ✅ 已修复**（NULL/5 项快照 legacy 可见、前端横幅可达）；观察项 **Q-01 ✅ 已关闭**（空集 N/A，不再假绿）。
- 本夹为 M6 返工验收的权威记录，声明 **M6 验收结论：通过**，取代 r3 `ACCEPTANCE.md` §4 的「有条件通过」；按 ADR-0008 不修改 r3 历史文件。
- 合入：全绿已达成，按 PRD「先修再合并」由用户/宿主确认后合并；本轮结论不代替合并动作。

验收结论：✅ 通过
