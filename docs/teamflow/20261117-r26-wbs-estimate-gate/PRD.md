<!-- meta: summary="新增第五类审批单据 WBS_ESTIMATE（工时评估初审）：PDR 排期与 RD 开始开发前必须有已通过的工时评估，否则 409 ESTIMATE_NOT_APPROVED；通过后人天改动超 ±20% 评估即失效；allowedActions/详情卡片/接口三处同源判定，演示夹具补已通过评估单，门禁只增不减。" -->
基线依赖：docs/teamflow/20261116-r23-flow-control-gates（拆解前置门、排期前置门、同码同结构、403 先于 409 的判定顺序不得回归）
取代：docs/teamflow/20261116-r23-flow-control-gates#AC-6：`allowedActions` 不再只由「状态机合法 ∩ 授权」决定，PDR 的 `schedule` 与 RD 的 `start_dev` 还要过工时评估门（未通过的从 `allowedActions` 中剔除）；16 动作矩阵与 r22 数据驱动判定仍零改动。

# 需求工时评估初审（WBS_ESTIMATE）

## 1. 背景与目标

**现状（三件事，都是缺口）**

1. 工时只是一个字段：`estimateDays` 有 0.5 天精度校验，但**没有独立环节、没有前置门**（`docs/teamflow/FLOW-CONTROL-AUDIT.md` §3 G2）。实测无工时的 PDR 调 `schedule` 返回 200；无工时的 RD 由 dev1 调 `start_dev` 返回 200 → `IN_DEV`。排期与开发之间没有「人天经过评审」这道关。
2. 审批引擎已有 4 类单据（`REQ_SUBMIT` / `REQ_REVIEW` / `RELEASE` / `REQ_DEPLOY`），会签/或签、审批人解析（ROLE / USER / SUPERVISOR / DOMAIN_OWNER）、待办已办、加签转办催办撤回都齐备——缺的只是第五类单据和一处门。
3. 需求状态机里没有"评估态"，也没有"排期/开发需先评估"的判定；`estimateDays` 还能被 `PUT extra.estimateDays` 与 `POST deliver` 两条路径事后改动，且改完不影响任何门。

**目标**

- **G1 环节**：工时评估成为独立的第五类审批单据 `WBS_ESTIMATE`，绑定单个需求（PDR 或 RD），结论可追溯（谁提、谁审、审的多少天、为什么驳回）。
- **G2 门**：没有"已通过且未失效"的评估单时，PDR 不能 `schedule`、RD 不能 `start_dev`，返回 409 `ESTIMATE_NOT_APPROVED`。
- **G3 同源**：`allowedActions`、详情卡片、接口 409 由**同一个判定函数**产出——页面上不给的动作，接口一定 409；接口给的，页面一定给。
- **G4 不回归**：父需求只读汇总、人天汇总口径、r23 拆解门与排期父门、版本测试/发版审核门、r22 动作数据驱动判定、三档授权、1848 行授权数据全部不动。

## 2. 用户故事

- **US-1 研发负责人（dev1 这类）**：在需求详情提交工时评估（人天 + 说明），随时看到"审批中 / 已通过 / 已驳回（附原因）"，不用去问产品"我这单能不能开工了"。→ AC-1、AC-3、AC-4、AC-13
- **US-2 产品经理 / 域负责人（po1、pm1 这类）**：在审批中心一处处理评估待办，或签即可通过；驳回必须写原因，原因出现在需求动态里让研发看得到。→ AC-3、AC-4、AC-6
- **US-3 项目经理 / PMO**：没有通过的评估时，排期与开始开发按钮就在但点不动、写明"需先通过工时评估（当前评估单：…）"；绕过页面直接调接口也被 409 拦住，`detail` 明确说明缺什么（`required:"ESTIMATE_APPROVED"`）。→ AC-7、AC-8、AC-11、AC-12、AC-13
- **US-4 业务方 / 审计**：看到的人天是"被审过的数"；通过后又被人改大改小超过 20%，这张评估单立即失效、门重新关上。→ AC-10
- **US-5 演示者**：演示环境里内部链路与厂商链路都不被新门卡死，评测/演示时点得下去。→ AC-15、AC-16

