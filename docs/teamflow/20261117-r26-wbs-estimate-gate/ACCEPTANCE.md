# 验收报告 · r26 工时评估初审（WBS_ESTIMATE）与前置门

- 任务夹：`docs/teamflow/20261117-r26-wbs-estimate-gate/`（PRD / DESIGN / TECHNICAL / QA-REPORT 同夹）
- 分支：`feat/wbs-estimate-approval`（未提交改动 42 条，全部落在 approval / requirement / seed / web / scripts / docs）
- 验收角色：产品经理（验收负责人）；验收日期 2026-11-17
- 结论：**⚠️ 有条件通过**——18 条 AC 中 17 条功能面全绿、AC-18 部分达成（`evidence/` 截图缺失 + memory 实测基线未随修复轮订正）；无 P0/P1/P2 未决；条件 2 项、建议 3 项。

## 1. 验收方法与独立复核

不只看 QA 结论：对关键面做了独立复核（真栈在当前会话可用，`8080` 网关 + `8082` req-service）。

| 复核项 | 命令 / 动作 | 结果 |
|---|---|---|
| 新增单测真跑（5 类） | `mvn -o -q -Dmaven.repo.local=$PWD/.m2/repository -pl req-service test -Dtest='WbsEstimate*Test'` | exit 0，**28 passed**（Gate 5 / Submit 8 / Uniqueness 6 / Stale 5 / Callback 4） |
| 门前端断言（静态+真栈） | `node scripts/tests/access/a7-estimate-gate.mjs` | **16/16**（R1–R6 打到真栈：PDR/RD 详情带 `estimate`、APPROVED 夹具在位、厂商视角读自己需求仍带块） |
| AC-17 被 QA 漏跑的两套 | `bash scripts/analytics-i18n-verify.sh`、`bash scripts/collab-verify.sh` | **analytics 74/74**、**collab 257/257（s0–s8，fail=0）**——QA 报告未列这两项，本次补跑全绿 |
| 判定顺序（代码事实） | `RequirementService.transition` 381/386/424/434 行 | `requireWritable`(403) → `authorize`(403) → `stateMachine.target`(409 `ILLEGAL_TRANSITION`) → r23 既有门 → r26 评估门 ✓ |
| 409 detail 形状 | `RequirementErrors.estimateNotApproved` 104–111 行 | 恰好三键 `{requirementId, status(默认 NONE), required=ESTIMATE_APPROVED}` ✓ |
| 六态同源 | `WbsEstimateService` 46/127/188/272 行 | `STALE_THRESHOLD=0.20` 单点；`statusOf` 唯一派生；`> 阈值` 严格大于 ✓ |
| 12 组矩阵 | `WbsEstimateGateTest` 70–81 行 | 6 态 × 2 动作逐组断言 ✓ |
| AC-4 驳回原因 | `ApprovalEngine.java:195` | `REASON_REQUIRED` 400 前置校验已落地（QA 的 D-01 已关闭）✓ |
| 依赖零新增 | `git diff --stat HEAD -- pom.xml web/package.json` | 空 ✓ |
| 证据截图 | `find docs/teamflow/... logs/teamflow -name '*.png'` | **无**（`evidence/` 目录不存在）✗ |

## 2. 逐条 AC 核对

