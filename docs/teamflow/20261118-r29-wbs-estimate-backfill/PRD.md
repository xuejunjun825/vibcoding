# PRD · 存量工时评估回填（r29）

- **需求编号**：20261118-r29-wbs-estimate-backfill
- **基线依赖**：`docs/teamflow/20261117-r26-wbs-estimate-gate`（工时评估门禁：没有 `APPROVED` 评估单的 PDR/RD 不能排期/开始开发，`409 ESTIMATE_NOT_APPROVED`）
- **取代声明**：**不取代任何历史 AC**。r29 只在 r26 门禁之外补一条运维回填通道，r26 的六态判定、±20% `STALE` 阈值、`409` 三键契约、判定顺序、状态机与 schema 全部照旧（见 §5 AC-7）。
- **降级说明（可追溯性）**：本轮 tech 阶段只产出 `TECH-CHANGE.md`（技术变更单），未产出 PRD，导致 AC 编号无法回溯。本文件由开发阶段依宿主运行记录 `tf-mwlztf64-ylus7r` 的 `requirement` + `requirementSupplement`（8 条原文要求）**忠实复原**，编号自 `AC-1` 起；`TECH-CHANGE.md` §5.1 的 S1–S6 十八组断言即这些 AC 的机械断言，两者一一对应，不再另立口径。

## 1. 问题

r26 上线后，判断「能否排期/开始开发」的前置条件是**存在一张 `APPROVED` 的 `WBS_ESTIMATE` 单**。存量环境里大量 PDR/RD 从没走过评估流程（r26 之前无此门禁），因此：

- 它们被新门永久卡死，`schedule`／`start_dev` 恒 409；
- 唯一能让它们通过的路径是 `make platform-demo-reset` 的**演示夹具**（`DemoEstimateFixture`），那是给演示环境造数据的，不能对真实存量跑；
- 人工逐条在页面上重走「提交评估 → 审批」，量与批次不可控，且没有「跑了哪些、成功哪些、失败原因」的可审计记录。

需要一条**可重复执行、可先看后做、按批次可审计**的一次性运维回填能力。

## 2. 用户与场景

| 角色 | 场景 |
|---|---|
| 平台运维 / 交付工程师 | 存量环境升级到 r26 后，一次性给存量未评估需求补单；必须先 dry-run 出计划、备份、再 apply |
| 研发负责人（域负责人） | 回填产生的评估单需要真实审批痕迹（谁批的、什么时候、意见是什么），而不是数据库里凭空多出的 `APPROVED` 行 |
| 审计 / 事后核对 | 给定 `batchId` 能列出本次回填命中的全部需求与单据 |

## 3. 范围

**做**：只读/写入两态运维入口 + 薄运维脚本 + 自声明门禁脚本 + dev 栈开关与 make 接线 + RUNBOOK 运维口径。

**不做**：不改六态判定/±20%/`409` 三键/状态机/终态集，不加 schema，不做反向撤销脚本，不改演示夹具，不给审批引擎加「管理员代批」语义，不做分布式互斥。

## 4. 需求条目（源自 requirementSupplement 8 条，逐条保留）

- **R1 运维入口与脚本**：交付 `scripts/backfill-wbs-estimate.sh`（薄封装），背后是一个**只读/写两态**的运维入口（`/internal` 或独立 `main`，选型必须写清理由）；**默认 dry-run**，只有 `--apply` 才写库。
- **R2 选取口径**：只补「类型为 PDR/RD、非终态、且没有 `PENDING`/`APPROVED` 评估单」的需求；`BUS` 一律跳过。人天取 `requirement.estimateDays`，为空或 `<= 0` → 跳过并注明原因码 `NO_ESTIMATE_DAYS`，**不得臆造人天**。
- **R3 走真实审批引擎**：必须通过 `ApprovalEngine` 生成 `APPROVED` 单（发起 + 完成任务），**禁止 JDBC 直插 `wf_instance`/`wf_task`**；单据说明与审批意见统一为「存量回填（runId/批次号）」；审批人取域负责人，解析不出回退平台管理员并打 `FALLBACK_APPROVER` 标记。
- **R4 幂等与可重跑**：二次运行 `planned=0`、不新增单据、不新增 `wf_history`。
- **R5 报告契约**：报告每行至少含 需求编号/类型/状态/原人天/评估单号/结果(`SKIPPED|CREATED|FAILED`)/原因码；单条失败不中断整批；出现 `FAILED` 时进程退出码非 0。
- **R6 门禁脚本**：新增 `scripts/wbs-backfill-verify.sh` 并接入 make 目标，至少覆盖 ① dry-run 不改库 ② apply 后 `schedule`/`start_dev` 不再 409 ③ 再跑 0 变更 ④ BUS 未补单 ⑤ 人天空标 `NO_ESTIMATE_DAYS` 且仍被拦 ⑥ 有 `FAILED` 时退出码非 0；**禁止只数「总数 > 0」**。
- **R7 不回归**：`platform-verify ≥193`、`platform-test-negative ≥130`、`access ≥206`、`rbac 113`、`platform-demo-reset 69`、`unit ≥371`、`lint 22`、web `typecheck+build`；不改六态/±20%/409 三键/判定顺序/状态机，不加 schema。
- **R8 运维文档**：`RUNBOOK.md` 写前置备份、dry-run 读法、apply 窗口、失败重试与回滚口径、按批次号可查。