## 3. 范围与非目标

**范围内**

- 后端：新增 `WBS_ESTIMATE` 单据类型 + 种子流程（或签：域负责人 / 产品经理）+ 提交端点 + 审批中心摘要 Provider + 需求动态三类事件（提交/通过/驳回）+ 评估门 + 需求视图 `estimate` 块 + 409 detail。
- 前端：需求详情概览的评估卡片、受评估门约束动作的**禁用态与原因文案**（见 §5.7）、审批页签取单（不走「只看我的待办」过滤）、审批中心 `工时评估` 标签与筛选、对应 api。
- 夹具与门禁：`DemoFixture` / `DemoResetService` 补已通过评估单；`platform-verify`、`platform-test-negative`、`platform-demo-reset` 同步；`access` 追加评估按钮断言。
- 文档：本任务夹、`memory.md` 同主题行替换（评估单与阈值是团队约定）。

**非目标**

- 不做附件、不做多轮评估/会签升级（两级审批）、不做 PMO 二级复核、不做按人天条件分支的自动路由（不用引擎 CONDITION 节点）。
- 不做 BUS 需求评估、不做批量评估、不做只读的"预估/已投入/偏差"看板（审计文档 §4 R-B 的业务视图不在本轮）。
- 不新增需求状态（状态机不加"评估中"）、不新增角色、权限码、动作码。
- 不改 r22 判定机制、三档授权、权限矩阵、`StateTables` 语义、拆解门/排期父门/版本测试与发版审核门。
- 不回填存量业务数据的评估单（见 F3）；不做移动端与打印样式适配。

## 4. anti-goals / 反面目标

- **AG-1 演示环境一进详情就处处 409**：12 条演示需求一条评估单都没有，"排期""开始开发"全被拦，演示比改动前更难走——技术上门是对的，但产品上不可用。可核验：reset 后分别用 admin / vpm1 各点一次排期与开始开发均 200；`wf_instance` 里 `WBS_ESTIMATE` 11 张 `APPROVED`、0 张 `PENDING`。
- **AG-2 页面说能排期、点下去 409（或反之）**：详情 `allowedActions` 与接口判定各写一套。可核验：对 6 种评估结论状态（NONE/PENDING/APPROVED/REJECTED/SUPERSEDED/STALE）逐一同时读详情与直接调接口，两者结论一致。
- **AG-3 评估通过后被悄悄改人天，仍然能开发**：门形同虚设。可核验：`approvedDays=10`，把需求人天改成 12.5 后 `start_dev` 返回 409，卡片显示"已失效（人天已改）"。
- **AG-4 同一需求同时两张"已通过"，或旧单仍显示"审批中"**：用户不知道该信哪一张。可核验：任意时刻该需求的 `APPROVED` ≤ 1、`PENDING` ≤ 1；重提后旧单在时间线上显示"已作废"。
- **AG-5 没权限的人被点进全局 /403 页**：r25 刚修完的形态回潮。可核验：vpm1 点"提交评估"零请求、零 URL 变化、无 `/403` 页面；非审批归属人调审批动作 403 且页面就地提示。

## 5. 交互流程摘要

**5.1 正常链**：需求详情（概览）→ 评估卡片显示"未评估"→ 填预估人天 + 说明 → 提交 → 卡片"审批中"、需求动态一条"提交工时评估"→ 审批中心出现待办（域负责人 / 产品经理，或签）→ 通过 → 单据 `APPROVED`、需求 `estimateDays` 写入评估值、动态一条"工时评估通过"→ 卡片"已通过"、`allowedActions` 出现 `schedule`（PDR）/`start_dev`（RD），原本禁用的动作按钮变为可用。

**5.2 驳回与重提**：审批人驳回需填原因 → 单据 `REJECTED`、动态一条「工时评估驳回：<原因>」、卡片展示原因与"重新提交"→ 重提时旧单置 `SUPERSEDED`（不删除，时间线可查），新单 `PENDING`。

