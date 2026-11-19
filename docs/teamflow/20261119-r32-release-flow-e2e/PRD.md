<!-- meta: summary="把演示夹具补成一条可演完的 PENDING_TEST RD（MR/人天/需求级提报与评审审批真实补记），让 DEMO-RELEASE-FLOW 一次跑到 PUBLISHED，并把端到端与负向被拦写进门禁" -->
基线依赖：docs/teamflow/20261118-r30-release-approval-chain、docs/teamflow/20261118-r31-gate-commit-hygiene（r30 的审批链/准入/快照语义与 r31 的提交区间判据均不得回退）

# r32 · 发布演示全绿跑通（夹具 → PUBLISHED → 门禁）

## 1. 背景与目标

r30 交付了分级审批链、四段链路与不可变快照，`DEMO-RELEASE-FLOW.md` 也按 8 步写成。但 2026-11-19 在 `main=a03be22` 上照文档实跑，第 7 步发布单提交被 `409 ADMISSION_FAILED` 拦住，`failed=[REQ_TEST_PASSED, REQ_APPROVALS_DONE, MR_FILLED, MAN_DAYS_FILLED]`。原因不在代码，而在演示夹具与文档：

- 夹具 12 条演示需求的 `mr_url` 全为空，只有 `DONE` 需求才有实际人天；文档指向的 `DEMO-R-08` 其实也没有 MR；
- 需求级 `REQ_SUBMIT` / `REQ_REVIEW` 从未发起过——准入会沿需求链上溯到第一代有审批实例的需求（`AdmissionEvaluator.approvalLine`），而全库 12 条需求都没有这两类实例；
- 文档 A.3 因此把它写成「这次演示卡在哪」，与 r30 AC-18「正常链真实端到端跑通并留证」的目标不符。

本轮**只补演示数据与门禁，不改业务语义**：让夹具里有一条真能演到底的 RD，文档从「卡在哪」改成「全绿跑通」，并让 `release-flow-verify` 同时覆盖「跑到 PUBLISHED」与「被拦且指名」两种结局。

目标（可验证）：
1. `make platform-demo-reset` 之后，不改库、不手工补字段，照文档 8 步即可一次跑到 `PUBLISHED`；
2. 需求级提报/评审、发布单两级、版本三级的每一级都有真实待办与 `NODE_APPROVED` 留痕；
3. 负向演示仍在：清空 MR 后提交必须 `409` 且 `failed` 恰好指名 `MR_FILLED`；
4. 门禁把上述两结局自动化，且现有门禁只增不减。

## 2. 用户故事与验收标准

### US-1 演示者：reset 完就能开演，不补数据
**AC-1（夹具主角就绪）** `make platform-demo-reset` 后，`DEMO-R-04` 满足：类型 `RD`、状态 `PENDING_TEST`、`release_id` 指向 `DEMO-REL-1`、`mr_url` 非空、`estimate_days=2.0`、`actual_days>0`（1.5–2.0，半天粒度）；这些值经正式 `POST /api/req/requirements/{id}/deliver` 写入，`audit_log` 有对应 `DELIVER`（before/after 含 `mrUrl`）。
**AC-2（需求级审批真跑）** `DEMO-R-01`（`DEMO-R-04` 的 BUS 祖先）的 `REQ_SUBMIT` 与 `REQ_REVIEW` 两个实例均为 `APPROVED`，由真实动作链在种子事务内产生：`submit` → 引擎逐级 `approve` → `review_start` → 引擎 `approve`，净状态回到 `POOLED`（`POOLED` 正是评审通过后的合法状态，见 `RequirementReviewCallback`），全程不直插 `wf_*`：seed 代码对 `wf_instance/wf_task/wf_history` 只读不写，实例带 `definition_id`、任务带真实 `assignee`、`wf_history` 至少 4 条动作。
**AC-3（覆盖与计数）** `DEMO-REL-1` 的测试计划覆盖 `DEMO-R-04` 的至少 1 条用例（`DEMO-C-03/04`）；`scripts/platform-demo-reset.sh` 的断言从 70 增至 ≥74（新增：R-04 就绪、R-01 两单 `APPROVED`、计划覆盖、审批引擎产物形态），`PASS==TOTAL` 守卫保留；`requirements=12 / releases=1 / testCases=12 / planCases=10 / notifications=4 / estimateApproved=11` 等既有口径不降；连续两次 reset 结果一致（幂等）。

