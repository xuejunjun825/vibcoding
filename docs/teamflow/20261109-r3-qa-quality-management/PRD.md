<!-- meta: summary="M6 质量管理补全：以测试计划完整生命周期、轮次与结构化准出为执行底座，以版本化可会签的测试报告为结论载体，以规则级可豁免的版本质量门为发布硬门禁，并补齐策略/分配/环境/Go-No-Go/生产缺陷回流与五项质量指标。" -->
# M6 质量管理补全 —— 测试计划闭环 · 测试报告 · 版本质量门

基线依赖：docs/teamflow/20261108-r2-devops-req-platform/（M1–M5 已验收实现；其测试用例库、计划执行、发布准入 5 项、缺陷/指标/权限/隔离契约不得回归）

取代：docs/teamflow/20261108-r2-devops-req-platform/#AC-12：发布准入由 5 项扩为 6 项（新增「质量门通过」），原「强制放行」退役，降级为规则级豁免 + PMO/管理员审批。

取代：docs/teamflow/20261108-r2-devops-req-platform/#AC-16：测试计划状态由「草稿/执行中/完成」三态扩展为「草稿→待评审→已批准→执行中→已完成→关闭/归档」完整生命周期，计划完成判定改为结构化准出条件 + 轮次汇总。

取代：docs/teamflow/20261108-r2-devops-req-platform/#AC-3：需求「测试通过」门禁的通过率与用例判定改由版本质量门统一取数（计划准出、报告、质量门三处同源），严格/宽松模式语义不变。

> 需求输入：docs/teamflow/20261108-r2-devops-platform/QUALITY-MANAGEMENT-GAP.md（12 条缺口与优先级）；视觉基线：同目录 UI-STYLE.md 与 prototype/。

---

## 1. 背景与目标

### 1.1 业务背景

M1–M5 已交付「用例库 → 计划 → 执行 → 失败转缺陷 → 发布准入 5 项」的最小闭环，但质量管理的**作业流程**仍是断的：计划可直接编辑、无评审无变更留痕；「完成」只等于「没有未执行」；缺陷修完无法开第二轮并保留首轮结论；版本发布没有一份可签、可回看的测试报告；准入里的「测试通过」是需求状态而不是可核验的结论，PMO 只有一个「强制放行」大按钮。

用户要求三件事：**测试计划要完整管理**、**梳理并补齐质量管理缺失的作业流程**、**版本发布要有测试报告的质量门**。GAP 分析给出 12 条缺口，本轮交付其中 P0 五条（评审与变更、准出条件、轮次、测试报告、质量门+豁免）与 P1 五条（测试策略、执行分配与进度、环境台账、Go/No-Go 评审、生产缺陷回流与观察窗）。

### 1.2 本轮目标（M6）

1. **计划是受控对象**：完整生命周期、评审、变更日志、未批准不可被发布单引用。
2. **完成是可判定的**：准出条件结构化，服务端唯一判定，不满足 409 并列出未满足项。
3. **执行是可追溯的**：R1 新功能 / R2 回归 / R3 缺陷验证，每轮用例集与结论独立留存，历史不可覆盖。
4. **结论是可签的**：测试报告自动生成、版本化、可导出（XLSX/PDF 带水印）、三级会签，未签报告不能进门禁。
5. **门禁是硬的**：发布单第 6 项实时计算 + 固化快照，失败列规则与修复指引，绕过前端同样拒绝；豁免按规则级、有原因/影响/有效期/审批。
6. **质量是可度量的**：生产缺陷回流与 72h 观察窗，FPY、回归通过率、逃逸率、发布后 72h 缺陷数、回滚率进指标字典，与看板/报告/门禁同源。

### 1.3 成功判据

- 任一历史发布单，能回看**当时的**规则值、报告版本、缺陷清单与快照哈希；用快照引用的明细可复算出同样的通过率与缺陷数。
- 同一筛选下，计划准出、测试报告、质量门、看板四处数字一致（整数完全相等，比率保留两位小数一致）。
- 任何「界面置灰」的拒绝，直接调接口得到同样的错误码与失败项。
- r1 四步与 r2 的 `platform-verify / platform-test-negative / platform-perf / platform-unit / lint` 全绿，无回归。

### 1.4 名词与口径（全篇统一）

| 名词 | 定义 |
| --- | --- |
| 测试策略 | 版本级质量策划：必测需求范围、风险点、环境清单、轮次计划、资源说明；是计划的父级 |
| 测试计划 | 某 Release 下的一次受控测试活动，含范围（`case_version` 快照）、时间窗、准出条件、执行分配 |
| 轮次 Round | 计划内的一次执行批次：R1 新功能 / R2 回归 / R3 缺陷验证；用例集与结论独立冻结 |
| 准出条件 | 通过率阈值、未关闭 P0/P1 数、必需用例集通过率三类结构化条件；服务端唯一判定 |
| 测试报告 | 由计划/轮次数据自动生成的结论文件；版本化，结论 PASS / CONDITIONAL / FAIL，三级会签后生效 |
| 质量门 | 绑定 Release 的规则集；对发布单提交时计算，结果 PASS / PASS_WITH_WAIVER / FAIL，并固化快照 |
| 豁免 Waiver | 针对**单条规则**的限时放行，必须填原因/影响范围/有效期，经 PMO 或平台管理员审批 |
| 观察窗 | 发布执行成功后的一段缺陷观察期，默认 72h；窗内关联该 Release 的缺陷标记「逃逸缺陷」 |
| FPY | 一次通过率：R1 轮 PASS 用例数 / R1 轮已执行用例数 |
| 报告有效期 | 自最后一位会签人签署时刻起算，默认 7 天（按 Release 可配 1–30 天） |

---

## 2. 用户与角色

| 角色 | 本轮新增职责 |
| --- | --- |
| 测试工程师 | 维护策略/计划草稿、执行本人分配用例、开轮次、提交计划评审、生成报告 |
| 测试负责人（测试角色中的计划 owner / 测试经理，见 §15 T1） | 批准计划与变更、分配执行任务、签署报告第一签、发起 Go/No-Go |
| 产品 | 确认测试范围与报告第二签；对 CONDITIONAL 结论表态 |
| PMO（管理层） | 报告第三签、豁免审批、Go/No-Go 主持、质量指标 owner |
| 域负责人 | 查看本域质量门与报告、接收门禁失败/豁免通知 |
| 研发 | 只读本厂商计划/报告/门禁结论；「测试通过」仍由服务端门禁判定 |
| 运维 | 环境台账维护、发布单执行、Go/No-Go 参与、生产缺陷提报 |
| 平台管理员 | 环境/规则阈值兜底配置、豁免审批（与 PMO 同级）、全租户聚合 |
| 业务方 | 不新增可见面；缺陷摘要视图口径不变 |

---

## 3. 业务流程与状态机

### 3.1 端到端链路

```
Release ─┬─ 测试策略（范围/风险/环境/轮次计划）
         ├─ 测试计划（草稿→待评审→已批准→执行中→已完成→关闭/归档）
         │    ├─ 轮次 R1/R2/R3 ── 执行记录（case_version + round_id + env_id）── 缺陷
         │    ├─ 执行分配（执行人×模块×用例数）── 工作台「我的待执行用例」
         │    └─ 变更日志（原因+审批人）── plan.revision+1 ── 旧报告失效
         ├─ 测试报告（PLAN / RELEASE 汇总）── v1..vN ── 会签（测试负责人→产品→PMO）
         └─ 质量门（规则集）── 评估快照 ── 发布单第 6 项准入
                                  └─ 规则级豁免（原因/影响/有效期/审批）
发布执行成功 ── 观察窗 72h ── PROD 缺陷（逃逸标记）── 版本质量统计/指标
```

### 3.2 测试计划生命周期

| 当前 | 事件 | 目标 | 前置/副作用 |
| --- | --- | --- | --- |
| — | 创建 | 草稿 DRAFT | 必须挂 Release、策略、owner、时间窗、≥1 用例、准出条件 |
| 草稿 | 提交评审 | 待评审 PENDING_REVIEW | 校验必填完整；写审计与通知 |
| 待评审 | 批准 | 已批准 APPROVED | 仅测试负责人/PMO；记录 `approved_by/at`；计划范围冻结为 revision 基线 |
| 待评审 | 驳回 | 草稿 | 必须填意见；通知 owner |
| 已批准 | 首次执行/开始 | 执行中 IN_PROGRESS | 开启首轮 R1 |
| 执行中 | 完成 | 已完成 COMPLETED | **服务端准出判定**；不满足 409 且不改状态 |
| 已完成 | 开新轮次 | 执行中 | 缺陷修复后再测的合法入口 |
| 执行中/已完成 | 关闭 | 已关闭 CLOSED | 必须填原因；执行中关闭=取消，结论 CANCELLED |
| 已关闭 | 归档 | 已归档 ARCHIVED | 只读；任何写操作 409 |