| AC | 结论 | 证据（QA 实测 + 本验收复核） |
|---|---|---|
| AC-1 第五类单据可发起 | ✅ | 种子 `SeedService:268` 建 `WBS_ESTIMATE/ANY` 已发布定义；`POST /{id}/estimate` 201 PENDING、需求状态不变；BUS/终态 400 不建单（QA §3） |
| AC-2 提交校验 | ✅ | 0 / 1.25 / 501 字 / 未知字段 → 400；0.5 天 + 500 字 → 201（QA 边界 2/2） |
| AC-3 初审通过 | ✅ | 人天回写 0.5 精度、实例 APPROVED、`ESTIMATE_APPROVED` 动态、需求状态不变（回写 4/4 单测 + 真栈） |
| AC-4 驳回必填原因 | ✅ | 无/空白原因 → 400 `REASON_REQUIRED` 且实例仍 PENDING；原因进卡片与时间线（复验 6/6，`ApprovalEngine:195`） |
| AC-5 唯一性与重提 | ✅ | 同人天 409 `ESTIMATE_ALREADY_APPROVED`；两轮通过后 APPROVED=1；旧 PENDING 置 SUPERSEDED；撤回回落旧单（7/7，唯一性单测 6/6） |
| AC-6 双向可查 | ✅ | 审批中心 `?bizType=WBS_ESTIMATE` 筛选 + 「工时评估」标签；详情页签按 `detail.estimate.instanceId` 取实例（a7 R4/R5）；非归属人 403 |
| AC-7 PDR schedule 门 | ✅ | 409 + 三键 detail；状态与 `STATE_CHANGE` 均不变；APPROVED 后 200 转 SCHEDULED（41/41 矩阵） |
| AC-8 RD start_dev 门 | ✅ | 同上，TODO 被拦、通过后 IN_DEV，detail.status 六态如实回报 |
| AC-9 四态一律拦 | ✅ | NONE/PENDING/REJECTED/STALE 真栈；SUPERSEDED 由矩阵单测 + 残留单覆盖，不统一回 NONE |
| AC-10 ±20% 失效 | ✅ | 10→12.0 放行、10→12.5 拦；PUT 与 deliver 两路径；null → STALE（`WbsEstimateService:127/272` 严格大于） |
| AC-11 allowedActions 同源 | ✅ | 12 组单测真跑全绿；`statusOf` 一处派生、`0.20` 单点；口径边界与前端渲染解耦已在代码注释与 memory 写明 |
| AC-12 判定顺序不回归 | ✅ | 403 → `ILLEGAL_TRANSITION` → r23 门 → 评估门（代码行序 + 真栈 41/41）；r23 门错误码不变 |
| AC-13 卡片 + 动作行禁用态 | ✅（静态+组件级） | a7 16/16：按钮仍渲染 + `disabled` + 文案含「工时评估」+ 点击零请求；`RequirementDetailView:159` 权限文案与门文案分函数；通过后同位置可用（dev CDP 30/30，夹具驱动真实组件）。**真实浏览器人眼/读屏未覆盖**（见 §4） |
| AC-14 审批页签三态 | ✅ | 加载/失败/空三态中文，与提报单并列只读块（QA 静态 + 真栈详情） |
| AC-15 演示夹具 | ✅ | reset 后 11 张 APPROVED / 0 在途 / 11 条需求各一张；走引擎 `start+approve` 路径（a7 R1–R3 真栈核对） |
| AC-16 门禁夹具与负向 | ✅ | platform-verify 192（≥174）、negative 111（≥96）；4c 段六态各拦 + 越权 403；两脚本幂等 |
| AC-17 门禁不缩水 | ✅ | rbac 113 / access 206 / verify 192 / negative 111 / demo-reset 69 / unit 367 / lint 22 / typecheck+build+compile 绿；**本验收补跑 analytics 74/74、collab 257/257** |
| AC-18 改动面与文档 | ⚠️ 部分 | 改动面与 `pom.xml`/`package.json` diff 空 ✓；memory 含「渲染看权限、禁用看门」✓；**`evidence/` ≥3 张截图缺失** ✗；memory r26 实测基线仍写 178/108/205/15（实际 192/111/206/16）✗ |

> 说明：注入的 AC 索引含 r26 之外的 AC-19~AC-22（历史需求的审计/门禁/权限矩阵条目），本需求 PRD 只定义 AC-1~AC-18；历史条目的不回归由 AC-17 的 rbac/analytics/collab 门禁覆盖（均已绿）。

## 3. 架构一致性核查（M3 质量门）

