# QA 报告 · r28 负向门禁第 7/8 段自带夹具（总数恒定 130）

- 任务夹：`docs/teamflow/20261117-r28-negative-gate-fixture/`（本夹无 PRD.md，验收契约以 `TECH-CHANGE.md` §5「回归与验证计划」为准）
- runId：`tf-mwkj8oj8-hoo3ee` ｜ 分支：`feat/negative-gate-fixture` ｜ HEAD：`7144d30`
- 测试时间：2026-11-17 18:37–18:52（Asia/Shanghai）｜ 测试人：QA
- 交付形态：脚本门禁修复 + 产品记忆行订正（**无 UI、无业务 Java、无 pom/package.json 改动**）
- **结论：可验收**（20 条用例全 PASS，未发现缺陷）

## 1. 测试范围与环境

- 被测改动（`git status --porcelain` 仅 3 项）：
  1. `scripts/platform-test-negative.sh`：+52/−9，仅第 7/8 段 `:423-481` 重写 + 文件头 r28 注释一行；
  2. `docs/teamflow/memory.md`：1 行插入/1 行删除（门禁基线行行尾订正，历史句未动）；
  3. `docs/teamflow/20261117-r28-negative-gate-fixture/`（本任务夹）。
- 环境：本机真栈（网关 `:8080` → `req-service:8082` / `deliver-service:8083`，MySQL 种子库），管理员与 `biz1`/`pm` 账号登录均 200；Nacos/DB 为 `make platform-up` 起的开发栈，测试前后均以 `bash scripts/platform-demo-reset.sh`（69/69）复原。
- 不在范围：Java 业务逻辑、前端构建产物、`platform-verify.sh` / `access-verify.sh` 语义（本轮均未改，仅作回归）。
- 浏览器：沙箱内不可启动（宿主限制），本轮无 UI 变更，视觉类检查不适用（见 §5）。

## 2. 用例与结果

| 编号 | 用例（对应 `TECH-CHANGE.md` §5 行） | 命令 | 结果 |
|---|---|---|---|
| R28-T1 | 演示数据基线可复原 | `bash scripts/platform-demo-reset.sh` | PASS 69/69 |
| R28-T2 | 干净重置态：negative 130/130（第 1 次） | `bash scripts/platform-test-negative.sh` | PASS 130/130 |
| R28-T3 | 干净重置态：negative 130/130（第 2 次，reset 后实测 pm pending=0） | 同上 | PASS 130/130，夹具 BUS `610031` 终态 `PENDING_REVIEW` |
| R28-T4 | 有遗留待办态：仍 130（第 1 次，运行前 pm pending=1） | 同上 | PASS 130/130 |
| R28-T5 | 有遗留待办态：仍 130（第 2 次，运行前 pm pending=1） | 同上 | PASS 130/130 |
| R28-T6 | 连跑两次同结果（T3→T5，中间不 reset） | 同上 ×2 | PASS 130/130、130/130 |
| R28-T7 | 不再出现静默跳过文案 | `grep -cE "无待办任务\|跳过减签" <日志>` | PASS 命中 0 |
| R28-T8 | 变异自检必红（夹具 submit 置空） | 副本内改第 438 行为 `:` 后运行 | PASS exit 1，128 PASS + 2 FAIL，失败行「7) 夹具 BUS submit 失败」「8) 无夹具 taskId，加签负向未执行」 |
| R28-T9 | 计数账静态复核（夹具不计断言） | `bash logs/teamflow/tf-mwkj8oj8-hoo3ee/scripts/r28-negative-gate-static-check.sh` | PASS 7/7（段内 pass/allow/warn 调用 0、deny 恰 2、fail 兜底 8 处、精确选择器、无 `.id` 回退、memory 含订正） |
| R28-T10 | platform-verify 下限 ≥193 | `bash scripts/platform-verify.sh` | PASS 193/193 |
| R28-T11 | access 下限 ≥206 | `bash scripts/access-verify.sh` | PASS 206/206（a7 16/16） |
| R28-T12 | rbac 基线 113 | `bash scripts/rbac-verify.sh` | PASS 113/113（s9 8/8、s2r 11/11） |
| R28-T13 | lint 22 | `bash scripts/tests/lint.sh` | PASS 22/22 |
| R28-T14 | 单测 ≥371 | `bash scripts/platform-unit.sh` | PASS 371/371 |
| R28-T15 | web typecheck + build 绿 | `cd web && npm run typecheck && npm run build` | PASS exit 0，`built in 6.65s` |
| R28-T16 | 改动面与仓库卫生 | `git status --porcelain`、`git diff --numstat`、`git diff --check` | PASS 仅 3 项预期改动；memory 1/1、脚本 52/9；无空白错误；无构建产物/依赖/密钥噪声 |
| R28-T17 | 数据副作用可控 | reset 后 `db_q` 查 pm pending | PASS 0；每次运行仅多一张 `neg-r28-bus-*`（与 4d/16 段夹具同性质） |
| R28-T18 | 架构符合性（蓝图对照） | 见 §3 | PASS |
| R28-T19 | 跨模块接口一致性（静态） | 见 §3 | PASS |
| R28-T20 | 失败语义：夹具失败即红 | 见 R28-T8 + 静态 `fail` 兜底 | PASS |

失败/阻塞：0 / 0。

## 3. 架构符合性与接口一致性（交付质量门）

**蓝图对照（6 点全部符合）**