- 非法流转（如草稿直接完成、待评审直接关闭）→ 409 `ILLEGAL_PLAN_TRANSITION`，返回 `allowedNext`。
- 归档后计划不能被新报告/新轮次引用；历史报告仍可查。
- 计划状态不因缺陷重开而自动回退；回退由新轮次表达。

### 3.3 轮次

- 同一计划**同一时刻只允许一个进行中轮次**；新轮次创建时上一轮必须已关闭（否则 409 `ROUND_IN_PROGRESS`）。
- 轮次关闭前置：该轮用例集全部有结果（未执行须显式标记 SKIP 并填原因），否则 409 `ROUND_HAS_UNEXECUTED`。
- 关闭即冻结：该轮的用例集、结果、执行人、起止、结论只读；任何补录/修改 409 `ROUND_CLOSED`。
- 轮次结论：PASS / FAIL / CONDITIONAL / CANCELLED，由该轮结果计算，关闭人可下调不可上调。
- 「从上一轮失败/阻塞用例建轮」为一等操作：新轮用例集 = 指定轮次的 FAIL ∪ BLOCKED 集合（含 `case_version`）。

### 3.4 报告与会签

| 状态 | 含义 | 可执行动作 |
| --- | --- | --- |
| SIGNING 会签中 | 某版本已生成，等待三级签署 | 签署/驳回；重新生成新版本 |
| SIGNED 已会签 | 三级全部同意，生效并起算有效期 | 被门禁引用；导出 |
| REJECTED 已驳回 | 任一签署人驳回 | 只读；必须生成新版本 |
| SUPERSEDED 已被取代 | 新版本完成会签后，旧版本自动转此态 | 只读留档 |

- 会签顺序固定：测试负责人 → 产品 → PMO；越序 409 `SIGNOFF_ORDER_VIOLATION`。
- 同一人重复签署幂等（返回同一签署记录，不新增行）。
- 驳回即该版本作废（REJECTED），修订后生成 v(N+1) 重走全部三签。
- 新版本生成后、完成会签前，**上一已签版本仍有效**；新版本签完后旧版本转 SUPERSEDED，门禁自动取最新 SIGNED 版本。

### 3.5 质量门判定

1. 发布单创建/编辑阶段可预览（不固化）；**提交时**实时计算并固化 `quality_gate_snapshot`。
2. 规则逐条求值：PASS / FAIL / WAIVED / SKIPPED（预留项未启用）。
3. 门禁结果：全 PASS → `PASS`；≥1 WAIVED 且其余 PASS → `PASS_WITH_WAIVER`；任一 FAIL → `FAIL`。
4. 准入第 6 项接受 `PASS` / `PASS_WITH_WAIVER`；`FAIL` → 409 `QUALITY_GATE_FAILED`，返回 `failedRules[]`（规则码、阈值、实际值、证据引用、修复指引）。
5. 同一发布单重复提交：已有有效快照则复用并返回同一 `snapshotId`（幂等）；数据变化导致重算时生成新快照，旧快照保留。
6. 质量门算法**唯一实现**在 deliver-service，req-service 经 `GatePort` 调用；req 侧不得再写一套判定 SQL。

### 3.6 豁免

申请（测试负责人/产品/运维）→ 审批（PMO 或平台管理员）→ 生效（该规则 WAIVED，其余规则照常求值）→ 到期自动失效（不删除记录）→ 可撤销（写审计）。

- 一条豁免只覆盖一条规则、一个 Release/门禁；禁止「整门豁免」。
- P0/P1 阻断规则**不可豁免**（硬红线）；其余规则豁免有效期 1–30 天。
- 豁免不改变报告结论；门禁结果体现为 `PASS_WITH_WAIVER` 并附豁免清单。

### 3.7 生产缺陷回流与观察窗

- 发布单执行结果为「成功」时，以 `ended_at` 为起点开启观察窗（Release 级 `observation_hours`，默认 72，可配 24–168）。
- 窗内创建且关联该 Release 的缺陷 → `is_escape=true`，并记录 `escape_window_end`；窗外为 false。判定在创建时一次写入，后续改窗不回溯。
- 逃逸缺陷回写版本质量统计（逃逸数、72h 缺陷数、按严重级分布），并进入质量指标；不修改历史门禁快照。

### 3.8 关键页面与交互摘要

| 页面 | 路由（新增/改） | 关键交互 |
| --- | --- | --- |
| 版本测试策略 | `/test-strategies?releaseId=` | 范围勾选（Release 需求）、风险清单、环境多选、轮次计划；保存即新版本 |
| 测试计划详情 | `/test-plans/:id` | 顶部状态条 + 下一步按钮；Tab：概览/用例/轮次/分配/变更日志/报告 |
| 计划执行台 | `/test-plans/:id/execute`（改造） | 按轮次执行；结果 pill；失败行「转缺陷」；批量通过/跳过；环境选择 |
| 测试报告 | `/test-reports/:id` | 8 段式报告、版本切换、会签进度、导出 XLSX/PDF |
| 质量门 | `/quality-gates/:releaseId` | 规则卡（PASS/FAIL/WAIVED + 证据）、豁免申请、快照历史 |
| 发布单详情 | `/deploy-orders/:id`（改造） | 准入 6 项，第 6 项可展开规则明细；豁免清单；Go/No-Go 结论条 |
| Go/No-Go | `/releases/:id/go-no-go` | 门禁 + 报告 + 遗留缺陷 + 风险一屏；一键生成评审记录并导出 |
| 环境台账 | `/test-envs` | 列表 + 新建/维护/停用；引用检查 |
| 质量指标 | `/quality-metrics` | 5 个新指标卡 + 切片（域/厂商/版本/月）+ 下钻明细 |
| 工作台 | `/workbench`（改造） | 卡片「我的待执行用例」：按计划/模块/截止日，超期标红 |

---

## 4. 功能需求

### 4.1 测试策略（版本级质量策划）

- 每个 Release 可有多版本策略，`version_no` 递增；同一时刻仅最新版本可被计划引用。
- 字段：必测需求范围（Release 需求子集，至少 1 条）、风险清单（描述/等级/应对）、环境清单（引用 `test_env`）、轮次计划（拟开轮次与目标）、资源说明（人/时长，文本）、owner。
- 与计划的关系：**新建计划必须挂同 Release 的最新策略**；无策略 400 `STRATEGY_REQUIRED`。存量计划由迁移补建默认策略（范围=该 Release 全部需求，风险为空，环境为空），并在计划详情标注「迁移补建」。
- 策略不设审批（P2 的用例评审仍默认关）；变更写审计，不写 plan_change_log。

### 4.2 测试计划的创建、评审与变更

- 创建必填：Release、策略、名称、owner、时间窗（起≤止）、用例集（≥1，落 `case_version`）、准出条件、默认环境。
- 提交评审校验缺失 → 400 `PLAN_INCOMPLETE`，`missingFields[]`。
- 批准权限：测试负责人（该计划 owner 且具测试角色）或 PMO；其他人 403 `PLAN_APPROVE_FORBIDDEN`。
- **未批准计划不得被发布单引用**：发布单创建/编辑时带入需求 → 若需求对应计划未批准，409 `PLAN_NOT_APPROVED` 并列出计划；不落发布单。
- 变更控制（已批准及以后状态）：
  - 允许变更：用例增删（含版本）、时间窗、准出条件、owner、默认环境。
  - 必须填 `reason`（≥10 字）与 `approver`（测试负责人/PMO）；缺失 400 `CHANGE_REASON_REQUIRED`。
  - 每次变更写一条 `plan_change_log`（类型、变更前后 JSON、原因、审批人、操作人、时间），`plan.revision+1`。
  - 变更立即生效；旧报告因 `plan_revision` 不一致在门禁中判 FAIL（`QG-02 REPORT_STALE`），需重新出具。
- 关闭/归档：关闭需 `close_reason`；执行中关闭=取消（结论 CANCELLED），必须确认已通知相关人；归档后只读。
- 每次状态变更写 `audit_log` 并发站内通知（owner、审批人、域负责人）。

### 4.3 准出条件（Exit Criteria）

结构化 JSON（计划级可配，默认如下）：

```json
{
  "passRate":        {"enabled": true, "threshold": 95.0},
  "blockingDefects": {"enabled": true, "severities": ["P0","P1"], "maxOpen": 0},
  "requiredCases":   {"enabled": false, "caseIds": [], "requiredPassRate": 100}
}
```