### US-2 演示者：8 步一次跑到 PUBLISHED
**AC-4（全绿链路）** 从 reset 态按文档执行，每步返回预期码（创建 201，查询/动作 200），无 4xx/5xx：版本测试（建计划 → 提交评审 → 批准 → 开始 → 执行 PASS → 关闭轮次 → 完成计划）→ `DEMO-R-04` 执行 `test_pass` 到 `TEST_PASSED` → 报告三签 3/3 → 质量门 `PASS` → 建发布单 → 提交。
**AC-5（发布单两级）** 提交返回 200 并生成 `round=1` 快照（`payloadHash` 64 位 hex、`changedSinceSubmit=false`）；`n1` 待办只出现在 tl1/vendor1，批准后出现 `n2`（admin）待办；两级批完 `status=APPROVED`、`levels=APPROVED,APPROVED`，每级写一条 `NODE_APPROVED`（含 `nodeKey/nodeName/mode/actor`）。任一级批准后不得跳级出现下一级之外的待办。
**AC-6（版本三级至 PUBLISHED）** 版本审批 `n1` pm → `n2` tl1/vendor1 或签 → `n3` mgr1；**只有最后一级**批准后 `status=PUBLISHED`、`releasedAt` 非空、三个 level 全 `APPROVED`、`audit_log` 出现 `RELEASE_PUBLISH`；前两级批准后仍为 `LOCKED`/审批中，不得提前发布。
**AC-7（四段同源与快照）** 版本详情 `flowChain` 四段（测试计划/报告三签/质量门/发布单准入）与各自源头一致（计划 `COMPLETED`、三签 3/3、质量门 `PASS`、准入 9 项 0 FAIL），缺段显示 `MISSING`；准入按 `round=1` 可回放，回放内容与提交时一致。

### US-3 演示者：被拦也能演，且指名到项
**AC-8（清空 MR 即被拦）** 在版本 `LOCKED` 后，用 `deliver` 把 `DEMO-R-04.mr_url` 置空再提交：返回 `409 ADMISSION_FAILED`，`failed` **恰好为 `["MR_FILLED"]`**（其余 8 项 PASS），不写新快照、`round` 不增；恢复 MR 后再次提交 200 且 `round=1`。全过程在 A.3 有实测记录。
**AC-9（既有负向不退化）** 未锁定就提交仍 409 `RELEASE_NOT_LOCKED`；紧急/回滚缺原因仍 400；越级审批仍 403 `FORBIDDEN` 且任务仍 `PENDING`、历史无 `APPROVE`；`被拦链` 章节四段仍在。

### US-4 评审者：文档证据可核对
**AC-10（A.3 重写）** `docs/teamflow/DEMO-RELEASE-FLOW.md` 的 A.3 标题与内容改为「全绿跑通」：逐步真实响应码表、两个阶梯的逐级待办与留痕、PUBLISHED 的落库证据（字段值/查询）、以及 AC-8 的负向实测；A.1/A.2/A.4 同步更新（`RD_ID=DEMO-R-04`、`CASE_ID` 属 R-04、删掉「DEMO-R-08 已填 MR/工时」的错误说法）；`## 1.`–`## 8.` 八个步骤标题与 `## 被拦链` 保留（静态检查依赖）。
**AC-11（截图）** `docs/teamflow/demo-shots/` 新增 ≥2 张本轮实测图（PUBLISHED 详情四段链路、409 `failed=[MR_FILLED]`），文档引用路径可打开；不覆盖 r30 旧图。

### US-5 维护者：门禁自动覆盖两结局
**AC-12（S7 与分母）** `scripts/release-flow-verify.sh` 新增 S7 共 8 条断言（夹具就绪 / 测试到 `TEST_PASSED` / 三签 / 质量门 / 提交快照 / 两级批完 / 三级到 `PUBLISHED`+审计 / 负向 409 且无快照），`RELEASE_FLOW_TOTAL` 32→40，`PASS==TOTAL` 守卫与失败即非 0 退出保留；`--static-only` 自检同步（分段 6→7、分母 40、文档检查），`make release-flow-verify` 一条命令可重复。
**AC-13（不缩水、改动面受控）** `platform-verify`、`platform-test-negative`、`access-verify`、`rbac-verify` 断言数不减且全绿；第 32 条提交卫生仍只用 `git merge-base main HEAD`..`HEAD` 的 `diff --name-status --no-renames` 纯区间判据、不读工作树，提交后复跑仍全绿；diff 只允许落在 `seed` 包、`scripts/`、`docs/`、`Makefile` 帮助文本；不新增迁移/表/第 13 条需求，不改审批、准入、快照、状态机代码，不新增放行开关；若必须改业务代码才能走通，停止并上报。