| 蓝图主张 | 落地事实 | 判定 |
|---|---|---|
| 零 schema 变更，复用 `wf_instance/wf_task/wf_history`，单据数据只存 SUBMIT 快照 | 无 `.sql` 改动；`EstimateSnapshot` 解析 `detail_json.document` | ✅ 符合 |
| `BIZ_WBS_ESTIMATE` / `STATUS_SUPERSEDED` 各唯一一处声明 | `WorkflowVersion`、`ApprovalInstance` 单点（QA 接口 54/54） | ✅ 符合 |
| 门唯一插点 `RequirementService.transition`，不落库不写审计 | 428–437 行单点，位于 403 与 r23 门之后 | ✅ 符合 |
| 六态与门同源 | `WbsEstimateService.statusOf` 唯一派生，`ensureGate/applyGate/view` 全复用它 | ✅ 符合 |
| `DocumentSummaryProvider` 实例重载不破既有四实现 | default 方法 + 改 `ApprovalCenterService` 调用点 | ✅ 符合 |
| 前端门文案单一来源 `views/req/estimate.ts` | `estimateGateTip` 被卡片/行内/title 三处消费；模板无拼串 | ✅ 符合 |
| 种子：`seedEstimateWorkflow` / `backfillDomainOwners` 命名方法；重置：`ResetResult resetBusiness()` | 内联在 `SeedService:262-270`；`DemoResetService` 返回 `Map<String,Long>`（全仓无 `ResetResult`） | ⚠️ 偏离（P3）：仅私有辅助方法未按蓝图抽取，跨模块接口与编排行为一致，无重复实现 |

未发现重复实现、适配层漂移或既有结构破损；`allowedActions` 仍只在 `RequirementService` 一处过滤（toView/detail/TransitionResult 三出口调用同一 `applyGate`）。

## 4. 条件与遗留

**放行条件（建议 re-accept 前完成，均为 P3 级、不影响功能）**

| 编号 | 内容 | 关联 | 责任 |
|---|---|---|---|
| C1 | 补 `evidence/` ≥3 张截图：未评估时按钮禁用 + 原因、审批中心待办「工时评估」、驳回原因在动态里；并补 409 实测输出与 reset 计数 | AC-18 | QA/dev（需可启动浏览器环境） |
| C2 | 订正 `docs/teamflow/memory.md:36` r26 实测基线为 **verify 192 / negative 111 / access 206 / a7 16 / demo-reset 69**，并登记 analytics 74、collab 257 | AC-18 | 文档维护 |

**建议（P3，不阻断）**

| 编号 | 内容 | 说明 |
|---|---|---|
| C3 | 抽取蓝图命名的 `seedEstimateWorkflow` / `backfillDomainOwners` / `resetBusiness()`（或返回 `ResetResult`） | 仅命名/封装对齐，行为与接口不变；可与下次 seed 改动合并 |
| N-02 | 新单通过取代旧 APPROVED 时 `wf_history` 复用 `INSTANCE_WITHDRAWN` 动作 | 评论已写明「已被新的评估单取代」，属语义标注；建议后续加 `SUPERSEDED` 动作 |
| — | 人眼补测：真实浏览器交互（禁用按钮 tab 序、title/行内逐字、通过后可用）、960/1440 视觉走查、双会话并发或签 | 沙箱 Chrome 不可用；dev 侧 CDP 30/30 已覆盖行为面 |

## 5. 结论

- 主链真栈跑到终态：提交（201）→ 审批中心待办/或签通过 → 人天回写 → `schedule`/`start_dev` 放行 → 改人天超 ±20% 读时派生 STALE 重拦；被拦不落库、不写 `STATE_CHANGE`；判定顺序与 r23 既有门不回归。
- 门禁只增不减：rbac 113 / access 206 / verify 192 / negative 111 / demo-reset 69 / unit 367 / lint 22 / analytics 74 / collab 257 / typecheck+build+compile 绿；`pom.xml` 与 `web/package.json` diff 为空。
- 功能与架构接口面均符合蓝图，唯一偏离是种子/重置的私有方法命名（P3）；**AC-18 的交付证据（截图）与 memory 基线订正未完成**，故不签 ✅，按 ⚠️ 有条件通过处理，完成 C1/C2 后可直接复验签收（无需重跑功能门禁）。

验收结论：⚠️ 有条件通过