**5.3 判定顺序（pin）**：403（无写权限 / 无审权 / 越权数据面）→ 409 `ILLEGAL_TRANSITION`（状态机不允许）→ 既有门（r23 父门等，维持现有先后）→ 409 `ESTIMATE_NOT_APPROVED` → 落库 + 审计 + 通知。既有门失败时的错误码与文案不变。

**5.4 结论状态与门的对照（pin）**

| 评估结论状态 | 含义 | PDR `schedule` / RD `start_dev` |
|---|---|---|
| `NONE` | 从未提交 | 409 |
| `PENDING` | 有在途单（含重提后的新单） | 409 |
| `APPROVED` | 已通过，且需求当前人天相对评估值偏差 ≤ 20% | 放行 |
| `REJECTED` | 最近一张被驳回且未重提 | 409 |
| `SUPERSEDED` | 已被新单取代（以最新单为准） | 409 |
| `STALE` | 已通过，但需求人天相对评估值偏差 > 20% | 409 |

**5.5 失效规则（pin）**：`|需求当前 estimateDays − 单.approvedDays| / 单.approvedDays > 20%` 即失效，**严格大于**，按 0.5 天精度比较。判定在**读取时派生**，不写库：单据记录态只有 `PENDING/APPROVED/REJECTED/SUPERSEDED`，`STALE` 是读出来的（这样 `PUT extra.estimateDays`、`POST deliver` 两条人天写路径都不会漏）。

**5.6 唯一性（pin）**：同一需求同一时刻至多 1 张 `PENDING`、至多 1 张 `APPROVED`。通过时把该需求其它 `PENDING` 单置 `SUPERSEDED`；已 `APPROVED` 且人天未变的重复提交返回 409 `ESTIMATE_ALREADY_APPROVED`（不建空转单）；已 `APPROVED` 但偏差 > 20% 时允许新建，旧单保持 `APPROVED` 供追溯，门按 `PENDING` 拦。

**5.7 动作行的渲染与禁用（pin，本轮用户拍板）**：`schedule`（PDR）与 `start_dev`（RD）两个受评估门约束的动作，**渲染与否看权限，能不能点看门**——有权限就渲染按钮，门未满足则 `disabled` 并在按钮 `title` 与动作行内联提示写明原因；不得再用 `allowedActions` 当渲染开关（`allowedActions` 的口径本身不变，仍不返回被门挡住的这两个动作，见 AC-11）。

| 原因类别 | 触发 | 文案（title 与行内一致） | 交互 |
|---|---|---|---|
| 权限不足 | 功能码/动作码 DENY（r25 口径） | `需要权限：<码>` | disabled，点击零请求、不落 /403 |
| 审批门未满足 | 评估单为 `NONE/PENDING/REJECTED/SUPERSEDED/STALE` | `需先通过工时评估（当前评估单：无 / 待审核 / 已驳回 / 已失效）` | disabled，点击零请求 |

两类原因都禁用，但**文案不同、不可混用**：权限不足不写"工时评估"，评估门未满足不写"需要权限"。评估单状态 → 文案：`NONE`→无、`PENDING`→待审核、`REJECTED`→已驳回、`SUPERSEDED`→已作废、`STALE`→已失效、`APPROVED`→（按钮可用，无提示）。概览顶部评估卡片的门提示与动作行内联提示取同一份文案来源，两处口径一致。

## 6. 优先级

整体 **P0**（管控缺口：排期与开发当前不受任何评审约束）。分项：单据与门 P0；评估卡片与审批中心可见 P1；审批页签时间线、文案打磨 P2。

## 7. 依赖与风险

