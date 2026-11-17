# 技术变更单 · r28 负向门禁第 7/8 段自带夹具（总数恒定 130）

- 任务夹：`docs/teamflow/20261117-r28-negative-gate-fixture/`（ADR-0008，建后不可变）
- 类型：tech-driven rework（门禁自愈，无用户可见功能变更）
- 基线：r27 任务夹 `docs/teamflow/20261117-r27-allowed-actions-parity`（memory 门禁基线行订正于 r27）
- runId：`tf-mwkj8oj8-hoo3ee`；分支：`feat/negative-gate-fixture`

## 1. 背景与目标

一句话：让 `scripts/platform-test-negative.sh` 第 7/8 段自己造出「pm 名下有 PENDING 审批任务」的前置数据，去掉条件执行，使断言总数在**干净重置**与**有既有待办**两种环境下恒定为 **130**。

根因（实测证据）：`scripts/platform-test-negative.sh:423-435` 先 `login_as "pm"` 再取 `/api/req/approvals/todo` 的 `.items[0]`，仅当拿到 taskId 才跑两条断言，否则 `warn "无待办任务，跳过减签/加签负向"`。`make platform-demo-reset`（69/69）后 11 张工时评估单均为 APPROVED、无在途审批任务，pm 待办为空 → 实跑 **128/128 PASS**，而报告与 `docs/teamflow/memory.md` 记的是 **130**。该 130 只在「环境里恰好有在途待办」时成立，属门禁静默缩水。

## 2. 影响面

| 文件 | 改动 |
|---|---|
| `scripts/platform-test-negative.sh` | 仅第 7/8 段（`:423-435`）重写为自带夹具；文件头注释加一行 r28 说明；计数/汇总/退出逻辑（`:931-936`）不动 |
| `docs/teamflow/memory.md` | 同主题行（门禁基线行）行尾追加 r28 订正：第 7/8 段自带夹具、总数恒定 130、失败必须 `fail` |
| `docs/teamflow/20261117-r28-negative-gate-fixture/` | 本变更单 + 后续 QA/验收留痕 |

不改：`scripts/platform-verify.sh`、`scripts/access-verify.sh`（语义不动）、任何业务 Java、`pom.xml`、`web/`。`Makefile` 无需改（`make platform-test-negative` 只是薄封装）。

## 3. 技术方案（要点）

1. **夹具路线（选定）**：段内以 `biz1` 建一张 BUS 并自服务 `submit`，走默认 `REQ_SUBMIT/BUS` 流程（审批人 = `PRODUCT` 会签，pm 可见），再从 pm 的审批待办按 `bizId + bizType=REQ_SUBMIT` 精确取真实 `taskId`。
   - 建单：`login_as "biz1" "Passw0rd!"` → `POST /api/req/requirements {"type":"BUS","title":"neg-r28-bus-${RUN_TAG}","domainIds":[$DOMAIN1]}` → `POST /api/req/requirements/{id}/actions {"action":"submit"}`（SUBMITTED→APPROVING）。
   - 取任务：`login_as "pm"` → `GET /api/req/approvals/todo?page=1&size=200` → `[.items[] | select(.bizId == $R28_BUS and .bizType == "REQ_SUBMIT")][0].taskId`。
   - 不复用 4d 的 `R27_BUS`：它已被 pm approve（任务 handled），无法再走「减签/加签」路径；也不复用 `.items[0]`（旧写法可能抽到 `WBS_ESTIMATE` 单，隐性依赖待办排序）。
2. **两条断言恒定执行**（判据一字不改）：
   - 减签空原因：`POST /api/req/approvals/tasks/{taskId}/remove-sign {"targetUserId":1,"reason":""}` → `400 REASON_REQUIRED`。依据：`ApprovalEngine.removeSign`（`req-service/src/main/java/com/example/req/approval/ApprovalEngine.java:279-283`）先校验原因、后锁任务，故 PENDING 任务上必为 400 且不落库。
   - 已处理加签：本段先 `POST .../tasks/{taskId}/actions {"action":"APPROVE","comment":"r28 夹具：先处理"}`（要求 200；不 `pass` 计数），再 `POST .../tasks/{taskId}/add-sign {"type":"AFTER","userIds":[1],"comment":"x"}` → `409 TASK_ALREADY_HANDLED`。依据：`ApprovalEngine.addSign`（同文件 `:226-241`）在 `!task.isPending()` 时抛 409。
3. **计数账（必须恒定 130）**：夹具步骤只用 `api_call` 与失败即 `fail`，**不调用 `pass`/`allow`/`deny`**，故不增加 TOTAL；两条 `deny` 是唯一新增计数（+2）。
   | 环境 | 旧 | 新 |
   |---|---|---|
   | 干净重置（demo-reset 后） | 128（2 条 warn 跳过） | **130** |
   | 有既有待办 | 130 | **130** |