## 3. 范围与非目标

**范围**：演示夹具（`DemoFixture`/`DemoResetService` 的 seed 数据与重置断言）、`DEMO-RELEASE-FLOW.md`、`scripts/release-flow-verify.sh`（新增 S7）、`docs/teamflow/demo-shots/` 截图。

**非目标**：
- 不改 r30 的审批链、准入规则、快照语义、状态机与前端；
- 不接真实 GitLab/Sonar/构建/制品（后三项维持 CLOSED）；
- 不新增需求行、迁移、服务或依赖；不把演示脚本改造成 CI 框架；
- 不改 `DEMO-BUSINESS-FLOW.md` / `DEMO-BY-ROLE.md` 等其他演示文档的既有结论；
- 不解决演示环境的浏览器/外网可用性问题（以证据形态的降级约定处理，见 §10）。

## 4. 反面目标（anti-goals）

技术全绿但仍会被判失败的情形：

1. **仍然要手工补数据**：`make platform-demo-reset` 之后照文档跑到第 7 步，仍在 `409 ADMISSION_FAILED`，或还得先手工 UPDATE 一列 MR/人天才能过准入——那这轮等于没做。
2. **门禁是假绿**：S7 只在当前这台机器、这棵脏工作树上能过；换干净 clone 或提交后复跑就红（例如又依赖 `git status`/未跟踪文件），或反过来恒绿（没真跑 8 步，只回放旧快照）。
3. **为了让链走通而放宽校验**：把 MR 校验改成「非空即可」以外的放行、跳过 `REQ_APPROVALS_DONE`、或直接改状态列绕过状态机——演示绿灯了，但真实单据该拦的拦不住。
4. **文档没有实测证据**：A.3 只把「卡住」换成「已跑通」的措辞，没有逐步响应码、逐级留痕和 PUBLISHED 字段值；读者照文档复现仍然卡住。
5. **负向演示消失或被弱化**：删掉「被拦」一节，或把提交按钮做成不可点来「避免 409」——就无法证明系统真的会拦并指名到项。

## 5. 交互流程摘要

```
reset（夹具已就绪：R-04 = PENDING_TEST，MR/人天齐，R-01 提报+评审已批）
  ↓
[1] 登录 → [2] 确认 R-04 与用例 → [3] 建测试计划并提交评审 → 批准 → 开始 → 执行 PASS → 关闭轮次 → 完成
  ↓
[4] R-04 test_pass → TEST_PASSED
  ↓
[5] 建报告 → 三方签署 3/3 → 质量门 evaluate = PASS
  ↓
[6] 建发布单 → 发起版本审批（n1，版本 LOCKED）
  ↓
[7a] 负向：deliver mrUrl="" → 提交 ⇒ 409 ADMISSION_FAILED failed=["MR_FILLED"]（无快照）
[7b] 恢复 MR → 提交 ⇒ 200 round=1 快照
  ↓
[8] 发布单两级：n1 tl1/vendor1 → n2 admin ⇒ APPROVED,APPROVED
  ↓
[9] 版本三级：n1 pm → n2 tl1/vendor1 → n3 mgr1 ⇒ PUBLISHED + releasedAt + RELEASE_PUBLISH 审计
```

要点：负向插在「版本已 LOCKED」之后、「正式提交」之前，才能既命中准入（而不是先被 `RELEASE_NOT_LOCKED` 挡住），又不破坏后续正常链；清空 MR 用公开 `deliver` 接口、恢复同一接口，不碰数据库。

## 6. 优先级

| 优先级 | 内容 |
|---|---|
| P0 | AC-1/2/3 夹具就绪；AC-4/5/6/7 端到端到 PUBLISHED；AC-8 负向指名；AC-12 门禁覆盖两结局；AC-13 不缩水 |
| P1 | AC-10 文档 A.3 重写与 A.1/A.2/A.4 同步；AC-9 既有负向复核 |
| P2 | AC-11 截图（有环境则 UI 截图，否则按 §10 的降级约定） |

## 7. 依赖与风险

