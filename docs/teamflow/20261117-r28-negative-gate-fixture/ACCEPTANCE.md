# 验收报告 · r28 负向门禁第 7/8 段自带夹具（总数恒定 130）

- 任务夹：`docs/teamflow/20261117-r28-negative-gate-fixture/`（ADR-0008，建后不可变）
- runId：`tf-mwkj8oj8-hoo3ee` ｜ 分支：`feat/negative-gate-fixture` ｜ HEAD：`7144d30`
- 验收时间：2026-11-17 18:46–19:05（Asia/Shanghai）｜ 验收人：产品经理（验收）
- 交付形态：门禁脚本修复 + 产品记忆行订正（无 UI、无业务 Java、无 pom/package.json 改动）
- 验收依据：本任务夹无 `PRD.md`（上游既有缺口），契约取 `TECH-CHANGE.md` §5「回归与验证计划」+ 本轮需求原文与架构蓝图

## 1. 结论摘要

r28 的目标是消除负向门禁的「环境依赖」：第 7/8 段从「pm 恰好有待办才断言」改为段内自造 `REQ_SUBMIT/BUS` 夹具，两条断言恒定执行，夹具步骤不计入 TOTAL，总数在干净重置与有遗留待办两态恒为 130，夹具失败一律 `fail` 而非 `warn` 静默缩水。验收复跑证实上述目标全部达成，未发现 P0–P3 缺陷，架构符合蓝图，同意通过。

## 2. AC 逐条核对

| # | 验收判据 | 证据 | 结论 |
|---|---|---|---|
| A1 | 第 7/8 段自带夹具，不再依赖环境待办 | 静态：`scripts/platform-test-negative.sh:423-481` 段内 biz1 建 BUS→submit→按 bizId+bizType 取 taskId，无 `warn` 跳过分支 | PASS |
| A2 | 干净重置（demo-reset 69/69）后 negative 130/130 | 本轮 `bash scripts/platform-demo-reset.sh` → 69/69；随后 negative → 130/130，`减签空原因 → 400 REASON_REQUIRED`、`已处理任务加签 → 409 TASK_ALREADY_HANDLED` 两行 PASS | PASS |
| A3 | 两态/连跑总数恒定 130，不得 131/132 | 本轮干净态 2 次、`platform-verify` 之后 1 次，均 130/130；QA 另在「pm pending=1」的遗留待办态连跑 2 次均 130/130 | PASS |
| A4 | 日志不再出现「无待办任务，跳过减签/加签负向」 | 本轮每次运行 grep 该文案命中 0；脚本内已无该 `warn` 分支 | PASS |
| A5 | 夹具不计入 TOTAL | 静态复核 `:423-481`：`pass`/`allow`/`warn` 调用 0（仅注释出现字样），`deny` 恰 2 条，其余为 `api_call`/`fail` 与纯 shell 判断 | PASS |
| A6 | 夹具任一步失败即门禁变红，不被环境吞掉 | 代码路径：建单/submit/登录/取 taskId/approve 非 200 均 `fail`，由 `PASS != TOTAL`（`:931-933`）退出 1；QA/dev 变异实测 exit 1、128 PASS + 2 FAIL 且失败行指向 7/8 段，还原后 sha256 一致 | PASS |
| A7 | 两条断言的判据一字不改 | 本轮 PASS 原文：400 `REASON_REQUIRED`（`ApprovalEngine.removeSign` 先校验原因）与 409 `TASK_ALREADY_HANDLED`（`addSign` 判 `!task.isPending()`），仅前置数据改为自造 | PASS |
| A8 | 取任务用 bizId+bizType 精确选择器，不沿用 `.id` 回退 | `:456` 为 `[.items[] | select(.bizId == ${R28_BUS} and .bizType == "REQ_SUBMIT")][0].taskId`；全文件无 `.id //` 回退 | PASS |
| A9 | 段末复位身份，不污染后续段 | 第 7/8 段末 `API_TOKEN="$ADMIN_TOKEN"`，与相邻段一致 | PASS |
| A10 | `platform-demo-reset` 69/69 | 本轮复跑 69/69（含 11 角色 × 168 授权格、自定义角色 0 行） | PASS |
| A11 | `platform-verify` ≥193 | 本轮 193/193（含需求池 p95 0.005s ≤ 1s） | PASS |
| A12 | `access` ≥206 | 本轮重置后 206/206（八段齐全） | PASS |
| A13 | `rbac` 113 | 本轮 113/113（11 段，`s2r` 11/11） | PASS |
| A14 | `lint` 22 | 本轮 22/22 | PASS |
| A15 | `platform-unit` ≥371、web typecheck+build 绿 | 沿用 QA/dev 本轮证据（371/371；`built in 6.65s`，exit 0）；本轮未复跑，改动不触及 Java/前端 | PASS（证据） |
| A16 | 改动面：仅脚本一段 + memory 一行 + 任务夹；pom/package.json 零 diff | `git diff --name-only` 仅 `docs/teamflow/memory.md`、`scripts/platform-test-negative.sh`（52/9）；`pom.xml`/`package.json` 无 diff | PASS |
| A17 | 仓库卫生：无构建产物、依赖、日志或密钥噪声 | 验收前后 `git status --porcelain` 均只有 3 项（M memory.md、M 脚本、?? 任务夹）；`git diff --check` exit 0 | PASS |