4. **失败即真失败**：夹具任一步失败（biz1/pm 登录、建单、submit、待办取 taskId、approve 非 200）一律 `fail "7) 造前置审批任务失败：<现场>" "…修复提示"`，**不得** `warn` 跳过；`PASS != TOTAL` 由 `platform-test-negative.sh:931-933` 退出 1。pm 登录失败同样改为 `fail`。
5. **收尾**：段末 `API_TOKEN="$ADMIN_TOKEN"` 复位（与相邻段一致），避免污染后续段的身份。

## 4. 行为兼容性影响

- 产品运行时行为：**零变更**（无 Java/SQL/前端改动，接口契约、错误码、状态机不动）。
- 门禁行为（刻意变更）：`make platform-test-negative` 在干净重置环境由 128 变 130；不再出现「跳过」绿；夹具坏掉时门禁变红而不是变绿。这是本轮唯一可观察变化，属「更严」方向。
- 数据副作用：每次运行额外留一张 `neg-r28-bus-*` 需求（APPROVING→PENDING_REVIEW），与既有 4d/16 段夹具同性质，由 `make platform-demo-reset` 清场；已核后续段无「requirement 全表计数」断言（仅 `:55`、`:134` 用同段 before/after 差值）。

## 5. 回归与验证计划

前置：`make platform-down` → `mvn -o -Dmaven.repo.local=$PWD/.m2/repository -DskipTests compile` → `make platform-up` → `bash scripts/platform-demo-reset.sh`（69/69）。

| 命令 | 期望（下限） |
|---|---|
| `bash scripts/platform-test-negative.sh` | **130/130**，日志无 `无待办任务，跳过减签/加签负向`；连跑两次同结果 |
| `bash scripts/platform-test-negative.sh`（有遗留待办时） | 仍 **130/130**（不能 131/132） |
| 变异自检（QA 本地临时注释夹具块，不提交） | **exit 1** 且失败行指向第 7/8 段（证明断言不再被环境吞掉） |
| `bash scripts/platform-verify.sh` | ≥193 |
| `bash scripts/access-verify.sh` | ≥206 |
| `bash scripts/rbac-verify.sh` | 113 |
| `bash scripts/platform-demo-reset.sh` | 69 |
| `bash scripts/lint.sh` | 22 |
| `bash scripts/platform-unit.sh` | ≥371 |
| `cd web && npm run typecheck && npm run build` | 绿 |
| `bash scripts/test-all.sh`（可选，串跑） | 全绿 |

验收还需：提交说明与验收报告写清「128→130」的根因、修法、复跑证据（日志行号 + 两条 PASS 原文），提交信息含 runId `tf-mwkj8oj8-hoo3ee` 与任务夹路径。

## 6. 风险与回滚

- 夹具强依赖默认 `REQ_SUBMIT/BUS` 流程与 `biz1`/`pm` 账号：若种子改角色或流程改审批人，段内取不到 taskId → 门禁红。**这是有意的**（暴露流程漂移）；修复方向是更新夹具选择器，不是回退成 warn。
- 计数账唯一支点是「夹具不 `pass`」：评审/QA 若给夹具加 `pass`，总数变 131+，与 AC 冲突——代码评审按第 3 节表格检查。
- 每次运行多一张需求/一条审批实例：只在演示库，`platform-demo-reset` 可恢复；不影响 130 的计数（计数只看断言）。
- 回滚：单文件单段回滚，`git checkout 7144d30 -- scripts/platform-test-negative.sh` 即恢复 r27 语义（连带回到 128 的环境依赖态，需同步回退 memory 行尾 r28 订正句）。

## 7. 假设与待澄清

- **夹具单据类型**：选 `REQ_SUBMIT/BUS`（biz1 提报）。备选 `WBS_ESTIMATE` 不可行——pm 无 `req:deliver`，只有域负责人/产品经理「审批」权，提报权在研发；若产品坚持用评估单，则须给 pm 加 `req:deliver`，会改权限基线，不建议。
- **夹具是否计断言**：按「总数恒定 130」取「夹具不计」。若希望把「夹具就绪」也纳入计数，基线应改 131 并同步 memory/AC-10——需产品确认，默认不采纳。
- **变异钩子**：不新增 `R28_*_MUTATE` 环境变量（避免与 r27 的 `R27_PARITY_MUTATE` 混成一类）；变异自检由 QA 临时改脚本完成，工作区不留钩子。
- 其余无待澄清项；用户约束「不动业务代码、不改 platform-verify/access-verify 语义」已按原样遵守。