1. 未新增模块/抽象：改动收敛到单脚本一段 + memory 一行，`platform-verify.sh`、`access-verify.sh`、业务 Java 零改动（`git diff --name-only` 仅 2 文件）。
2. 只走统一底座：夹具新建需求/提交/取待办/减签/加签全部经 `scripts/lib.sh` 的 `api_call`，段内无 `curl`/`mysql`/`db_q` 自拼（grep 0 命中），错误码与 `X-Tenant-Id` 语义不分叉。
3. 不引入跨段共享夹具变量：`R28_BUS`/`R28_TASK` 均为段内局部；段末 `API_TOKEN="$ADMIN_TOKEN"` 复位，不污染后续段。
4. 取任务用精确选择器 `[.items[] | select(.bizId == ${R28_BUS} and .bizType == "REQ_SUBMIT")][0].taskId`（`scripts/platform-test-negative.sh:456`），与 4d.6（`:358`）同形，未沿用 `.items[0].taskId // .items[0].id` 回退。
5. 重复的 jq 选择器（`:212/:240/:308` WBS_ESTIMATE，`:358`/`:456` REQ_SUBMIT）按蓝图**刻意不抽公共函数**：抽函数会改动 r27 4d 的 19 条断言与 130 基线，回归面大于收益。判为可接受的既有技术债（非本轮缺陷），后续抽 `todo_task_id(biz_id,biz_type)` 时应一次性替换三处——已记录在案。
6. 无重复实现漂移、无破坏既有结构；计数/汇总/退出逻辑（`:931-936`）未动。

**接口一致性（静态，文件:行）**

| 调用点 | 目标契约 | 核验 |
|---|---|---|
| `POST /api/req/requirements` | `RequirementController.java:104-114` 返回 201 `Created(long id,…)`（`RequirementViews.java:59`） | 一致 |
| `POST /api/req/requirements/{id}/actions` `{"action":"submit"}` | `RequirementController.java:131` 返回 `TransitionResult` 200 | 一致 |
| `GET /api/req/approvals/todo?page=1&size=200` | `ApprovalController.java:36` `PageSlice<ApprovalViews.TaskRow>`；`JpaApprovalStore.java:175-181` size 上限 200、按 id DESC | 一致 |
| 待办行 `.taskId` | `ApprovalViews.java:107` `record TaskRow(long taskId, …, String bizType, long bizId, …)` | 一致（字段名 taskId，非 id） |
| `POST …/tasks/{taskId}/remove-sign {targetUserId,reason}` | `ApprovalController.java:95`（`RemoveSignRequest(targetUserId,reason)`）；`ApprovalEngine.java:278-281` 先判 `reason` 空 → 400 `REASON_REQUIRED`（先于加锁） | 一致 |
| `POST …/tasks/{taskId}/add-sign {type,userIds,comment}` | `ApprovalController.java:89`（`AddSignRequest`）；`ApprovalEngine.java:226-241` 先 `assertAssignee` 后 `!task.isPending()` → 409 `TASK_ALREADY_HANDLED` | 一致，且判据与蓝图一致 |
| `POST …/tasks/{taskId}/actions {"action":"APPROVE"}` | `ApprovalController.java:73` 返回 `Map(taskId,instanceStatus,nodeStatus)` 200 | 一致 |

结论：蓝图声明的接口/字段/错误码全部存在且形状匹配，无「文件存在但调用不到」的 P1 级断裂。

## 4. 缺陷表

未发现缺陷（P0/P1/P2/P3 = 0/0/0/0）。

说明：本轮无 UI 改动，craft 评分（设计稿对照的视觉/交互打磨）不适用，故不产生 P3 观察项；架构侧的唯一技术债（选择器三处重复）是蓝图明确接受的历史取舍，非本轮引入，不计缺陷。

## 5. 人工补测清单（环境限制，非交付缺陷）

| 项 | 原因 | 建议方法/工具 |
|---|---|---|
| 浏览器截图/视觉回归 | 沙箱内浏览器不可启动（宿主 token 限制，`--no-sandbox` 等无效）；且本轮 diff 仅脚本 + memory，无任何 UI 变更 | 不适用；如需人工复核，`make platform-up` 后 `bash scripts/platform-test-negative.sh` 观察 130/130 与第 7/8 段两条 PASS 即可（非视觉项，已自动覆盖） |
| 计数账「夹具不得计断言」的长期护栏 | `TOTAL` 是人工计数器，无运行时机制强制；本轮靠静态门禁脚本（R28-T9）守 | 评审时对 `:423-481` 复核；后续如抽公共函数，把该静态检查纳入 `make lint` 的候选改进 |

## 6. 结论

**可验收。** r28 把第 7/8 段从「环境恰好有待办才断言」改为段内自造 `REQ_SUBMIT/BUS` 夹具，干净重置与有遗留待办两态各跑两次均为 **130/130**，两条断言（`400 REASON_REQUIRED`、`409 TASK_ALREADY_HANDLED`）每次都真实执行；变异夹具后门禁立即变红（exit 1，2 FAIL 指向第 7/8 段），证明不再被环境吞掉。回归下限（verify 193、access 206、rbac 113、demo-reset 69、lint 22、unit 371、web typecheck+build）全部达标，改动面与仓库卫生符合契约。环境已 `platform-demo-reset`（69/69）复原；代码保持未提交，由验收阶段决定提交。