- 判定口径（服务端唯一实现，`ExitCriteriaEvaluator`，计划完成/报告生成/质量门共用）：
  - 计划通过率 = 计划范围内每个用例在 `[计划创建, 判定时刻]` 的**最新一条**执行结果（跨轮次）为 PASS 的数量 / 计划范围用例总数。
  - 执行覆盖率 = 有执行记录的用例数 / 计划范围用例总数。
  - 必需用例通过率 = 必需用例集中最新结果为 PASS 的数量 / 必需用例数。
  - 未关闭 P0/P1 = 关联该 Release 且关联需求在本计划范围内、`status≠关闭` 的缺陷数。
- `POST /test-plans/{id}/complete`：不满足 → 409 `PLAN_EXIT_CRITERIA_UNMET`，`unmetItems[]` 每项含 `code`（`PASS_RATE_BELOW` / `OPEN_BLOCKING_DEFECTS` / `REQUIRED_CASES_NOT_PASSED`）、`required`、`actual`、`detail`（缺陷编号或用例编号）。状态不变。
- 通用 `PATCH /test-plans/{id}` 直接置 COMPLETED 走同一判定，返回同一 409，不允许旁路。
- 严格模式（Release 级 `gate_mode=STRICT`）：通过率阈值强制 100%，且所有非通过结果均阻断。

### 4.4 测试轮次（Round）

- `POST /test-plans/{id}/rounds`：类型 SMOKE / FEATURE / REGRESSION / DEFECT_VERIFY；轮次号 `round_no` 按计划自增；用例集默认沿用计划范围（记录 `case_version`），可显式传入或「取上一轮 FAIL∪BLOCKED」。
- 每轮记录：用例集、执行人集合、起止时间、结论、关闭人/时间、备注；可关联缺陷编号（验证轮）。
- 执行记录必须带 `round_id`；缺省时服务端取当前进行中轮次，无进行中轮次 → 409 `NO_ACTIVE_ROUND`。
- 计划汇总提供两种视图：**分轮次**（每轮通过率/覆盖率/结论）与**累计**（每用例最新结果）。两者都保留，历史轮次永不覆盖。
- 轮次关闭后新执行只能进新轮次；不允许在旧轮次补录。

### 4.5 执行任务分配与进度

- `plan_assignment`：计划（或轮次）× 执行人 × 模块 × 计划用例数 × 截止日。
- 合计校验：按计划分配时，所有分配数之和必须等于计划用例总数；按轮次分配时等于该轮用例数。不等 → 400 `ASSIGNMENT_MISMATCH`，返回 `diff` 与未分配用例数。
- 进度：按人/模块统计「已执行/通过/失败/阻塞/未执行」；计划详情与工作台同源。
- 超期：截止日 < 今天且仍有未执行 → 工作台标红，并每天 09:00 发一次站内提醒（同日同人幂等，不重复轰炸）。
- 工作台「我的待执行用例」：作为执行人看到的分配用例 + 我负责计划中未分配的用例（仅 owner 可见）；分页 ≤1s。

### 4.6 测试环境台账

- `test_env`：名称、类型 DEV/TEST/STAGING/PROD、地址、负责人、状态 AVAILABLE / MAINTENANCE / DISABLED、所属域、备注。
- 唯一性：同租户同域内名称唯一；停用不影响历史引用。
- 计划绑定默认环境；执行记录 `env_id` 必填（缺省继承计划），允许临时切换（报告按环境分组）。
- 删除：被计划或执行记录引用 → 409 `ENV_IN_USE`，只能停用。
- 隔离：厂商只看到自己可用域内的环境；跨域查询 403 `SCOPE_DENIED`。

### 4.7 测试报告

**生成**

- 范围：`scope=PLAN`（单计划）或 `scope=RELEASE`（版本汇总，门禁默认用此范围）。
- 输入：计划范围与 `case_version` 快照、轮次（默认全部已关闭轮次 + 当前轮次截至 `data_cutoff`）、执行记录、缺陷、环境。
- 前置：RELEASE 范围内至少 1 个已批准计划，否则 409 `REPORT_NO_PLAN`；存在未批准计划时报告列出「未纳入计划」，门禁 QG-01 会因此失败。
- 8 个固定段落：① 范围与版本快照 ② 用例执行分布（结果×模块×执行人）③ 通过率/覆盖率 ④ 缺陷统计与趋势（按日、严重级、来源）⑤ 遗留缺陷与豁免清单 ⑥ 环境与配置 ⑦ 风险与结论（PASS/CONDITIONAL/FAIL）⑧ 会签页。
- 结论规则：
  - FAIL：未关闭 P0/P1 > 0，或必需用例未 100% 通过。
  - CONDITIONAL：P0/P1 = 0，但准出条件中其余项未满足且有遗留缺陷/豁免清单；必须填写风险、缓解措施与遗留清单，且 PMO 签署时确认。
  - PASS：准出条件全部满足。
  - 会签人只能把建议结论下调（PASS→CONDITIONAL→FAIL），不能上调；上调尝试 409 `CONCLUSION_UPGRADE_FORBIDDEN`。
- 数字口径与 §4.3 完全一致；报告存储明细 id 清单（用例 id、执行 id、缺陷 id），不是只有汇总数。

**版本与失效**

- 每次生成产生 `test_report_version`（v1、v2…），存完整快照 JSON、`result_digest`、结论、通过率、覆盖率、生成人/时间、内容哈希。
- `result_digest` = 按 `case_id` 升序拼接「case_id:case_version:result」的 SHA-256；门禁用同一算法对当前执行结果求值，不一致即报告过期。
- 新版本会签完成后旧版本 SUPERSEDED；旧版本仍可查、可导出，数字不随新数据变化。
- 报告有效期：`signed_at + validity_days`（Release 级，默认 7，可配 1–30）；到期门禁判 `REPORT_EXPIRED`。

**导出**

- `GET /test-reports/{id}/export?format=xlsx|pdf&version=N`，复用既有导出与水印能力。
- 水印：`内部资料 · <租户> · <报告编号> · v<版本> · <生成时间>`，PDF 每页可见，XLSX 表头与页脚各一处。
- 导出按调用者数据权限过滤；越权导出 403。

**会签**

- `report_signoff`：角色（TEST_LEAD/PRODUCT/PMO）、签署人、决定（APPROVE/REJECT）、意见、时间、顺序号。
- 顺序固定，越序 409；驳回 → 整版本 REJECTED，重出版本重走三签。
- GET 报告详情始终返回签署进度与当前有效版本；未签完不得被门禁引用（409 `REPORT_NOT_SIGNED`）。

### 4.8 版本发布质量门

- 质量门按 Release 建立（一个 Release 一个门禁配置，`quality_gate`），规则可配阈值；发布单提交时求值并固化快照。
- 规则集（默认全启用，除预留项）：

| 代码 | 规则 | 默认阈值 | 可豁免 |
| --- | --- | --- | --- |
| QG-01 | 发布单需求清单中每条需求都有**已批准计划**覆盖，且该 Release 存在已会签的 RELEASE 范围报告；未覆盖需求列编号 | 覆盖率 100% | 否（缺计划=没测） |
| QG-02 | 报告有效：已会签、结论满足（PASS；CONDITIONAL 需豁免清单+PMO 审批）、未过有效期、`plan_revision` 与 `result_digest` 与当前一致 | 有效期 7 天 | 否（结论类） |
| QG-03 | 报告加权通过率 ≥ 阈值（严格模式 100%） | 95% | 是（需 PMO） |
| QG-04 | 未关闭 P0/P1 缺陷 = 0 | 0 | **否（硬红线）** |
| QG-05 | 集成门禁：Sonar 质量门 / 制品校验 / 最近构建成功 | 本轮预留，`enabled=false`，状态 SKIPPED | — |

- 加权通过率 = ΣPASS / Σ计划用例数（跨计划按用例数加权，不做计划平均），与报告 `passRate` 同一算法。
- 快照（`quality_gate_snapshot`，不可变）至少含：门禁结果、逐规则 `code/status/required/actual/evidence`、报告 `id+version+conclusion+signed_at+result_digest`、未关闭 P0/P1 编号清单、豁免 `id+规则+审批人+到期`、`computedAt`、`hash`。
- 发布单准入变为 6 项：原 5 项语义不变，第 6 项「质量门通过」；任一 FAIL → 409，`failedRules[]` 按规则给出 `fixHint`（如「补一份会签通过的版本报告」「关闭 BUG-0042/BUG-0051」「为 QG-03 申请豁免并经 PMO 审批」）。
- 绕过前端：`POST /deploy-orders/{id}/submit`、直接 `PATCH` 状态、直连服务端口，均走同一判定并返回同一 409。
- 历史发布单（r2 时期无质量门）详情显示「质量门未启用（历史单据）」，不伪造 PASS，不改写 `admission_result_json`。

### 4.9 门禁豁免（Waiver）