## 5. 验收标准（AC）

每条 AC 都给**可机械复算**的判据；括号内是 `TECH-CHANGE.md` §5.1 的断言组与 `scripts/wbs-backfill-verify.sh` 的组号。

| AC | 内容 | 判据（含断言组） |
|---|---|---|
| **AC-1** | 双态入口与开关（R1） | 开关未开 → `POST /internal/wbs-estimate-backfill` 返回 **404 `BACKFILL_DISABLED`**；dry-run 报告 `mode=dry-run`；dry-run 前后 `wf_instance` / `wf_history` **逐表计数不变**（S1 契约 4 条） |
| **AC-2** | 选取口径（R2） | `BUS` 需求**不产生任何单据**（S4 2 条）；人天空/`<=0` 的需求落 `SKIPPED/NO_ESTIMATE_DAYS` 且门禁**仍然 409**（S5 2 条）；终态与已有 `PENDING`/`APPROVED` 单的行落 `SKIPPED/TERMINAL`、`HAS_ACTIVE_ESTIMATE`、`HAS_APPROVED_ESTIMATE` |
| **AC-3** | 真实审批链路（R3） | apply 后该需求存在 `APPROVED` 的 `WBS_ESTIMATE` 单，且 `wf_history` 有 `SUBMIT`＋`APPROVE` 两条流水，`detail_json` 与 `comment` 均含同一 `batchId`；无任何直插 `wf_*` 代码路径（S2 生效 5 条，含 `schedule`/`start_dev` 不再 409） |
| **AC-4** | 幂等（R4） | 同 `batchId`/不同 `batchId` 连跑两次：第二次 `planned=0 && created=0`，`wf_instance`、`wf_history` 计数不变（S3 3 条） |
| **AC-5** | 报告契约与失败语义（R5） | 每行七键齐全（`code`/`type`/`status`/`estimateDays`/`instanceId`/`result`/`reason`，**值为 null 时键也要在**）；有 `FAILED` 仍返 200 完整报告；脚本落退出码 `1`（有 FAILED）/`2`（用法或环境错）/`3`（传输失败），全成功 `0`；单行失败不影响其余行（S1 七键 + S6 失败语义 2 条） |
| **AC-6** | 门禁自声明（R6） | `make wbs-backfill-verify` 自声明 `WBS_BACKFILL_TOTAL=18`，实跑 `TOTAL_CHECKS` 必须等于该数（少跑即红）；六组 S1–S6 覆盖 R6 的 ①②③④⑤⑥ |
| **AC-7** | 不回归（R7） | §5.2 各门禁复跑达到 R7 的下限；`git diff` 中六态判定、±20%、`409` 三键、状态机、迁移脚本**零改动** |
| **AC-8** | 运维文档（R8） | `RUNBOOK.md` 覆盖：备份（`requirement`/`wf_instance`/`wf_task`/`wf_history`）、dry-run 报告读法、apply 窗口与开关、失败重试（同 `batchId`）、回滚口径（只增不撤，禁 `UPDATE`/`DELETE` 单据）、按 `batchId` 反查 SQL |

## 6. 假设与待拍板

已落地假设（技术阶段定稿，`TECH-CHANGE.md` §7）：

1. 入口选 `/internal/**`：只有容器内才有 `TenantContext`/`DataScope` 租户绑定与 `@Transactional` 引擎，独立 `main` 只能直插 `wf_*`（违反 R3）。
2. 审批人冲突落地为「域负责人优先 → 否则该实例真实待办审批人」＋`FALLBACK_APPROVER`；**不改** `ApprovalEngine.assertAssignee` 的禁代批语义（改它要动 `WBS_ESTIMATE` 或签种子与 r26 流程快照，另计回归面）。
3. `STALE` 自动补单并 supersede 旧单。
4. `NO_ESTIMATE_DAYS` 不代填、`BUS` 不补单。
5. 放弃分布式互斥，靠 active 单唯一约束 + 引擎 409 兜底（`FAILED/CONCURRENT_ACTIVE`）。

仍开放：

- **§7-2 管理员代批**：若要「回退平台管理员并真由管理员批准」，需在 `WBS_ESTIMATE` 或签加 `USER:admin` 节点（改种子、动 r26 流程快照）。当前实现只把管理员作为**发起人**，批准仍由实例真实待办完成。
- `audit_log` 反查批次号：需先在 web 事件字典补 `ESTIMATE_BACKFILLED` 文案（否则页面显示英文裸码），本轮未做，批次审计暂只走 `wf_history`（见 `RUNBOOK.md` §3 步骤 2 订正）。