- **bizType 硬编码散点**：`WorkflowVersion` 常量与"四类单据"注释、审批中心 `BIZ_LABELS`、需求详情 `listApprovalMine` 里 `bizType.includes('REQ')` 的过滤、统计/导出口径——漏一处就表现为"单建了但看不到"，需逐点核查（AC-6 覆盖）。
- **存量脚本大面积 409（最大回归风险）**：`platform-verify` 的 `schedule`、`platform-test-negative` 的 `start_dev`、VENDOR_PM 用例的排期都跑在无评估数据上，必须逐个补评估或换数据，否则门禁直接红。
- **两条人天写路径**：`PUT extra.estimateDays` 与 `POST deliver`，失效做成读取派生才不会漏（见 5.5）。
- **小基数阈值**：`approvedDays=2` 时改 0.5 天就是 25% > 20% 即失效——设计使然，需在卡片文案与测试里体现，别让人误以为门"随机"。
- **`allowedActions` 语义变更**与 r23 AC-6 的约定冲突，已用「取代」声明；`decompose` 仍不进 `allowedActions`。
- **渲染源混淆**：需求详情现有动作行由 `allowedActions` 与权限混合驱动，若沿用旧写法"不在 allowedActions 就不渲染"，就会出现"权限够但按钮消失"，与 §5.7 的"渲染看权限、禁用看门"冲突；改动时须把两套判定分开落位（权限 → 渲染，门 → 禁用 + 原因），并保留 r25 的权限文案断言。
- **审批人为空**：域内无负责人时既有引擎行为是无人可审，评估单会永远 `PENDING` 把链路卡死；演示域的负责人必须存在（夹具已指定审批人）。

## 8. 假设与待澄清

**8.1 未定事实类（fact）**

- **F1 失效判定的落点**：假定"存储态只有 PENDING/APPROVED/REJECTED/SUPERSEDED，`STALE` 读取时按人天偏差派生，不在人天写路径上做失效回写"。理由是两条写路径 + 未来新增路径都可能漏写。若改为落库 `STALE`，需要在所有写路径拦截并保证一次不落，改动面与漏判风险显著变大。
- **F2 重提语义**：假定"旧单作废（`SUPERSEDED`）而不是允许并存后取最近一张通过"。若改为并存，AC-5 的"`APPROVED` ≤ 1"断言与 `ESTIMATE_ALREADY_APPROVED` 分支都要改。
- **F3 存量数据**：假定"存量业务需求不回填评估单，新门只约束新流转；只有演示夹具补 11 张已通过单"。若要求存量需求仍能排期，需要一次性补单脚本（另立需求，本轮不做）。
- **F4 权限维度**：假定"发起复用 `req.action.deliver`（`req:deliver`），审批复用引擎的任务归属校验，不新增权限码/动作码/角色"——即当前只有 PMO 与 DEV 能发起，产品经理（PRODUCT）只能审不能提。若要求产品经理也能发起，需要新增动作码并扩权限矩阵，与"权限矩阵零改动"的硬约束冲突，须用户拍板。

**8.2 用户取舍类（taste）**

- **T1 人天变更阈值**：±20%（严格大于）还是"任意改动即需重评"。默认 **±20%**：容忍评审精度内的微调，避免每 0.5 天的修正都重走审批；代价是 2 天的小单改 0.5 天就失效，而 10 天的单改 2 天仍放行。
- **T3 评估卡片放哪**：概览顶部，还是只在审批页签。默认 **概览顶部**（状态与门原因要在动线起点可见），审批页签只放时间线。
- **T4 是否允许发起人自审**：默认 **允许**（沿用引擎现状）；要禁自审得改审批人解析，超出"只加单据类型与节点配置"的约束。

**8.3 已定（本轮用户拍板，不再作为待澄清项）**

- 未通过评估时前端**保留** `schedule`/`start_dev` 按钮并置 `disabled`，`title` 与行内提示写「需先通过工时评估（当前评估单：无/待审核/已驳回/已失效）」，概览卡片保留同一条提示（同一文案来源）；按钮**渲染看权限、禁用看门**，不以 `allowedActions` 当渲染开关。理由是与 r23 拆解门（禁用+原因）、r25 无权限按钮（`需要权限：<码>`）保持同一种交互形态，且"按钮在但点不动 + 为什么"比"按钮消失"更容易理解；`allowedActions` 自身口径不变（仍不返回被门挡住的这两个动作）。详见 §5.7 与 AC-13。

## 9. 验收标准