- 申请字段：规则码、原因（≥10 字）、影响范围（受影响需求/用例/缺陷清单）、有效期（1–30 天）、申请人。
- 审批：PMO 或平台管理员；申请人不得自批（除非本身是 PMO/管理员，仍需二次确认并审计）。
- 生效：该规则转 WAIVED（快照中记录豁免 id、审批人、到期时间）；其余规则仍独立求值。
- 到期：自动失效，重新求值该规则为 FAIL；不删除记录，快照仍可回看。
- 撤销：审批人可撤销，写审计并通知；已发出的快照不受影响（历史冻结）。
- 原「强制放行」：`POST /deploy-orders/{id}/force-release` 保留路由但返回 409 `FORCE_RELEASE_RETIRED`，提示走豁免流程并写审计；UI 入口移除；历史放行记录仍展示。
- 禁止：一条豁免覆盖多条规则（400 `WAIVER_RULE_SINGLE_ONLY`）、豁免 QG-04（400 `RULE_NOT_WAIVABLE`）、无有效期或有效期 >30 天（400 `WAIVER_EXPIRY_INVALID`）。

### 4.10 发布 Go/No-Go 评审

- 视图：Release/发布单维度，一屏呈现质量门结果（逐规则）、报告摘要与结论、未关闭 P0/P1 与遗留缺陷、策略中的风险清单、观察窗设置。
- 一键生成评审记录（`release_review`）：会议时间、参与人、门禁快照 id、报告 id/version、遗留缺陷清单、风险与缓解、结论 GO / NO-GO / CONDITIONAL、附加条件、创建人；记录不可变，可导出 PDF。
- 默认治理语义：评审记录**不单独硬阻断**提交（质量门是唯一硬门禁）；结论为 NO-GO 时发布单页顶部红色警示，提交需 PMO 二次确认并留痕；CONDITIONAL 必须填写附加条件。
- 门禁尚未求值时可生成「预评审」记录，但必须标注「门禁未评估」，不得作为 GO 依据。

### 4.11 生产缺陷回流与观察窗

- `defect.source` 枚举扩为 `TEST` / `PROD` / `MANUAL`；`PROD` 必须关联 Release（400 `PROD_DEFECT_RELEASE_REQUIRED`）。
- `defect` 新增 `root_cause`（需求/设计/编码/环境/数据/其他）与 `found_phase`（开发自测/提测/回归/发布/生产）；生产缺陷建议必填，不强制。
- 观察窗：Release `observation_hours` 默认 72（24–168）；发布单执行成功时记录窗口起点；窗内创建且关联该 Release 的缺陷 `is_escape=true`，并写 `escape_window_end`。
- 版本质量统计 `GET /releases/{id}/quality`：门禁历史、报告版本、未关闭 P0/P1、逃逸缺陷数、发布后 72h 缺陷数、回滚次数；数字与缺陷/发布单明细接口用同一筛选。
- 逃逸缺陷不吊销已发出的门禁快照与已通过的发布单，但同一 Release 的**后续**发布单必须重新出具报告并重新求值（`result_digest` 会变化，QG-02 失败并提示）。

### 4.12 指标字典增量

| 编码 | 指标 | 公式 | 切片 |
| --- | --- | --- | --- |
| FPY | 一次通过率 | R1 轮 PASS 用例数 / R1 轮已执行用例数（分母为 0 时返回 null，不显示 0%） | 版本、计划、域、厂商、月 |
| REGRESSION_PASS_RATE | 回归通过率 | R2 及以后轮次 PASS 数 / 同范围已执行数 | 版本、计划、域、厂商、月 |
| DEFECT_ESCAPE_RATE | 缺陷逃逸率 | 逃逸缺陷数 / 该版本关联缺陷总数 | 版本、域、厂商、月 |
| POST_RELEASE_DEFECTS_72H | 发布后 72h 缺陷数 | 窗口内关联该 Release 的缺陷数（窗口长度取 Release 配置） | 版本、域、厂商、环境 |
| ROLLBACK_RATE | 回滚率 | 结果=回滚 的发布单数 / 已执行发布单数 | 版本、域、环境、月 |

- 5 条指标写入 `metric_definition`（新版本记录，旧版本可查）；每条含编码、名称、主题、公式、数据源表、口径说明、owner、版本、生效日期。
- 看板、报告、质量门、版本质量页调用**同一聚合实现**；同一筛选下整数相等、比率两位小数一致；指标卡可下钻到明细。
- 禁止在报告或门禁里另写一套聚合 SQL；口径变更必须新增版本，不得原地改公式。

### 4.13 通知与审计增量

- 通知：计划提交/批准/驳回、变更批准、分配与超期、报告待签/签署完成/驳回、门禁失败、豁免申请/审批/到期、Go/No-Go 生成、逃逸缺陷、发布后 72h 汇总。
- 审计：计划状态流转、变更、轮次开关、分配调整、环境增删改、报告生成/签署/驳回/导出、门禁求值、豁免全流程、Go/No-Go 生成、逃逸标记、指标版本变更。
- 审计可按单据反查，含操作人、IP、前后值摘要；导出操作单独记一条。

### 4.14 接口增量清单（前缀经网关 `/api`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST/GET/PUT | `/test-strategies`、`/test-strategies/{id}` | 策略版本维护 |
| POST | `/test-plans/{id}/submit-review`、`/approve`、`/reject`、`/start`、`/complete`、`/close`、`/archive` | 生命周期动作 |
| POST/GET | `/test-plans/{id}/changes`、`/change-logs` | 变更与日志 |
| POST/GET | `/test-plans/{id}/rounds`、`/rounds/{roundId}/close` | 轮次 |
| POST/GET | `/test-plans/{id}/assignments`、`/progress` | 分配与进度 |
| GET | `/workbench/my-cases` | 我的待执行用例 |
| CRUD | `/test-envs`、`/test-envs/{id}` | 环境台账 |
| POST | `/test-reports`、`/test-reports/{id}/versions` | 生成/重出版本 |
| POST | `/test-reports/{id}/signoff` | 会签（APPROVE/REJECT） |
| GET | `/test-reports/{id}`、`/test-reports/{id}/export` | 详情与导出 |
| GET/PUT | `/releases/{id}/quality-gate` | 门禁配置与查看 |
| GET | `/deploy-orders/{id}/admission` | 准入预览（6 项） |
| POST | `/quality-gates/{id}/evaluate` | 预评估（不固化） |
| POST/GET | `/quality-gates/{id}/waivers`、`/waivers/{id}/approve|reject|revoke` | 豁免 |
| POST/GET | `/releases/{id}/reviews` | Go/No-Go 记录 |
| GET | `/releases/{id}/quality` | 版本质量统计 |
| GET | `/metrics/quality` | 质量指标聚合（统一下钻） |

### 4.15 错误码约定

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | `PLAN_INCOMPLETE` / `CHANGE_REASON_REQUIRED` / `ASSIGNMENT_MISMATCH` | 必填缺失 / 变更无原因 / 分配数不等 |
| 400 | `WAIVER_FIELDS_REQUIRED` / `WAIVER_EXPIRY_INVALID` / `WAIVER_RULE_SINGLE_ONLY` / `RULE_NOT_WAIVABLE` | 豁免字段/期限/粒度/红线 |
| 400 | `PROD_DEFECT_RELEASE_REQUIRED` | 生产缺陷缺版本 |
| 403 | `PLAN_APPROVE_FORBIDDEN` / `SCOPE_DENIED` | 越权批准 / 跨域访问 |
| 409 | `ILLEGAL_PLAN_TRANSITION` / `PLAN_NOT_APPROVED` / `PLAN_EXIT_CRITERIA_UNMET` | 非法流转 / 未批准被引用 / 准出未满足 |
| 409 | `ROUND_IN_PROGRESS` / `ROUND_HAS_UNEXECUTED` / `ROUND_CLOSED` / `NO_ACTIVE_ROUND` | 轮次冲突 |
| 409 | `REPORT_NO_PLAN` / `REPORT_NOT_SIGNED` / `SIGNOFF_ORDER_VIOLATION` / `CONCLUSION_UPGRADE_FORBIDDEN` | 报告前置/会签/结论 |
| 409 | `QUALITY_GATE_FAILED` / `FORCE_RELEASE_RETIRED` | 门禁失败 / 强制放行退役 |
| 409 | `ENV_IN_USE` | 环境被引用 |
| 422 | 未知枚举/未知单据 | 参数校验（沿用 r2） |

统一响应：`{code, message, details:{...}, traceId}`；409 的 `details` 必须含可执行的失败项数组与修复指引。

---

## 5. 数据模型增量

> 所有新表统一带 `tenant_id`，隔离实体带 `domain_id`、`vendor_id`（或由所属 Release/计划派生后落库，便于过滤）；唯一键均含 `tenant_id`；外键只做逻辑约束（不建库级 FK，沿用 r2）。迁移只增不删，新增 Flyway `V5__quality_management.sql`（现有最新 V4）。