| # | 风险 | 应对 |
|---|---|---|
| R1 | 只有 BUS 能发起需求级审批（`AdmissionEvaluator.approvalLine` 沿父链上溯）；若把主角换成 `DEMO-R-10/11/12`（父 PDR `DEMO-R-09` 无父，链上没有 BUS），准入永远差 `提报:未发起` | 主角固定 `DEMO-R-04`（唯一同时满足「PENDING_TEST」与「挂在 BUS 祖先下」）；不新增第 13 条需求 |
| R2 | 种子里真跑两条审批会产生待办/通知与 `wf_*` 行，可能撞 `notifications=4`、`fixtureTraces=0` 等不变量 | seed 内按真实链路跑完后清理临时通知/待办；断言只许上调不许删除；以「重置后计数」为准而非中途值 |
| R3 | 计划批准人有权限限制（admin/owner；qa1/tl1 403 `PLAN_APPROVE_FORBIDDEN`），文档若沿用旧写法会误导 | 文档写明每步用哪个账号；门禁 S7 用真实账号 |
| R4 | 负向清空 MR 依赖 `deliver` 在 `TEST_PASSED` 仍可写（当前 `requireWritable` 只挡终态） | 若后续加锁导致 409，先上报再定，不改校验绕过 |
| R5 | S7 若复用 `DEMO-REL-1` 或 S2 的发布单，会与既有断言互相污染 | S7 自建发布单/版本，S6 卫生检查置后，`EXIT` trap 复位演示数据 |
| R6 | 截图依赖真实前端与浏览器 | 见 §10：UI 截图优先，环境不可用时用响应+落库证据图并在文档注明 |

## 8. 里程碑建议

1. **M1 夹具**：seed 补 MR/人天 + 真实补记两条需求级审批；`platform-demo-reset` 断言 70→≥74，两次 reset 幂等。
2. **M2 链路**：按 8 步实跑到 `PUBLISHED`，记录逐步响应码与逐级待办/留痕；做一次负向（清空 MR）与恢复。
3. **M3 文档与证据**：A.3 重写、A.1/A.2/A.4 同步、新增截图，保留「被拦链」。
4. **M4 门禁**：新增 S7（8 条）、分母 32→40、静态自检同步；`platform-demo-reset` 计入新断言。
5. **M5 回归**：六条门禁全绿 + 提交后复跑（merge-base 区间判据），提交信息含 runId 与任务夹。

## 9. 工程约束

- 真实走审批引擎，**不直插 `wf_*` 表**；RD 若走不到 `PENDING_TEST` 需先补前置，按真实动作链路走，**不许绕过门禁或改状态机**。
- **不改动 r30 的审批链/准入/快照语义**；若发现必须改业务代码才能走通，先停下来说明，不要偷偷放宽校验。
- 门禁不缩水：现有 32 条与 `platform-verify` / `platform-test-negative` / `access-verify` / `rbac-verify` / `platform-demo-reset` **只增不减**。
- 判据用 `merge-base(main, HEAD)..HEAD` 区间，不依赖脏工作树（r31 约定）。
- 分支与提交：留在 `feat/demo-release-green`；当前唯一未提交改动是本任务夹 `docs/teamflow/20261119-r32-release-flow-e2e/`；交付前提交，提交信息含 runId `tf-mwmvwpj7-6kmkas` 与任务夹名（沿用 r30/r31 约定）。
- `.gitignore` 复核（dev 阶段动作）：本项目为 Maven 多模块 + `web/` 前端，现有规则已覆盖 `target/`、`.nacos/`、`logs/`、`node_modules/`、`dist/`、`.vite/`、`.pnpm-store/`、IDE、`*.log`、`.env*`、`.DS_Store`；本轮确认 `docs/teamflow/demo-shots/*.png` **必须入库**（不得被忽略）、`logs/teamflow/` 仍忽略、不产生新的迁移文件；如出现新产物（如测试截图临时目录）按同族规则补一条并在提交说明里点明。

## 10. 假设与待澄清

- **演示 MR 链接用示例地址**（假设）：准入只判非空，环境无外网 GitLab，默认 `https://git.example.com/mr/r32-e2e`；若用户要求可点击到真实仓库，需要提供内网地址，届时只改 seed 常量与文档。
- **负向演示选「清空 MR」**（口味）：另两种同样可行是「报告未签就提交」与「未锁定就提交」，但前者需重跑一轮报告、后者会先被 `RELEASE_NOT_LOCKED` 挡住而演示不到准入；清空 MR 能精确命中 `failed=["MR_FILLED"]` 且可无损恢复。默认按清空 MR。
- **证据形态**（口味）：默认两张图都用真实前端页面；若当前环境无可用浏览器，则退化为「接口响应 + 落库查询」的证据图，并在 A.3 注明是接口证据而非 UI 截图——不伪造界面。