**AC-1（P0）第五类单据可发起并绑定单个需求**：种子后 `wf_definition` 存在 `biz_type=WBS_ESTIMATE`、`req_type=ANY` 的已发布默认流程 1 条；`POST /api/req/requirements/{id}/estimate`（目标为 PDR 或 RD）→ 201，落 `wf_instance`：`biz_type='WBS_ESTIMATE'`、`biz_id`=需求 id、`status='PENDING'`、当前节点为初审；**需求自身状态不变**；目标为 BUS 或终态（DONE/CLOSED）→ 400 `VALIDATION_FAILED`（`detail.field` 指明 type/status）且不建实例。

**AC-2（P0）提交内容校验**：`estimateDays` 必填、> 0、0.5 天精度（复用既有精度校验，违规错误码 `INVALID_DAY_PRECISION`）；说明可选、≤ 500 字；不合法 → 400 且**不建实例、不写 `estimateDays`**；合法 → 单据记录 `proposedDays`，需求 `estimateDays` 在通过前保持不变（以通过为准）。

**AC-3（P0）初审通过**：审批归属人（域负责人或产品经理，由引擎既有解析产生）同意 → 实例 `APPROVED` + 记录审批人/时间；需求 `estimateDays` 写入单据值（0.5 精度）；详情 `estimate.status=APPROVED` 且含审批人、时间、单据 id；需求动态新增一条 `ESTIMATE_APPROVED`；**需求状态不变**。

**AC-4（P0）驳回**：驳回必须带原因，缺失 → 400 `REASON_REQUIRED`（沿用既有码）；`REJECTED` 后需求人天与状态均不变，详情 `estimate` 块与需求动态都显示驳回原因，可再次提交；审批中心已办可查该单及原因。

**AC-5（P0）唯一性与重提**：同一需求任意时刻 `PENDING` ≤ 1、`APPROVED` ≤ 1；重提时旧 `PENDING` 置 `SUPERSEDED`（不删除、时间线可查）；已 `APPROVED` 且人天未变再提交 → 409 `ESTIMATE_ALREADY_APPROVED`，不建新单；已 `APPROVED` 但偏差 > 20% 再提交 → 允许，新单 `PENDING`，旧单保持 `APPROVED`，门立即回到 `PENDING` 拒绝。

**AC-6（P1）双向可查**：需求详情「审批」页签能展示该需求的评估单（状态、单据人天、节点、时间线、审批人），**不依赖"只看我的待办"**（现有 `listApprovalMine` + `bizType.includes('REQ')` 的过滤必须改）；审批中心能按 `bizType=WBS_ESTIMATE` 筛出待办与已办，标签显示"工时评估"；非归属人调 `POST /api/req/approvals/tasks/{id}/actions` → 403（既有文案"不是你的待办任务"）。

**AC-7（P0）PDR `schedule` 前置门**：无已通过且未失效的评估单 → 409 `ESTIMATE_NOT_APPROVED`，`detail` 恰含 `{requirementId, status, required:"ESTIMATE_APPROVED"}`，`status` 取 NONE/PENDING/REJECTED/SUPERSEDED/STALE 之一；**不落库**（需求状态仍 `PENDING_ANALYSIS`，无 `STATE_CHANGE` 审计）；有 `APPROVED` 单 → 200 且转 `SCHEDULED`。

**AC-8（P0）RD `start_dev` 前置门**：同上（`TODO` 被拦、`APPROVED` 后 200 转 `IN_DEV`），`detail.status` 同样按 6 态返回；被拦时不写状态、不写审计。

**AC-9（P0）驳回/失效/作废一律拦**：`REJECTED`、`SUPERSEDED`、`STALE`、`NONE` 四种情形下 `schedule` 与 `start_dev` 均 409 且 `detail.status` 如实回报（不接受统一回 `NONE`）。

**AC-10（P1）人天变更失效规则**：通过后需求人天被改（`PUT extra.estimateDays` 与 `POST deliver` 两条路径各测一次），`|new−approved|/approved > 20%` → 门判定 `STALE`、`schedule`/`start_dev` 409；幅度 ≤ 20% → 仍放行。边界用 `approvedDays=10`：改 12.0（20%）放行、改 12.5（25%）拦截；再用 `approvedDays=2` 验证改 0.5 天即失效。**清空人天（null）必然 `STALE`**。