| 表 | 关键字段 | 约束/索引 | 说明 |
| --- | --- | --- | --- |
| `test_strategy` | release_id, version_no, scope_json, risk_json, env_ids_json, round_plan_json, resource_note, owner_id, status, domain_id | uk(tenant, release, version_no) | 版本测试策略 |
| `test_plan`（增字段） | strategy_id, env_id, entry_criteria_json, exit_criteria_json, approved_by, approved_at, revision(default 0), closed_at, close_reason, review_comment | idx(release_id, status) | 生命周期与准出 |
| `test_plan_round` | plan_id, round_no, round_type, name, case_scope_json, executor_ids_json, start_at, end_at, status, conclusion, closed_by, closed_at, defect_ids_json | uk(plan_id, round_no)；idx(plan_id, status) | 轮次，用例集快照 |
| `plan_assignment` | plan_id, round_id, executor_id, module_id, planned_count, due_date, created_by | idx(plan_id, executor_id)；uk(plan_id, round_id, executor_id, IFNULL(module_id,0)) | 执行分配 |
| `plan_change_log` | plan_id, revision, change_type, before_json, after_json, reason, approver_id, approved_at, operator_id | idx(plan_id, revision) | 变更留痕 |
| `test_env` | name, env_type, base_url, owner_id, status, domain_id, vendor_id, note | uk(tenant, domain_id, name) | 环境台账 |
| `test_report` | scope(PLAN/RELEASE), plan_id, release_id, status, current_version, data_cutoff, created_by, domain_id | idx(release_id, scope)；idx(plan_id) | 报告头 |
| `test_report_version` | report_id, version_no, snapshot_json, result_digest, conclusion, pass_rate, coverage, generated_by, generated_at, signed_at, status, hash | uk(report_id, version_no)；idx(result_digest) | 版本快照 |
| `report_signoff` | report_id, version_no, sign_role, signer_id, decision, comment, signed_at, order_no | uk(report_id, version_no, sign_role) | 三级会签 |
| `quality_gate` | release_id, report_id, status, ruleset_version, evaluated_at, created_by, domain_id | uk(tenant, release_id) | 门禁配置/当前结果 |
| `quality_gate_rule` | gate_id, rule_code, enabled, threshold_json, status, evidence_json, evaluated_at | uk(gate_id, rule_code) | 规则当前态 |
| `quality_gate_snapshot` | gate_id, deploy_order_id, report_version_id, result, snapshot_json, hash, computed_at, created_by | idx(deploy_order_id)；idx(gate_id, computed_at) | 不可变快照 |
| `quality_gate_waiver` | gate_id, rule_code, release_id, reason, impact_scope, expiry_at, applicant_id, approver_id, approved_at, status | idx(gate_id, rule_code)；idx(status, expiry_at) | 规则级豁免 |
| `release_review` | release_id, deploy_order_id, gate_snapshot_id, report_id, report_version_no, meeting_at, participants_json, risks_json, open_defects_json, decision, conditions, doc_path, created_by | idx(release_id, meeting_at) | Go/No-Go 记录（技术输入表清单未列，交付项 9 需要持久化，本轮补齐） |
| `test_execution`（增字段） | round_id, env_id | idx(plan_id, round_id, case_id) | 执行绑轮次/环境 |
| `deploy_order`（增字段） | quality_gate_id, report_id；`admission_result_json` 扩为 6 项 | idx(quality_gate_id) | 准入第 6 项 |
| `defect`（增字段） | source(TEST/PROD/MANUAL), root_cause, found_phase, is_escape, escape_window_end | idx(release_id, is_escape, found_at) | 逃逸回流 |
| `release`（增字段） | observation_hours(default 72), report_validity_days(default 7) | — | 观察窗/有效期 |
| `metric_definition`（新增行） | FPY / REGRESSION_PASS_RATE / DEFECT_ESCAPE_RATE / POST_RELEASE_DEFECTS_72H / ROLLBACK_RATE | uk(tenant, code, version) | 指标版本化 |

**迁移回填**：每个有计划的 Release 生成一份默认策略；存量计划补 `strategy_id`、`revision=0`、`exit_criteria_json` 默认值；存量执行记录 `round_id` 归入各计划自动创建的 R1（`round_type=FEATURE`，结论按现状计算）；`deploy_order` 旧单 `quality_gate_id` 为空。

---

## 6. 权限矩阵增量

> 列沿用 r2 的 10 个角色；「测试(负责人)」指测试角色中该计划的 owner 或被配置为测试经理者，不新增系统角色（见 §15 T1）。

| 功能 | 业务部门 | 二级企业业务 | 二级企业负责人 | 产品 | 项目/域 | 研发 | 测试 | 运维 | 管理层 | 平台管理员 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 测试策略编制 | — | — | — | ⚪ | ⚪本域 | — | ✓ | — | ⚪只读 | ✓ |
| 计划编制/提交评审 | — | — | — | — | — | — | ✓ | — | — | ✓ |
| 计划批准/变更审批 | — | — | — | — | — | — | ✓(负责人) | — | ✓PMO | ✓ |
| 计划执行/开轮次 | — | — | — | — | — | — | ✓本厂商 | — | — | ✓ |
| 执行任务分配 | — | — | — | — | — | — | ✓(负责人) | — | — | ✓ |
| 环境台账维护 | — | — | — | — | ⚪只读 | — | ⚪只读 | ✓ | — | ✓ |
| 测试报告生成 | — | — | — | — | — | — | ✓(负责人) | — | — | ✓ |
| 报告会签 | — | — | — | ✓ | — | — | ✓(负责人) | — | ✓PMO | ✓ |
| 质量门查看 | — | — | — | ✓ | ✓ | ⚪本厂商 | ✓ | ✓ | ✓ | ✓ |
| 豁免申请 | — | — | — | ✓ | — | — | ✓(负责人) | ✓ | — | ✓ |
| 豁免审批 | — | — | — | — | — | — | — | — | ✓PMO | ✓ |
| Go/No-Go 发起/记录 | — | — | — | ⚪ | — | — | ✓(负责人) | ✓ | ✓PMO | ✓ |
| 生产缺陷提报 | — | — | — | ✓ | — | ⚪ | ✓ | ✓ | — | ✓ |
| 质量指标看板 | ⚪摘要 | — | ⚪摘要 | ✓ | ✓本域 | ⚪本厂商 | ✓ | ✓ | ✓ | ✓ |
| 导出报告/评审记录 | — | — | — | ✓ | ✓ | ⚪本厂商 | ✓ | ✓ | ✓ | ✓ |

（✓ 允许；⚪ 受限/按配置；— 不允许。技术方案需细化到接口级并逐格断言。）

---

## 7. 数据隔离与可见性

- 继续走 r2 的 `ScopeSql/ScopedJdbc` 单点：新增表与接口全部带 `/*scope:*/` 标记，fail-closed；不允许新写一套过滤。
- 归属派生：策略/计划/环境/报告/门禁/评审的 `domain_id` 由 Release 派生；计划的 `vendor_id` 由执行分配的厂商或 Release 承接方派生；**不接受前端覆盖**。
- 跨厂商：厂商测试只能看到分配给本厂商的计划/轮次/用例/报告段落；RELEASE 汇总报告对厂商只展示本厂商段落与汇总数字，其他厂商明细不可见；越权 403 `SCOPE_DENIED`。
- 质量门：发布单提交者只需能看结论与失败项；规则证据中的缺陷编号按可见性裁剪（不可见时只给数量），禁止通过失败项泄漏跨厂商缺陷标题。
- 快照：快照内容按生成时的可见性固化；后续权限变化不回写历史快照，但查看时再过一层当前权限（不可见字段脱敏为数量）。
- 导出：报告/评审记录导出按调用者权限过滤；PDF 水印含租户，防跨租户转发后无法追溯。
- 租户：缺 `X-Tenant-Id` 或与 JWT 不一致 403 且不落库，沿用 r2。

---

## 8. 非功能需求

| 类别 | 要求 |
| --- | --- |
| 性能 | 门禁求值（1 万用例/3 计划）P95 ≤500ms；报告生成（5000 用例）≤5s；XLSX 导出 ≤10s、PDF ≤15s（可异步，前端轮询）；计划进度/工作台 ≤1s；快照读取 ≤300ms；`result_digest` 计算 ≤300ms |
| 一致性 | 计划准出/报告/门禁/看板同源；整数完全一致，比率两位小数一致；同一筛选可下钻复算 |
| 并发 | 重复签署幂等；重复提交复用快照；同一计划并发开轮次只成功一个（唯一约束 + 409）；门禁求值对报告版本加读锁，避免签一半被引用 |
| 不可变 | 快照、已签报告版本、已关闭轮次、已生成评审记录不可修改；只能新增版本/记录 |
| 审计 | 全流程动作落 `audit_log`；导出单独记录 |
| 可用性 | 门禁失败必须给「哪条 + 差多少 + 怎么修」；报告数字可点开明细；计划完成失败列出具体缺陷/用例编号 |
| 兼容 | r2 既有接口只增不改；历史数据不重算、不伪造；旧发布单展示「未启用质量门」 |
| 浏览器 | Chromium 最新版；报告/评审可打印，A4 不横向溢出 |