## 3. 架构一致性检查（M3 质量门）

- **符合蓝图（6/6）**：① 未新增模块/抽象，改动收敛到单脚本一段 + memory 一行；② 夹具只走 `scripts/lib.sh` 的 `api_call`，段内无自拼 `curl`/`mysql`，错误码与 `X-Tenant-Id` 语义不分叉；③ 无跨段共享夹具变量（`R28_BUS`/`R28_TASK` 段内局部），段末 token 复位；④ 取任务用精确选择器，未沿用 `.items[0]` 与 `.id` 回退；⑤ jq 选择器第 3 处重复按蓝图**刻意不抽公共函数**，避免触碰 r27 的 19 条断言与 130 基线；⑥ 计数/汇总/退出逻辑（`:931-936`）未动。
- **偏差**：无结构性偏差。蓝图写的改动点 `:423-435` 是旧段行号，实际重写后该段延伸至 `:423-481`，属同一段落的自然行宽，不是范围扩大。
- **重复实现/适配漂移/既有结构破坏**：未发现。未新增第二套判定或第二套 HTTP 客户端。
- **技术债（蓝图已明确接受，非本轮引入）**：① `REQ_SUBMIT` 选择器共三处，后续统一抽 `todo_task_id(biz_id, biz_type)` 一次性替换；② `TOTAL` 是人工计数器，无运行时机制强制「夹具不计数」，本轮由静态复核脚本 + 评审守（见遗留 L2）。
- **判定影响**：不构成「偏离蓝图 / 重复实现 / 应抽未抽」的有条件通过项，功能与结构均达标。

## 4. 本轮验收复跑证据

| 命令 | 结果 |
|---|---|
| `bash scripts/platform-demo-reset.sh` | exit 0，69/69 |
| `bash scripts/platform-test-negative.sh`（重置后，第 1 次） | exit 0，130/130；7/8 段两行 PASS，无跳过文案 |
| `bash scripts/platform-verify.sh` → `bash scripts/platform-test-negative.sh`（第 2 次） | 193/193；后接 negative 130/130（总数未漂移） |
| `bash scripts/platform-demo-reset.sh` → `bash scripts/access-verify.sh` | 69/69；206/206 |
| `bash scripts/rbac-verify.sh` | 113/113 |
| `bash scripts/tests/lint.sh` | 22/22 |
| 最终 `bash scripts/platform-demo-reset.sh` → `bash scripts/platform-test-negative.sh`（第 3 次） | 69/69；130/130，`减签空原因 → 400 REASON_REQUIRED`、`已处理任务加签 → 409 TASK_ALREADY_HANDLED` |
| `git status --porcelain` / `git diff --check` | 仅 3 项预期改动；exit 0 |

数据已由最后一次 `platform-demo-reset`（69/69）复原；代码保持未提交，由提交流程处理。

## 5. 意见与遗留（均非阻断）

- **L1 任务夹缺 `PRD.md`**：上游既有缺口（TECH-CHANGE 已声明），本轮未扩围补写；验收以 `TECH-CHANGE.md` §5 与需求原文为契约，建议后续需求确保 PRD 落夹。
- **L2 `TOTAL` 人工计数器无运行时护栏**：本轮由静态复核（`:423-481` 无 `pass`/`allow`/`warn`、`deny` 恰 2）与评审守；建议后续把该静态检查纳入 `make lint` 候选。
- **L3 选择器三处重复**：`REQ_SUBMIT` 于 `:212/:240/:308`（WBS_ESTIMATE）与 `:358/:456` 同形，蓝图明确接受；后续抽 `todo_task_id()` 时一次性替换。
- **L4 `Makefile:55` 帮助文案仍写「15 条负向断言」**：历史文档漂移，按蓝图本轮不动，建议后续单独订正，避免混入功能修复。
- **L5 每次运行多留一张 `neg-r28-bus-*` 需求**：与既有 4d/16 段夹具同性质，`platform-demo-reset` 可清场，已核实不影响后续段的 before/after 差值断言。
- **L6 夹具 `targetUserId`/`userIds` 固定为 1**：判据（空原因、任务已处理）先于用户校验，实测无影响；若日后调整审批引擎判定顺序需同步复核。

验收结论：✅ 通过