**AC-11（P1）`allowedActions` 与门同源**：未评估/在途/驳回/失效时 PDR 详情 `allowedActions` 不含 `schedule`、RD 不含 `start_dev`；`APPROVED` 且授权允许时出现；单测覆盖 6 状态 × 2 动作 = 12 组，且断言"详情给的"与"接口放行的"一致；16 动作矩阵、`sys_action` 72 码、1848 行授权数据逐字不变。**口径边界**：`allowedActions` 仍是"接口会不会放行"的唯一真相，但前端按钮**渲染不看它、只看权限**，禁用与否看门（见 AC-13 / §5.7）——两者不一致时以接口 409 为准，页面必须提前禁用而不是隐藏。

**AC-12（P0）判定顺序与既有语义**：403 先于 409（无写权、无审权、越权数据面各一例）；状态机非法动作仍 409 `ILLEGAL_TRANSITION`（不被新门改写错误码）；r23 父门、拆解门、版本测试门、发版审核门错误码与文案不变（回归断言）；父需求只读汇总与人天汇总口径不变。

**AC-13（P1）概览评估卡片与动作行禁用态**：卡片显示当前评估状态、需求当前人天、评估人天、审批人与时间、驳回/失效原因、门提示；"提交评估 / 重新提交评估"按 r25 规则收敛（渲染 + `:disabled` + `:title="需要权限：<码>"` + handler 首行兜底），无权限账号（vpm1）点击**零请求、零 URL 变化、无 `/403` 页面**。
动作行按 §5.7 落地并断言：**未通过评估时 `schedule`（PDR）与 `start_dev`（RD）按钮仍渲染且 `disabled=true`**，`title` 与内联提示均含"工时评估"且写明当前评估单状态（无/待审核/已驳回/已作废/已失效），点击零请求（若仍触发请求则不得落 /403 页）；**评估通过后同一个按钮变为可用**且可正常发起；权限不足时同一位置的文案是 `需要权限：<码>`、不含"工时评估"字样（两类原因不混用）；卡片门提示与动作行提示逐字一致（同一文案来源）；提交成功或 409 后就地刷新卡片、动作禁用态与 `allowedActions`；`npm run typecheck` 0 错。

**AC-14（P2）审批页签与空态**：评估单在「审批」页签与提报/复核单并列展示，含时间线与结论；空态（无单）、加载中、失败三态齐全且文案为中文；宽度按 UI-STYLE 不横向滚动。

**AC-15（P0）演示夹具**：`DemoFixture` 的 12 条需求中 11 条 PDR/RD（含 `DEMO-R-12` 的 `TODO`）各生成 1 张 `APPROVED` 的 `WBS_ESTIMATE`（发起人=需求负责人，审批人=该域负责人，审批时间早于计划开始），**走同一条落库/状态路径而非绕过引擎直接插状态**；reset 后 `WBS_ESTIMATE` 共 11 张、`PENDING` 0 张；admin 与 vpm1 各走一次"排期 → 开始开发"均 200；连跑两次 `platform-demo-reset.sh` 逐键一致（幂等）。

**AC-16（P0）门禁夹具同步 + 新增负向**：`platform-verify` 中所有 `schedule`/`start_dev` 调用先建评估并通过（或改用已有通过数据），断言只增不减；`platform-test-negative` 新增 ≥ 4 条：未评估 `schedule` 409、未评估 `start_dev` 409、评估被驳回后 `start_dev` 409、无权限者提交/审批 403（403 一行可拆两条，计 ≥4）；总数 ≥ 96；两个脚本重复运行幂等，失败路径不残留脏数据。

**AC-17（P0）门禁不缩水**：`rbac-verify` ≥ 113（不新增权限码，应恰好 113）、`access-verify` ≥ 190（新增评估按钮/门断言 ≥ 6 条，实测 ≥ 196）、`platform-verify` ≥ 174（实测应增）、`platform-test-negative` ≥ 95、`platform-demo-reset` ≥ 66（实测应增）、`unit` ≥ 338（新增门与单据单测 ≥ 12 例）、`lint` ≥ 22、`typecheck` 0、`build` 绿、`mvn -o -DskipTests compile` 0 错；`analytics` 74 与 `collab` s0–s8 不回归。