---

## 9. 用户故事与验收标准

### 9.1 AC 编号对照

| 本文件夹 | GAP 建议编号 | 主题 |
| --- | --- | --- |
| AC-1 | AC-Q1 | 计划完整生命周期与变更留痕 |
| AC-2 | AC-Q2 | 多轮次与历史不可覆盖 |
| AC-3 | AC-Q3 | 结构化准出条件与 409 |
| AC-4 | AC-Q4 | 测试报告生成/版本/导出/会签 |
| AC-5 | AC-Q5 | 发布准入第 6 项质量门 |
| AC-6 | AC-Q6 | 规则级豁免与强制放行退役 |
| AC-7 | AC-Q7 | 门禁快照可回放 |
| AC-8 | AC-Q8 | 生产缺陷逃逸回流与 5 项指标 |
| AC-9 | P1（QP-1/QC-2/QC-3） | 测试策略、执行分配、环境台账 |
| AC-10 | P1（QA-4） | Go/No-Go 评审记录 |

### 9.2 用户故事

| 编号 | 故事 | 覆盖 AC |
| --- | --- | --- |
| US-1 | 作为测试负责人，我要让计划经过评审并留下每次范围变更的原因，避免「计划随时改、没人知道」。 | AC-1 |
| US-2 | 作为测试工程师，我要在缺陷修复后开 R2/R3 再测，并保留 R1 的原始结论。 | AC-2 |
| US-3 | 作为测试负责人，我要能判定计划「真的完成」，而不是「没人再执行」。 | AC-3 |
| US-4 | 作为测试负责人/产品/PMO，我要一份自动生成、可导出、可签署的版本测试报告。 | AC-4 |
| US-5 | 作为 PMO，我要发布单提交时被质量门硬拦住，并被告知差在哪、怎么补。 | AC-5 |
| US-6 | 作为域负责人，我要看到某条规则被谁、为什么、到什么时候豁免，而不是一个「强制放行」。 | AC-6 |
| US-7 | 作为验收人，我要回看三个月前那次发布当时的门禁数据。 | AC-7 |
| US-8 | 作为质量经理，我要知道发布后 72h 的缺陷与逃逸率，并能按厂商切片。 | AC-8 |
| US-9 | 作为测试经理，我要按人/模块分配用例、盯进度，并维护环境台账。 | AC-9 |
| US-10 | 作为 PMO，我要在发布前一屏看完门禁、报告、遗留缺陷与风险，并生成评审记录。 | AC-10 |

### 9.3 AC-1 计划完整生命周期与变更留痕

- **AC-1.1** 创建缺 Release/策略/owner/时间窗/用例/准出条件任一 → 400 `PLAN_INCOMPLETE`，`missingFields` 列出全部缺失项；补齐后 200，状态 DRAFT。
- **AC-1.2** 非法流转：DRAFT 直接 `complete`、PENDING_REVIEW 直接 `close`、ARCHIVED 后任何写 → 409 `ILLEGAL_PLAN_TRANSITION`，`allowedNext` 与状态机表一致；状态与 `updated_at` 不变。
- **AC-1.3** 批准：非 owner 的测试人员或研发调用 → 403；owner/PMO → 200，`approved_by/at` 有值；驳回必须带意见（空 → 400），状态回 DRAFT。
- **AC-1.4** 未批准计划被引用：用 DRAFT/PENDING 计划的需求建发布单 → 409 `PLAN_NOT_APPROVED`，`planIds` 命中；`deploy_order` 行数前后不变。
- **AC-1.5** 已批准计划删 1 条用例：不带 reason → 400 `CHANGE_REASON_REQUIRED`；带 reason+审批人 → 200，`plan_change_log` 恰 1 行且含前后 JSON、原因、审批人；`plan.revision` +1；旧报告在门禁中报 `QG-02/REPORT_STALE`。
- **AC-1.6** 关闭需 reason；归档后 GET 正常、写 409；全程 `audit_log` 可按 plan_id 反查 ≥ 状态变更次数。

### 9.4 AC-2 多轮次与历史不可覆盖

- **AC-2.1** 开 R1（FEATURE）→ 执行 3 条（2 PASS/1 FAIL）→ 关闭 → 结论 FAIL；再开 R2（REGRESSION，取上一轮失败用例）→ 用例集恰为 1 条且 `case_version` 与 R1 一致。
- **AC-2.2** R1 未关闭时开 R2 → 409 `ROUND_IN_PROGRESS`；R1 有未执行用例直接关闭 → 409 `ROUND_HAS_UNEXECUTED` 并列出用例编号；标记 SKIP+原因后可关闭。
- **AC-2.3** R1 关闭后补录/修改其执行记录 → 409 `ROUND_CLOSED`；R1 的通过率、用例集、结论在 R2/R3 后保持不变（读取三次一致）。
- **AC-2.4** 执行记录缺 round_id 且无进行中轮次 → 409 `NO_ACTIVE_ROUND`；有进行中轮次时自动归入；响应与详情均返回 round_no。
- **AC-2.5** 计划汇总同时返回分轮次与累计两套数字；累计值 = 每用例最新结果，与手算一致；轮次数量 ≥3 时 JSON 不截断。
- **AC-2.6** R3 缺陷验证轮可关联缺陷编号；关闭后 `defect_ids_json` 可查，缺陷详情可反查该轮。

### 9.5 AC-3 结构化准出条件

- **AC-3.1** 默认阈值 95：构造 100 条用例、95 PASS/5 FAIL，且 P0/P1=0、无必需用例 → `complete` 200，状态 COMPLETED，`completed_at` 有值。
- **AC-3.2** 94 PASS/6 FAIL → 409 `PLAN_EXIT_CRITERIA_UNMET`，`unmetItems` 含 `PASS_RATE_BELOW{required:95, actual:94.0}`；计划仍 IN_PROGRESS。
- **AC-3.3** 未关闭 P1=1 → 409，`unmetItems` 含 `OPEN_BLOCKING_DEFECTS{required:0, actual:1, detail:["BUG-xxxx"]}`；关闭后重试 200。
- **AC-3.4** 必需用例集 3 条，其中 1 条 FAIL → 409 `REQUIRED_CASES_NOT_PASSED{required:100, actual:66.67, detail:[TC-xxxx]}`；该用例改 PASS 后 200。
- **AC-3.5** 直接用 `PATCH /test-plans/{id}` 置 COMPLETED → 与 `complete` 完全相同的 409 与 `unmetItems`，不允许旁路。
- **AC-3.6** 同源：报告中的通过率、门禁 QG-03 的 actual、计划详情通过率，在同一时点三者整数与两位小数完全一致（差异 0）。

### 9.6 AC-4 测试报告

- **AC-4.1** RELEASE 范围无已批准计划 → 409 `REPORT_NO_PLAN`；有 2 个计划（1 已批准、1 待评审）→ 生成成功，报告列出「未纳入计划」，门禁 QG-01 因覆盖缺口 FAIL。
- **AC-4.2** 报告含 8 个固定段落；每段数字可由 API 明细复算：PASS/FAIL/BLOCKED/SKIP/NOT_RUN 计数逐项相等，通过率/覆盖率两位小数相等。
- **AC-4.3** 再生成 → v2；v1 仍可 GET 且内容哈希不变；v1 与 v2 的 `result_digest` 在数据变化后不同。
- **AC-4.4** XLSX 导出：单元格中的用例数/通过数与 API 相等；PDF 每页含水印 `内部资料 · <租户> · <报告编号> · v<版本>`；两文件均含报告编号与版本号。
- **AC-4.5** 会签越序（产品先于测试负责人）→ 409 `SIGNOFF_ORDER_VIOLATION`；同一人重复签 → 200 且 `report_signoff` 不新增行；三人签完 → 版本 SIGNED，`signed_at` 为最后一次签署时间。
- **AC-4.6** 未签完的报告被门禁引用 → 409 `REPORT_NOT_SIGNED`；驳回后版本 REJECTED，门禁拒绝；重出 v2 并完成三签后，v1 转 SUPERSEDED 且只读。
- **AC-4.7** 有效期：签署后第 7 天 23:59 门禁通过，第 8 天 00:01 失败 `REPORT_EXPIRED`；Release 改 `report_validity_days=1` 后第 2 天失败。
- **AC-4.8** 结论上调：建议 CONDITIONAL 时选 PASS → 409 `CONCLUSION_UPGRADE_FORBIDDEN`；选 FAIL 允许，且必须填原因。

### 9.7 AC-5 版本发布质量门与准入第 6 项

- **AC-5.1** 发布单详情/预览返回 6 项，原 5 项名称与判定不变（逐项构造 r2 的失败场景仍 FAIL）；第 6 项返回规则数组。
- **AC-5.2** 无报告 → 提交 409 `QUALITY_GATE_FAILED`，`failedRules` 含 QG-01 与修复指引；`deploy_order` 状态未变、无 PASS 快照。
- **AC-5.3** 报告结论 FAIL / 已过期 / `result_digest` 不一致 / 计划 revision 变更 / 通过率 94.9 / 未关闭 P1=1，各自单独构造 → 409，`failedRules` 精确命中对应规则（QG-02 或 QG-03 或 QG-04），未命中的规则为 PASS。
- **AC-5.4** CONDITIONAL 报告：无豁免清单或无 PMO 审批 → QG-02 FAIL；补齐豁免且审批通过 → 门禁 `PASS_WITH_WAIVER`，准入第 6 项 PASS。
- **AC-5.5** 绕过前端：直接 `POST /deploy-orders/{id}/submit`、直接 `PATCH` 状态为「已提交」、绕过网关直连 8082，三者返回同一 409 与同一 `failedRules`。
- **AC-5.6** 通过：快照落库且 `hash` 非空；`deploy_order.quality_gate_id/report_id` 有值；重复提交返回同一 `snapshotId`。
- **AC-5.7** 严格模式：Release `gate_mode=STRICT` 时阈值按 100% 判定，94.9% 失败、100% 通过；宽松模式语义沿用 r2。

### 9.8 AC-6 规则级豁免与强制放行退役

- **AC-6.1** 申请缺原因/影响范围/有效期任一 → 400 `WAIVER_FIELDS_REQUIRED`；有效期 0 天或 31 天 → 400 `WAIVER_EXPIRY_INVALID`；一次提交两个规则码 → 400 `WAIVER_RULE_SINGLE_ONLY`。
- **AC-6.2** 豁免 QG-04（P0/P1）→ 400 `RULE_NOT_WAIVABLE`，无记录落库。
- **AC-6.3** 非 PMO/管理员审批 → 403；PMO 审批 → 该规则 WAIVED，`approved_by/at` 有值，写审计并通知域负责人。
- **AC-6.4** 关键反例：QG-03 FAIL + QG-01 已豁免 → 门禁仍 FAIL，`failedRules` 只有 QG-03；证明一条豁免不能把整门放行。
- **AC-6.5** 豁免到期后重新求值 → 规则 FAIL、门禁 FAIL；快照中仍保留该豁免 id 与到期时间，历史不变。
- **AC-6.6** 强制放行：`POST /deploy-orders/{id}/force-release` → 409 `FORCE_RELEASE_RETIRED`，提示豁免流程并写审计；UI 无该按钮；历史放行记录仍可见。

### 9.9 AC-7 门禁快照可回放

- **AC-7.1** 发布单详情返回快照：门禁结果、逐规则 required/actual/evidence、报告 id+version+conclusion+signed_at+digest、未关闭 P0/P1 编号、豁免清单、`computedAt`、`hash`。
- **AC-7.2** 快照生成后新增缺陷、重出报告 v2、修改计划：重新 GET 同一发布单，快照字段与 hash 完全不变。
- **AC-7.3** 用快照中的报告版本与缺陷编号清单复算：通过率、P0/P1 数与快照逐位一致（整数相等，比率两位小数相等）。
- **AC-7.4** 快照无更新/删除接口；直接改库不由应用保证，但 API 尝试 PUT/DELETE → 405/404。
- **AC-7.5** r2 历史发布单（无 gate）返回 `qualityGate=null` 且展示「未启用」，不得返回伪造 PASS 或空规则数组冒充通过。

### 9.10 AC-8 生产缺陷回流与质量指标

- **AC-8.1** 创建 `source=PROD` 且无 release_id → 400 `PROD_DEFECT_RELEASE_REQUIRED`；有 release_id → 落库，`source` 可查。
- **AC-8.2** 发布单执行成功 `ended_at=T`，观察窗 72h：T+1h、T+71h59m 创建的关联缺陷 `is_escape=true`；T+72h01m 为 false；边界按秒判定。
- **AC-8.3** 修改 Release `observation_hours` 后，已创建缺陷的 `is_escape` 不变；新缺陷按新窗口判定。
- **AC-8.4** `GET /releases/{id}/quality` 的逃逸数、72h 缺陷数、回滚率与同筛选的缺陷/发布单列表计数完全一致；改窗口不改变历史统计。
- **AC-8.5** 5 条指标在 `metric_definition` 各有一条新版本记录（含公式、生效日期、owner）；看板/报告/门禁同一筛选下数字一致，下钻明细可复算。
- **AC-8.6** 发布后逃逸缺陷出现 → 同 Release 再提发布单时 QG-02 因 digest/数据变化 FAIL 并要求重出报告；**已通过的历史发布单与快照不回滚、不改写**。

### 9.11 AC-9 测试策略、执行分配与环境台账

- **AC-9.1** 新建计划不挂策略 → 400 `STRATEGY_REQUIRED`；挂同 Release 最新策略 → 200；跨 Release 的策略 → 400。
- **AC-9.2** 策略范围只能选该 Release 的需求，选外部需求 → 400；保存后 version_no 递增，旧版本只读。
- **AC-9.3** 分配 3 人×模块合计 99，计划 100 条 → 400 `ASSIGNMENT_MISMATCH`，`diff=1`；补齐 100 → 200。
- **AC-9.4** 分配后工作台「我的待执行用例」只返回本人用例与本人负责计划的未分配用例；计数与计划进度接口一致；他人用例不可见。
- **AC-9.5** 超期：截止日为昨天且有未执行 → 工作台标红、收到 1 条通知；同日重复触发不重复通知（幂等）。
- **AC-9.6** 环境：同域重名 → 409；被计划引用后删除 → 409 `ENV_IN_USE`；停用后历史执行记录仍显示环境名；跨域查询 → 403。
- **AC-9.7** 执行记录必带 env_id；缺省继承计划环境；切换环境后报告按环境分组且数字可复算。

### 9.12 AC-10 Go/No-Go 评审记录

- **AC-10.1** 视图同时返回门禁快照、报告版本、未关闭 P0/P1、策略风险；任一为空时显示「未评估/无」，不报 500。
- **AC-10.2** 生成记录：必填会议时间、参与人、结论；结论 NO-GO 或 CONDITIONAL 时必须填风险与缓解，否则 400。
- **AC-10.3** 记录生成后不可修改；PDF 导出含结论、参与人、门禁快照 hash 与报告版本。
- **AC-10.4** 门禁未求值时生成记录 → 标注「未评估」，且不能作为 GO 依据（提交时仍按质量门判定）。
- **AC-10.5** NO-GO 记录存在时，发布单提交需 PMO 二次确认（留下确认人/时间）；质量门 FAIL 时二次确认也不能提交。

---

## 10. 范围与非目标

### 本轮交付范围（M6）

P0：计划生命周期与评审/变更、结构化准出、轮次、测试报告、质量门+规则级豁免（含量化指标与准入第 6 项）。
P1：测试策略、执行分配与进度、环境台账、Go/No-Go 评审、生产缺陷回流与观察窗、5 项质量指标。

### 非目标（明确不做）

- **不做** Sonar/制品/构建的实际对接（QG-05 只留扩展位与 `enabled=false`，M7 集成对接时接）。
- **不做** 自动化测试执行器/调度；仍只接收上报（r2 契约不变）。
- **不做** 测试数据造数平台、用例评审审批（默认关）、供应商质量评分卡、质量复盘自动报表（P2 顺延）。
- **不做** 邮件/企业微信/Webhook 通知（沿用站内通知；M7 再议）。
- **不做** 多租户隔离形态升级（仍 ROW）、不改 r1 的 Nacos/网关骨架、不替换版本矩阵。
- **不做** 历史数据重算或旧报告补签；旧发布单只展示「未启用质量门」。
- **不做** 计划/报告的富文本编辑器；结论与风险用纯文本 + 附件。

---

## 11. 反面目标（技术通过但会被退货的情形）