**AC-18（P1）改动面与文档**：改动限于 approval（新 bizType/种子/摘要/回调）、requirement（门/视图/校验/路由）、web（卡片+页签+审批中心标签+动作禁用态+api）、seed（DemoFixture/DemoResetService）、scripts 与文档；`pom.xml` 与 `web/package.json` diff 为空；`memory.md` 同主题行替换（第五类单据、±20% 规则、动作行"渲染看权限/禁用看门"约定），AGENTS.md 托管区外零改动；evidence ≥ 3 张截图（未评估时按钮禁用+原因、审批中心待办、驳回原因在动态里）+ 409 实测输出与 reset 计数。

## 10. 工程约束

- **分支**：当前已在 `feat/wbs-estimate-approval`，从该分支继续实现与提交；不新建分支、不切 `main`、不 rebase。
- **未提交改动**：`?? docs/teamflow/20261117-r26-wbs-estimate-gate/`（本任务夹，本轮产物）随本轮一并提交；不得 stash、不得丢弃；建议与新代码分两个提交（`docs:` + `feat:`）。
- **每步先编译**：每个任务开始先 `mvn -o -DskipTests compile`（离线、跳过测试），编译不过不进下一步；任务 ≤ 4 个且串行。
- **不扩面**：不做顺手重构、不引入新依赖（`pom.xml` 与 `web/package.json` diff 为空）、不改 r22 动作数据驱动判定机制、不改三档授权与权限矩阵、不改 `StateTables` 语义与需求状态集合、不把 `decompose` 塞进 `allowedActions`。
- **动作行渲染约定（本轮新约定）**：受门约束的动作"渲染看权限、禁用看门"，两类原因文案不混用（权限 → `需要权限：<码>`；门 → `需先通过工时评估（…）`）；不得以 `allowedActions` 作为渲染开关，`allowedActions` 只作为"接口会不会放行"的真相。
- **门禁不缩水**：rbac-verify ≥113、access-verify ≥190、platform-verify ≥174、platform-test-negative ≥92（新增后 ≥96）、platform-demo-reset ≥66、lint/unit/compile/typecheck/build 全绿；`sys_action` 72 码、1848 行授权、16 动作矩阵不变。
- **文档边界**：只写 `docs/teamflow/` 与 `logs/teamflow/<runId>/`；不写宿主 `docs/<role>/`，不在项目根散落日志或一次性脚本。
- **`.gitignore` 审计（开发阶段动作）**：核对 `target/`、`web/node_modules/`、`web/dist/`、`*.log`、`.nacos/`、`logs/teamflow/`、`*.tsbuildinfo`、本地 `.env*` 是否都已覆盖；本轮 evidence 截图放任务夹 `evidence/`，命令输出与临时脚本不入库；发现缺口在 dev 阶段补齐，并在提交说明里写明补了什么、为什么。
- **提交**：`feat:` 前缀描述本需求；不 tag、不 push（未被要求）。

## 11. 里程碑与任务拆分建议

| 里程碑 | 内容 | 出口 |
|---|---|---|
| M1（T1）单据与引擎接入 | bizType 常量、种子流程、提交端点、摘要 Provider、动态事件、单测 | compile 0 错；提交→待办可跑通 |
| M2（T2）评估门与视图 | 判定函数、transition 接入、`allowedActions` 过滤、`estimate` 视图块、409 detail | AC-7/8/9/10/11/12 单测绿 |
| M3（T3）前端 | 概览卡片、审批页签取单、审批中心标签/筛选、api、UI 探针 | typecheck 0；vpm1 点击不落 403 |
| M4（T4）夹具与门禁 | DemoFixture/DemoResetService 补 11 张、platform-verify/negative/demo-reset、access、memory | 全量门禁绿且只增不减 |

顺序固定：T1 → T2 → T3 → T4，不并行（门依赖单据，前端依赖视图块，门禁依赖前三者落地）。