1. **门禁只在按钮上**：页面显示红色不通过，但 `curl` 直接调提交接口 200，或直连 8082 能过。观察方式：QA 用三种入口（UI、网关、直连）各提交一次，必须三次都 409 且失败项一致。
2. **数字两张皮**：报告说通过率 96%，看板同筛选 91%，门禁又按第三个数判定；或报告只存汇总、点不开明细。观察方式：同一时点取三处数字与明细计数，整数不相等即退货。
3. **生命周期空转**：计划没评审也能被发布单引用；批准后随便改用例不留原因；重出报告后旧报告的范围快照跟着变（历史被改写）。观察方式：改一次范围，查变更日志与旧报告版本内容。
4. **豁免变万能放行**：一条豁免把整个质量门置为通过，或 P0/P1 也能豁免，或没有有效期/审批人仍生效。观察方式：只豁免 QG-03、让 QG-01 失败，门禁必须仍 FAIL；尝试豁免 QG-04 必须 400。
5. **逃逸统计永远为 0**：72h 内生产缺陷不标记、不回写；观察窗改配置后历史被重算；指标与缺陷列表对不上。观察方式：发布成功后 1 分钟提一个 PROD 缺陷，版本质量页的逃逸数必须为 1，且与缺陷列表同筛选一致。

---

## 12. 依赖与风险

| 项 | 说明 | 应对 |
| --- | --- | --- |
| 依赖 r2 实现 | `test_plan/test_plan_case/test_execution/deploy_order/defect/release` 的现有字段与状态；r2 的 `GatePort` 模式 | 只增字段/接口；迁移回填；r2 全量门禁必须继续绿 |
| 依赖 r2 隔离 | `ScopeSql/ScopedJdbc` 单点 | 新表全部带 scope 标记，负向用例覆盖跨域/跨厂商 |
| 风险：多计划加权口径 | 一个 Release 可能多厂商多计划，简单平均会失真 | 统一按用例数加权；报告与门禁复用同一聚合；AC-3.6/AC-5.3 断言 |
| 风险：报告快照体积 | 5000 用例的明细 JSON 可能数 MB | 快照只存 id 清单 + 汇总 + digest，明细按 id 回查；导出时再拼装 |
| 风险：PDF 中文字体 | 服务端渲染可能缺字体/水印错位 | 复用既有导出链路；QA 需核对 PDF 中文与水印（AC-4.4） |
| 风险：会签与审批流耦合 | r2 有第五类单据开关（默认关） | 本轮 Report signoff 独立实现，不复用 wf 实例；开关保持关闭 |
| 风险：旧「强制放行」调用方 | 可能有脚本/页面仍调用 | 保留路由返回 409 + 明确错误码；UI 移除入口；审计保留 |
| 风险：性能 | 门禁求值跨计划/缺陷/报告 | 单次聚合 SQL + digest；1 万用例 P95 ≤500ms 作为硬指标 |

---

## 13. 里程碑建议（M6，五批）

| 批次 | 内容 | 出口检查 |
| --- | --- | --- |
| M6.1 数据与策划 | Flyway V5、策略、环境台账、计划生命周期与变更日志、迁移回填 | AC-1、AC-9.1/9.2/9.6 |
| M6.2 执行闭环 | 轮次、分配/进度/工作台、准出条件与服务端完成判定 | AC-2、AC-3、AC-9.3–9.7 |
| M6.3 报告 | 生成器（8 段）、版本化、digest、XLSX/PDF 水印、三级会签 | AC-4 |
| M6.4 门禁 | 规则集、求值、快照、准入第 6 项、豁免、强制放行退役 | AC-5、AC-6、AC-7 |
| M6.5 治理与改进 | Go/No-Go、生产缺陷回流/观察窗、5 项指标、看板、种子与 E2E | AC-8、AC-10；全量负向与回归 |

每批结束跑：`make platform-verify`、`make platform-test-negative`；M6.5 后补 `make platform-perf` 与浏览器 E2E（报告导出、门禁失败、豁免闭环三条主链路）。

---

## 14. 工程约束

- **分支**：`feat/qa-quality-management` 已创建（基线 main `5f1bb66`，即 r2 合回后的最新 main）。本轮所有实现与文档提交在该分支，验收通过后合回 main。
- **未提交改动**：当前仅 `?? docs/teamflow/20261109-r3-qa-quality-management/`（本任务夹）。保留、不 stash、不丢弃，随本轮分支一并提交。
- **技术栈不变**：JDK 25.0.2、Spring Boot 4.0.9、Spring Cloud 2025.1.4、MySQL 9.6+Flyway、req-service(8082)/deliver-service(8083)/api-gateway(8080)、Vue3+Element Plus+Vite(5173)、JWT。不引入 Docker、Gradle、Flowable/Camunda、Redis/MQ、Python 交付物。
- **数据迁移**：只新增 `V5__quality_management.sql`（现有最新 V4），不改已执行迁移；回填逻辑见 §5；迁移必须可重复执行于 r2 的种子库。
- **服务归属**：质量门算法唯一实现于 deliver-service（`qualitygate` 包），req-service 通过既有 `GatePort` 调用；报告导出复用 deliver 既有导出与水印；不得在 req 复制判定 SQL。
- **接口兼容**：r2 接口只增不改签名；`force-release` 保留路由并返回 409 `FORCE_RELEASE_RETIRED`；`admission_result_json` 结构向后兼容（旧 5 项读取方不受影响）。
- **验证命令（提交前必须全绿）**：`make dev-up` → `make verify` → `make test-negative` → `make dev-down`；以及 `make platform-up` → `make platform-verify` → `make platform-test-negative` → `make platform-perf` → `make platform-unit` → `make lint`。为本轮新增 `scripts/tests/quality/*.sh`，纳入 `make platform-verify`（或新增 `make quality-verify` 并由 `make test` 汇总）。
- **前端**：新增路由 `/test-strategies`、`/test-reports/:id`、`/quality-gates/:releaseId`、`/test-envs`、`/releases/:id/go-no-go`、`/quality-metrics`；改造 `/test-plans/:id`、`/test-plans/:id/execute`、`/deploy-orders/:id`、`/workbench`。不新增 UI 组件库。
- **.gitignore**：现有规则已覆盖 `target/`、`node_modules/`、`dist/`、`.vite/`、`.pnpm-store/`、`coverage/`、`*.tsbuildinfo`、`.env*`（保留 `.env.example`）、`.nacos/`、`.run/`、`.m2/`、`*.log`、`.DS_Store`、`.idea/`、`logs/teamflow/`。本轮若产生导出落盘目录（如 `var/reports/`、`storage/`），必须确认命中 `var/` 或补充忽略；不得移除 `logs/teamflow/`、`.nacos/` 的忽略规则。
- **提交信息**：注明任务夹 `docs/teamflow/20261109-r3-qa-quality-management/` 与 runId（如团队沿用 r2 格式）；不提交 `target/`、导出产物与本地环境文件。

---

## 15. 假设与待澄清

以下 6 项均为**用户口味/治理强度**选择（非事实缺失），PRD 已按推荐默认落笔、可直接开工；若用户另选，按「若改选」列调整，不影响整体架构。

- **T1 测试负责人如何识别**：默认不新增系统角色，测试负责人 = 计划 owner（测试角色成员）或团队配置的测试经理标记；批准/会签权限限本域。理由：避免破坏 r2 的 10 角色矩阵与种子账号断言。若改为新增 `TEST_LEAD` 角色，则权限矩阵、种子数据与 AC-5 断言需同步调整。
- **T2 报告会签驳回后的处理**：默认整份版本作废（REJECTED），修订后生成 v(N+1) 重走三签。理由：签署必须对应同一份内容，避免「部分签署沿用」。若改为退回当前签署人重签，则版本号不变、审计需记录重签原因。
- **T3 豁免红线与期限**：默认 QG-04（未关闭 P0/P1）不可豁免，其余规则豁免 1–30 天、到期自动失效。理由：P0/P1 放行等于质量门失效。若允许 P1 豁免，需定义「P1 豁免需管理层双签」并接受审计风险。
- **T4 逃逸缺陷对已通过门禁的影响**：默认历史快照与已通过发布单冻结，不回滚；同版本后续发布单必须重出报告并重新门禁，版本质量页标红。理由：历史不可改写是快照可回放的前提。若要求自动吊销，则需新增「门禁吊销」状态与回滚流程。
- **T5 FPY 口径**：默认 R1 首轮一次通过率（分母 = R1 已执行用例数，未执行不计）。理由：FPY 衡量首次交付质量。若改为「需求首次测试通过率」，则按需求维度聚合，计划级 FPY 不可比。
- **T6 Go/No-Go 是否硬阻断**：默认不硬阻断（质量门是唯一硬门禁），NO-GO 时提交需 PMO 二次确认并留痕。理由：避免双门禁冲突与「有记录就能过」的错觉。若改为 NO-GO 直接拒绝提交，则发布单状态机需增加 REVIEW_BLOCKED。

**未决事实项：无。** 本轮范围、阈值默认值（7 天 / 95% / 100% 严格 / 72h / PMO 审批 / 第 5 规则预留）与既有 r2 契约均由需求澄清固定，无需要额外拍板的客观信息。
