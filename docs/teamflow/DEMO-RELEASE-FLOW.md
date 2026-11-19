# DEMO · 发版分级审批链（r32 · 一条需求跑到 PUBLISHED）

> 本文件是 r32「演示夹具 → 版本测试 → 报告三签 → 质量门 → 发布单准入 → 发布单两级 → 版本三级 → PUBLISHED」的**演示脚本**，命令可直接粘贴执行。
> 约束：演示与实现同账号、同路径、同端口（网关 8080 / req 8082 / deliver 8083），不另起环境、不改数据。
> 截图位统一放在 `docs/teamflow/demo-shots/`，本轮新增 `r32-published-chain.png` 与 `r32-admission-409-mr.png`。
> r30 那次停在同一份准入清单上（9 项里 4 项 FAIL），原因记在附 A；r32 用夹具把那条需求补到「可真发布」，整条链现在能跑到 `PUBLISHED`。

---

## 0. 前置（一次性）

```bash
make platform-up                 # 栈：nacos + 网关 + req + deliver
make platform-seed               # 种子：账号/域/演示数据 + r30 分级定义与 6 个绑定槽
make platform-demo-reset         # 演示夹具复位（12 条需求链 + 不变量自检）
bash scripts/release-flow-upgrade.sh   # 可选：证明 /internal/seed 连跑两次计数不增
```

账号（密码统一 `Passw0rd!`，admin 为 `Admin@12345`）：

| 账号 | 角色 | 在本链中的位置 |
|---|---|---|
| `pm` | PRODUCT | RELEASE 常规链第 1 级（产品经理会签） |
| `tl1` | PMO | RELEASE 常规链第 2 级 / 发布单链第 1 级 |
| `vendor1` | PMO（厂商） | 与 `tl1` **同为** PMO 会签人（ALL 模式需两人都批） |
| `mgr1` | MGMT | RELEASE 常规链第 3 级（管理层，ANY） |
| `admin` | PLATFORM_ADMIN | 发布单链第 2 级（平台管理员会签） |
| `qa1` | QA / TEST_LEAD | 测试报告三签第 1 签、计划执行人 |

所有请求都带 `Authorization: Bearer <token>` 与 `X-Tenant-Id: <数字 tenantId>`（网关按数字解析，传 `t1` 会 403）。

```bash
BASE=http://127.0.0.1:8080
login() { curl -sS -X POST "$BASE/api/req/auth/login" -H 'Content-Type: application/json' \
  -d "{\"tenantCode\":\"t1\",\"username\":\"$1\",\"password\":\"$2\"}"; }
TOK_PM=$(login pm 'Passw0rd!' | jq -r .token)
TOK_TL1=$(login tl1 'Passw0rd!' | jq -r .token)
TOK_V1=$(login vendor1 'Passw0rd!' | jq -r .token)
TOK_MGR=$(login mgr1 'Passw0rd!' | jq -r .token)
TOK_ADM=$(login admin 'Admin@12345' | jq -r .token)
TOK_QA=$(login qa1 'Passw0rd!' | jq -r .token)
# 交付登记（deliver）要 req:deliver，PRODUCT 是 DENY、DEV 才有；演示里由需求负责人 dev2 登记。
TOK_DEV=$(login dev2 'Passw0rd!' | jq -r .token)
TID=$(login admin 'Admin@12345' | jq -r .tenantId)
api() { # api <token> <METHOD> <path> [json]
  local t=$1 m=$2 p=$3 d=${4:-}
  if [[ -n "$d" ]]; then
    curl -sS -X "$m" "$BASE$p" -H "Authorization: Bearer $t" -H "X-Tenant-Id: $TID" \
      -H 'Content-Type: application/json' -d "$d" -w '\n%{http_code}\n'
  else
    curl -sS -X "$m" "$BASE$p" -H "Authorization: Bearer $t" -H "X-Tenant-Id: $TID" -w '\n%{http_code}\n'
  fi
}

# 演示夹具变量（platform-demo-reset 之后稳定存在；库参数与 scripts/lib.sh 一致：root/空密码）
q() { mysql -N -e "$1" devops_platform; }
DOMAIN=$(q "SELECT id FROM biz_domain WHERE tenant_id=$TID ORDER BY id LIMIT 1")
# r32 的主角：DEMO-R-04 是唯一「已是 PENDING_TEST 且父链上有业务祖先」的 RD——
# 它的 MR/实际人天/需求级两级会签都由夹具用真实动作链补好（见附 A.2）。
RD_ID=$(q "SELECT id FROM requirement WHERE tenant_id=$TID AND code='DEMO-R-04' LIMIT 1")
CASE03=$(q "SELECT id FROM test_case WHERE tenant_id=$TID AND code='DEMO-C-03' LIMIT 1")
CASE04=$(q "SELECT id FROM test_case WHERE tenant_id=$TID AND code='DEMO-C-04' LIMIT 1")
CASE_ID=$CASE03   # 兼容下文单用例写法
MR_URL='https://git.example.com/mr/r32-e2e'
```

> 下文 `→ 200` 指 http 状态；响应体用 `jq` 取字段。演示只读取，不手工改库。

---

## 1. 建演示版本（NORMAL 三级链）

先把 R-04 从旧版本摘出来。`DEMO-REL-1` 挂了 11 条需求，种子计划只覆盖其中 5 条，
QG-01 要 100% 覆盖率 → 那条版本永远过不了质量门（`5/11 = 45%`）。演示版本只挂 R-04，
1 条需求 2 条用例 = 100%。摘除要求原版本还在 `PLAN`（`DEMO-REL-1` 是），锁定后就得整条重来。

```bash
OLD_REL=$(q "SELECT release_id FROM requirement WHERE tenant_id=$TID AND id=$RD_ID")
[[ -n "$OLD_REL" ]] && api "$TOK_PM" DELETE "/api/req/releases/$OLD_REL/requirements/$RD_ID"
# → 204（无响应体）；需求行 release_id 置空，原版本覆盖分母同步减 1

REL=$(api "$TOK_PM" POST /api/req/releases '{
  "name":"r32 演示-常规发版","domainId":'"$DOMAIN"',"gateMode":"STRICT",
  "startDate":"2026-11-18","endDate":"2026-11-30","plannedReleaseDate":"2026-12-02",
  "releaseLevel":"NORMAL"}' | sed '$d' | jq -r .release.id)
api "$TOK_PM" GET "/api/req/releases/$REL" | head -1 | jq '.release | {code,releaseLevel,releaseLevelLabel,status}'
```
→ `201`，`releaseLevel:"NORMAL"`、`releaseLevelLabel:"常规"`、`status:"PLAN"`；`approvalLevels` 为空（未发起）。
`domainId` 取演示域：`mysql -N -e "SELECT id FROM devops_platform.biz_domain LIMIT 1"`。

**截图位 1**：版本详情「概览」顶部四段链路（全 MISSING）+ 侧栏等级「常规」。

## 2. 挂需求（DEMO-R-04，MR/工时已备）

```bash
api "$TOK_PM" POST "/api/req/releases/$REL/requirements" \
  '{"requirementIds":['"$RD_ID"']}'
```
→ `200`；`RD_ID` 用 `make platform-demo-reset` 后的 `DEMO-R-04`：状态 `PENDING_TEST`，
`mr_url=https://git.example.com/mr/r32-e2e`、`actual_days=2.0`，且需求级两级会签都已 `APPROVED`
（夹具走的是真实审批链，不是直插结果表——附 A.2 有证据）。
此时 `flowChain.deployAdmission.status:"MISSING"`（还没有发布单），`testPlan.status:"MISSING"`。

## 3. 建测试计划并跑一轮 PASS

演示版本只挂 1 条需求，覆盖率天然 100%。计划必须把这条需求的两条用例都放进去**并且都执行**：
STRICT 版本 QG-03 要 100% 执行，`rounds/{id}/close` 对未执行用例直接 409。

```bash
PLAN=$(api "$TOK_QA" POST /api/deliver/test-plans '{
  "releaseId":'"$REL"',"name":"r32 演示计划","ownerId":1,
  "windowStart":"2026-11-20","windowEnd":"2026-11-27",
  "caseIds":['"$CASE03"','"$CASE04"'],"gateMode":"STRICT"}' \
  | sed '$d' | jq -r .plan.id)
for a in submit-review approve start; do api "$TOK_QA" POST "/api/deliver/test-plans/$PLAN/$a" >/dev/null; done
for c in "$CASE03" "$CASE04"; do
  api "$TOK_QA" POST "/api/deliver/test-plans/$PLAN/execute" \
    '{"caseId":'"$c"',"result":"PASS","note":"r32 演示"}' >/dev/null
done
RID=$(mysql -N -e "SELECT id FROM devops_platform.test_plan_round WHERE plan_id=$PLAN AND active=1")
api "$TOK_QA" POST "/api/deliver/test-plans/$PLAN/rounds/$RID/close" '{"conclusion":"PASS","note":"全通过"}'
api "$TOK_QA" POST "/api/deliver/test-plans/$PLAN/complete"
```
→ 计划 `APPROVED→IN_PROGRESS→COMPLETED`；轮次 `exec_total=2`、`exec_passed=2`、`conclusion=PASS`；
版本详情的 `flowChain.testPlan` 由 `MISSING` 变 `PASS`，`allApproved:true`。

**截图位 2**：链路第①段「测试计划」由虚线的「暂无」翻成 PASS。

## 4. 测试报告与三签（qa1 → pm → tl1）

```bash
RPT=$(api "$TOK_QA" POST /api/deliver/test-reports \
  '{"scope":"RELEASE","releaseId":'"$REL"',"note":"r32 演示报告"}' | sed '$d' | jq -r .report.id)
api "$TOK_QA" POST "/api/deliver/test-reports/$RPT/versions/1/signoff" '{"decision":"APPROVE","comment":"测试通过"}' >/dev/null
api "$TOK_PM" POST "/api/deliver/test-reports/$RPT/versions/1/signoff" '{"decision":"APPROVE","comment":"产品确认"}' >/dev/null
api "$TOK_TL1" POST "/api/deliver/test-reports/$RPT/versions/1/signoff" '{"decision":"APPROVE","comment":"PMO 确认"}' >/dev/null
api "$TOK_QA" GET "/api/deliver/test-reports/$RPT/versions/1/signoff" | head -1 | jq '{status,conclusion,approved,total}'
```
→ `status:"SIGNED"`、`approved:3`、`total:3`；链路第②段 `signoff.levels` 三行全绿。
签署顺序不可乱：`qa1` 未签时 `pm` 签会 409/403（顺序锁）。

**截图位 3**：链路第②段「报告三签」三行阶梯 + 侧栏审批进度。

## 5. 评质量门（PASS 才放行）

```bash
GATE=$(api "$TOK_QA" POST "/api/deliver/quality-gates/$REL/evaluate" \
  '{"deployOrderId":null,"requirementIds":['"$RD_ID"']}')
echo "$GATE" | head -1 | jq '{result,snapshotId,failedRules}'
```
→ `result:"PASS"`、`failedRules:[]`、`snapshotId:0`——**这一步还没有快照**：evaluate 给的是评审结果，
真正落库、被发布单和链路引用的是提交时 capture 的那一份（见 A.2 第 4 条）。
所以链路第③段要等发布单提交之后再读；门本身仍是只读的——快照一旦生成就不再重算。

**截图位 4**：链路第③段「质量门」PASS + 规则明细。

## 6. 建发布单（含 9 项准入）

```bash
ORD=$(api "$TOK_PM" POST /api/req/deploy-orders '{
  "releaseId":'"$REL"',"env":"PROD",
  "windowStart":"2026-12-02T22:00:00+08:00","windowEnd":"2026-12-03T02:00:00+08:00",
  "artifactVersion":"r32-demo-1","rollbackPlan":"回滚到 r29 镜像","impact":"演示环境",
  "ownerId":1,"steps":[{"name":"停流量","order":1},{"name":"部署","order":2},{"name":"回归","order":3}],
  "requirementIds":['"$RD_ID"']}' | sed '$d' | jq -r .order.id)
api "$TOK_PM" GET "/api/req/releases/$REL" | head -1 \
  | jq '.flowChain.deployAdmission | {status,passedCount,failedCount,frozen,items:(.items|length)}'
```
→ 准入 9 项：`REQ_TEST_PASSED / NO_OPEN_HIGH_DEFECT / REQ_APPROVALS_DONE / MR_FILLED / MAN_DAYS_FILLED /
QUALITY_GATE / BUILD_PASSED / SONAR_GATE_PASSED / ARTIFACT_VERIFIED`，`frozen:false`（DRAFT 实时评估）。
链路第④段由 `MISSING` 变 `PASS`；发布单详情侧的同一份清单在 `GET /api/req/deploy-orders/$ORD` 的 `.admission`
（`.admission.counts.passed/failed`、`.admission.items|length==9`）。

**截图位 5**：链路第④段「发布单准入」9 项明细 + `frozen:false` 印章。

## 7. 发起版本审批并提交发布单（含清空 MR 的 409）

```bash
# 7a 版本进入 LOCKED（此后等级/需求都不可改）
api "$TOK_PM" POST "/api/req/releases/$REL/approval" | head -1 | jq '{instanceId,nodeKeys}'
# → 201，{"instanceId":N,"nodeKeys":["n1","n2","n3"]}

# 7b 负向：版本已 LOCKED，但需求本身仍可写 → 清空 MR 后提交，必须被 MR 闸门拦住
#    注意传 mrUrl:""（空串）。RequirementService.deliver 只在 mrUrl != null 时写库，传 null 清不掉。
#    登记人用 dev2：git 里 req:deliver 只发给 PMO/DEV，PRODUCT（pm）调这个是 403 req.action.deliver。
api "$TOK_DEV" POST "/api/req/requirements/$RD_ID/deliver" '{"mrUrl":""}'
api "$TOK_PM" POST "/api/req/deploy-orders/$ORD/submit" '{}' | head -1 \
  | jq '{code, failed:.detail.failed, items:(.detail.items|length)}'
# → 409 {"code":"ADMISSION_FAILED","detail":{"failed":["MR_FILLED"],...}}；快照数仍是 0（拒绝发生在 capture 之前）

# 7c 恢复 MR 后提交：闸门顺序 计划→LOCKED→准入→门→快照→审批
api "$TOK_DEV" POST "/api/req/requirements/$RD_ID/deliver" '{"mrUrl":"'"$MR_URL"'"}'
api "$TOK_PM" GET "/api/req/deploy-orders/$ORD" | head -1 \
  | jq '.flowSnapshot | {round,payloadHash,changedSinceSubmit}'
```
→ 7b 的 `failed` 恰好只有 `MR_FILLED` 一项（闸门按顺序判，不一次抛一堆）；7c 提交 `200`；
`flowSnapshot.round:1`、`payloadHash` 64 位小写 hex、`changedSinceSubmit:false`、
`flowSnapshotHistory[{round:1,...}]`。回放：`GET /api/req/deploy-orders/$ORD/flow-snapshots/1`。
提交后才产生 `PENDING_APPROVAL`，发布单审批链（两级）此时才有待办。

**截图位 6**：发布单详情「提交回执」卡：第 1 轮 + 12 位 hash + 段级「已变化」无印章；
另一张 `r32-admission-409-mr.png` 拍 7b 那一刻的准入清单——9 项 **通过 5 / 失败 1 / 跳过 0 / 关闭 3**：
`MR_FILLED` 是唯一红项（现场日志「未填 MR: DEMO-R-04」），第 7–9 项（构建 / Sonar / 制品校验）因集成未启用而
`CLOSED`（不伪装成 PASS），其余 5 项绿。

## 8. 逐级审批：发布单两级 → 版本三级 → PUBLISHED

```bash
todo() { api "$1" GET '/api/req/approvals/todo?page=1&size=100' | head -1 \
  | jq -r --arg b "$2" --arg t "$3" --arg n "$4" \
      '.items[] | select(.bizId==($b|tonumber) and .bizType==$t and .nodeKey==$n) | .taskId' | head -1; }
approve() { api "$1" POST "/api/req/approvals/tasks/$2/actions" '{"action":"APPROVE","comment":"演示批准"}' >/dev/null; }

# 发布单 n1：PMO 会签（tl1 与 vendor1 都必须批）；n2：平台管理员
approve "$TOK_TL1" "$(todo "$TOK_TL1" "$ORD" REQ_DEPLOY n1)"
approve "$TOK_V1"  "$(todo "$TOK_V1"  "$ORD" REQ_DEPLOY n1)"
approve "$TOK_ADM" "$(todo "$TOK_ADM" "$ORD" REQ_DEPLOY n2)"
api "$TOK_PM" GET "/api/req/deploy-orders/$ORD" | head -1 | jq '{status:.order.status,levels:[.approvalLevels[].status]}'
# → status APPROVED，levels ["APPROVED","APPROVED"]

# 版本 n1 pm → n2 PMO 会签（tl1 + vendor1）→ n3 管理层（ANY）
approve "$TOK_PM"  "$(todo "$TOK_PM"  "$REL" RELEASE n1)"
approve "$TOK_TL1" "$(todo "$TOK_TL1" "$REL" RELEASE n2)"
approve "$TOK_V1"  "$(todo "$TOK_V1"  "$REL" RELEASE n2)"
approve "$TOK_MGR" "$(todo "$TOK_MGR" "$REL" RELEASE n3)"
api "$TOK_PM" GET "/api/req/releases/$REL" | head -1 \
  | jq '{status:.release.status,levels:[.approvalLevels[].status]}'
```
→ 版本 `PUBLISHED`，`approvalLevels` 三级全 `APPROVED`（序号 n1/n2/n3 与节点名一一对应）。
每一步 `NODE_APPROVED` 留痕都含 `nodeKey/nodeName/mode` 三键（审批实例历史可查）。

**截图位 7**：版本详情审批阶梯三级全绿 + 状态 `PUBLISHED`。
**截图位 8**：发布单详情两级审批 + 快照卡仍可回放第 1 轮。

---

## 被拦链（四条真拦，不是灰按钮）

演示前先复制一份现场（或用新版本号），以下每条都应看到**具体错误码**，而不是「静默通过」。

### A. 测试报告没签完 → 提交被门拦住（409）

在上面的版本上重新生成一版报告（未签），再提交同一发布单的**新单据**：

```bash
api "$TOK_QA" POST /api/deliver/test-reports '{"scope":"RELEASE","releaseId":'"$REL"',"note":"故意不签"}'
ORD2=$(api "$TOK_PM" POST /api/req/deploy-orders '{...同步骤 6，artifactVersion:"r32-demo-2"...}' | sed '$d' | jq -r .order.id)
api "$TOK_PM" POST "/api/req/deploy-orders/$ORD2/submit" '{}'
```
→ `409`，`code:"QUALITY_GATE_FAILED"`，`detail.failedRules` 含 `QG-02`（`REPORT_NOT_SIGNED`，若报告结论为 CONDITIONAL 则是 `REPORT_CONDITIONAL_UNAPPROVED`）；
发布单仍 `DRAFT`，`flow-snapshots` 计数 **0**（拒绝发生在 capture 之前）。

### B. 紧急修复不写原因 → 400 且等级不变

```bash
api "$TOK_PM" PATCH "/api/req/releases/$REL/level" '{"releaseLevel":"EMERGENCY","emergencyReason":"   "}'
```
→ `400`，`code:"VALIDATION_FAILED"`，`detail.field:"emergencyReason"`；再查版本仍是原等级（写库前校验，不落半成品）。

### C. 越级审批 → 403（任务仍 PENDING，无 APPROVE 留痕）

版本发起后，`mgr1` 直接批 n2 的任务（此时该任务属 `tl1`/`vendor1`）：

```bash
T=$(todo "$TOK_TL1" "$REL" RELEASE n2)
api "$TOK_MGR" POST "/api/req/approvals/tasks/$T/actions" '{"action":"APPROVE","comment":"越级"}'
```
→ `403`；该 task 仍 `PENDING`，实例历史里没有 `APPROVE` 记录（`assertAssignee` 在写历史之前）。

### D. 没有「直接发布」的后门

```bash
curl -sS -o /dev/null -w '%{http_code}\n' -X POST "$BASE/api/req/releases/$REL/publish" \
  -H "Authorization: Bearer $TOK_ADM" -H "X-Tenant-Id: $TID"
```
→ `404/405`（发布只能由最后一级审批回调触发；`release-flow-verify` S6 静态检索同款约束）。

---

## 一键回归

```bash
make release-flow-verify     # S1..S7 40 条，PASS 必须等于 TOTAL；结束自动 demo-reset
make release-flow-upgrade    # 连跑两次 /internal/seed，定义/绑定/version 不增（12 条）
make platform-demo-reset     # 74 条：业务行 + 工时引擎 + 演示审批链，可重复执行
```

`release-flow-verify` 的 `--static-only` 只跑不连栈的契约检查（脚本语法/断言分母/S1–S7 分段/S7 函数齐备/
Makefile/DEMO 文档/EXIT trap），用于改脚本时快速自检；正式门禁必须连栈跑满 40 条，**缺前置直接失败，不跳过**。
S7 起跑前会自愈 `platform-demo-reset`，所以 S1–S5 把库改成什么样都不影响它。

---

## 附 A. 实测记录（2026-11-19，feat/demo-release-green，我自己跑过的）

下面这段不是设计意图，是真跑出来的结果与踩到的坑。r30 那次停在「准入 4 项 FAIL」，r32 把夹具补齐后
同一条链一次跑到 `PUBLISHED`——两次的差别写在 A.3。

### A.1 跑通到哪一步（全绿）

一次跑到 `PUBLISHED`：`make platform-demo-reset` 连跑两次都 `74/74`（两次逐键计数一致，4 条新断言是
R-04 六字段、R-01 提报单/复核单、演示计划覆盖）；8 步全部 2xx，需求 `DEMO-R-04` 从 `PENDING_TEST`
走到 `TEST_PASSED`；发布单准入在「清空 MR」那一刻是 **通过 5 / 失败 1（只 `MR_FILLED`）/ 关闭 3**，
恢复 MR 后提交 `200`、`flowSnapshot.round=1`；发布单两级 + 版本三级批完后版本 `LOCKED → PUBLISHED`
（`releasedAt=2026-11-19T10:17:36.943`，三级全 `APPROVED`）；`make release-flow-verify` → `CASE=40 PASS=40`。
**逐步响应码、两把梯子的逐级待办/留痕、PUBLISHED 的落库字段与查询、AC-8 的 409 实测都在 A.3。**

**截图**：`r32-published-chain.png`（REL-1444 发版链路四段全「已通过」+ 右侧三级审批阶梯全绿 + 状态「已发布」）、
`r32-admission-409-mr.png`（DEP-0748 准入 9 项：仅 `MR_FILLED` 红、快照「尚未提交 · 提交成功后生成快照」）。

> 截图里 ③ 卡片副标题写着「1 条未通过」，但卡片是 `PASS / 已通过`——这 1 条是 `QG-05`（本轮未接入外部质量源，
> 规则状态 `SKIPPED`），被 `FlowEvidenceService.qualityGate` 按「非 PASS 即 failed」的口径算进了 `failedRules`；
> 结论没有错，是这个展示口径与 `QualityGateService` 的「SKIPPED 不计入失败」不一致。已作为交付遗留记录，
> 不在本轮改动范围（见本次 dev 汇总的 out-of-scope）。

### A.2 四个必须知道的实操事实

1. **8 步不能跑在 `DEMO-REL-1` 上**。QG-01 的分母是 `release_requirement`，`DEMO-REL-1` 挂 11 条需求、
   种子计划只覆盖 5 条 → `5/11 = 45%`，质量门永远 FAIL，发布单提交过不去。第 1 步必须先
   `DELETE /releases/DEMO-REL-1/requirements/{R-04}`（版本在 `PLAN` 才可编辑），再建只挂 R-04 的演示版本。
2. **登记交付（deliver）不是 pm 的权限**。`req.action.deliver` 背后是 `req:deliver`，PRODUCT 是 DENY，
   只有 DEV / PMO 有；pm 调会拿到 `403 {"code":"FORBIDDEN","detail":{"action":"req.action.deliver","roles":["PRODUCT"]}}`。
   演示用需求负责人 `dev2`。这也是上面命令里 `TOK_DEV` 的来历。
3. **清空 MR 必须传 `mrUrl:""`**。`RequirementService.deliver` 只在 `mrUrl != null` 时写库（:571），
   传 `null` 不清空，闸门也就不会红——负向会变成假绿。
4. **`POST /quality-gates/{releaseId}/evaluate` 返回的是「评审」结果，不是快照**。`result=PASS` 但
   `snapshotId=0`；真正落库、被发布单引用的是提交时 capture 的那一份（本次 `#753`）。
   所以「链路第③段」要在**发布单建好之后**读 `GET /releases/{id}` 的 `flowChain.qualityGate`。
   另注：用例列表路径是 `/api/deliver/testcases`（`/test-cases` 不 404，而是挂住约 20 秒）。

### A.3 全绿跑通：逐步响应码、两阶梯留痕、PUBLISHED 落库

r30 那次准入的实况是 **通过 2 / 失败 4 / 关闭 3**：`REQ_TEST_PASSED`（需求还在 TODO）、
`REQ_APPROVALS_DONE`（需求级审批没发起）、`MR_FILLED`、`MAN_DAYS_FILLED` 四项全红；
版本侧的测试计划、报告三签、质量门那三段当时已经全绿——**卡点全在需求自己身上**，这正是联动该有的样子
（r30 时点的原始记录与截图保留在附 B，作对照）。

r32 没改业务语义，只补了一个演示夹具（`DemoApprovalFixture`，作为 `demo-reset` 的第三段事务）：
`DEMO-R-01` 用真实动作链走 `submit → 会签 → review_start(pm) → 会签`，两张审批单落成 `APPROVED`，
净状态回到 `POOLED`（评审通过后的合法态）；`DEMO-R-04` 经 `deliver` 写 MR 与 `actual_days=2.0`；
链上通知按 `notification.id` 水位线删掉，「通知 4 条」口径不变。于是需求侧四项准入自动变绿
（`REQ_TEST_PASSED` 由第 5 步当场补上），剩下就是把两把梯子走完。**链路本身一行没改**：
同一条链在 r30 停在准入，在 r32 的终点是 `PUBLISHED`。

#### A.3.1 逐步真实响应码

| # | 步骤 | 实测响应 | 关键回执 |
|---|---|---|---|
| 1 | 摘 R-04：`DELETE /releases/DEMO-REL-1/requirements/{R-04}` | `204` | 版本须在 `PLAN` 才可编辑 |
| 2 | 建演示版本：`POST /releases`（`releaseLevel=NORMAL`） | `201` | `REL-1444`，`status=PLAN` |
| 3 | 挂需求：`POST /releases/{id}/requirements` | `200` | 覆盖率分母变 `1` |
| 4 | 计划 C-03/C-04：`submit-review → approve → start` | `200` ×3 | 批计划人是计划负责人（`qa1` 会 `403`，见 A.2） |
| 5 | 执行：`execute` ×2（两条都 PASS） | `200` ×2 | `exec_total=2`、`exec_passed=2` |
| 6 | 关轮次 → 完成计划：`close → complete` | `200` ×2 | `conclusion=PASS`，版本覆盖率 `1/1` |
| 7 | 需求提测通过：`POST /requirements/{R-04}/actions`（`TEST_PASS`） | `200` | `DEMO-R-04` → `TEST_PASSED` |
| 8 | 报告三签：`qa1 → pm → tl1` | `200` ×3 | `flowChain.signoff.approved=3`，`status=SIGNED` |
| 9 | 建发布单：`POST /deploy-orders` | `201` | `DEP-0748`；准入 9 项 = 5 过 / 1 红 / 3 关闭 |
| 10 | 版本发起审批：`POST /releases/{id}/approval` | `201` | `nodeKeys=["n1","n2","n3"]`，版本 `LOCKED` |
| 11 | 清空 MR 后提交（AC-8，详见 A.3.4） | `409` | `ADMISSION_FAILED`，`failed` 恰 `["MR_FILLED"]`，快照 `0` |
| 12 | 恢复 MR 后提交 | `200` | `flowSnapshot.round=1`、`payloadHash=e819bc03…f83a5`（64 hex） |
| 13 | 读质量门快照 `#753`：`GET /releases/{id}` | `200` | QG-01/02/03 全 `PASS`；QG-01 `需求覆盖 100%（1/1）` |
| 14 | 发布单两级批完（n1 tl1+vendor1 → n2 admin） | `200` ×3 | `levels=["APPROVED","APPROVED"]` |
| 15 | 版本三级批完（n1 pm → n2 tl1+vendor1 → n3 mgr1） | `200` ×4 | `LOCKED → PUBLISHED`，`releasedAt=2026-11-19T10:17:36.943` |

#### A.3.2 两把梯子的逐级待办与留痕

| 阶梯 | 级 | `nodeKey` / 模式 | 待办落在谁 | 结果 |
|---|---|---|---|---|
| 发布单 `DEP-0748` | 1 | `n1` / ALL 会签 | PMO 的 `tl1` 与 `vendor1` 各一条 | 两人都批 → 该级 `APPROVED` |
| 发布单 `DEP-0748` | 2 | `n2` / ALL | 平台管理员 `admin` | 批后发布单 `APPROVED` |
| 版本 `REL-1444` | 1 | `n1` / ALL 会签 | 产品 `pm` | 批 → 该级 `APPROVED` |
| 版本 `REL-1444` | 2 | `n2` / ALL 会签 | PMO 的 `tl1` 与 `vendor1` 各一条 | 两人都批 → 该级 `APPROVED` |
| 版本 `REL-1444` | 3 | `n3` / ANY 单签 | 管理层 `mgr1` | 批 → 全链完成，版本 `PUBLISHED` |

逐级留痕直接查库（`$ORD`/`$REL` 换成 `DEP-0748`/`REL-1444` 的 id）：

```sql
-- 待办：会签级（MODE_ALL）每个审批人一行 wf_task，单签级（MODE_ANY）一人通过即过；node_key 即阶梯级
SELECT i.biz_type, i.biz_id, t.node_key, t.mode, t.approver_id, t.status, t.handled_at
FROM wf_instance i JOIN wf_task t ON t.instance_id = i.id
WHERE (i.biz_type = 'REQ_DEPLOY' AND i.biz_id = $ORD)
   OR (i.biz_type = 'RELEASE'    AND i.biz_id = $REL)
ORDER BY i.id, t.node_key, t.sort_no;

-- 留痕：每个审批人的动作一条 APPROVE，每级通过一条 NODE_APPROVED（detail_json 里带 nodeKey/mode）
SELECT instance_id, action, operator_id,
       detail_json->>'$.nodeKey' AS node_key, detail_json->>'$.mode' AS mode
FROM wf_history
WHERE instance_id IN (SELECT id FROM wf_instance
                      WHERE (biz_type = 'REQ_DEPLOY' AND biz_id = $ORD)
                         OR (biz_type = 'RELEASE'    AND biz_id = $REL))
ORDER BY id;
```

实测：两阶梯 5 级的 `wf_task` 全部 `APPROVED`、`handled_at` 非空，没有残留 `PENDING`；
`wf_history` 里每级恰有一条 `NODE_APPROVED`，`nodeKey` 与阶梯级一一对应，`operator_id` 都落在上表的待办人上。
页面侧同一份数据渲染成 `r32-published-chain.png` 右侧的两列阶梯（发布单两级、版本三级，全绿）。

#### A.3.3 PUBLISHED 的落库证据

接口侧 `GET /api/req/releases/$REL` 的 `approvalLevels` 三级全 `APPROVED`，`status="PUBLISHED"`、
`releasedAt="2026-11-19T10:17:36.943"`；发布单侧 `GET /api/req/deploy-orders/$ORD` 为 `APPROVED`，
`flowSnapshot.round=1`。

落库字段（`released_at` 非空即 PUBLISHED 已写库，它是版本审批终态与业务状态**同一事务**写入的）：

```sql
SELECT code, status, released_at FROM `release` WHERE code = 'REL-1444';
-- REL-1444 | PUBLISHED | 2026-11-19 10:17:36.943

-- 发布单提交时 capture 的那份快照（第 13 步读的就是它）
SELECT round_no, payload_hash, captured_at FROM deploy_flow_snapshot
WHERE deploy_order_id = (SELECT id FROM deploy_order WHERE code = 'DEP-0748');
-- round_no=1 | payload_hash=e819bc0350397335238e3e36abea963a5ca2f3389ba70df6691b36e6e53f83a5 | 提交时刻
```

#### A.3.4 AC-8 的 409 实测（清空 MR 必被拦）

前置：版本已 `LOCKED`（第 10 步），发布单 `DEP-0748` 仍为 `DRAFT`、未提交过。

```bash
# 1) 清空 MR —— 必须传 ""；传 null 不清库（A.2 第 3 条），负向会变假绿
api "$TOK_DEV" POST "/api/req/requirements/$RD_ID/deliver" '{"mrUrl":""}'
# 2) 提交
api "$TOK_PM" POST "/api/req/deploy-orders/$ORD/submit" '{}'
```

`409`，响应体 `{"code":"ADMISSION_FAILED","detail":{"failed":["MR_FILLED"],"fixHint":"…"}}`：
`failed` **恰好一项**（其余 8 项不报错——5 项 `PASSED`、3 项 `CLOSED`，不是一次抛一堆）；
`deploy_flow_snapshot` 计数仍 `0`、没有 `round`，发布单仍 `DRAFT`——拒绝发生在 capture 之前。
那一刻的准入清单见 `r32-admission-409-mr.png`（通过 5 / 失败 1 / 关闭 3）。

恢复后（第 12 步）再提交：`200`，`round=1`、`payloadHash` 为 64 位小写 hex，回放
`GET /deploy-orders/$ORD/flow-snapshots/1` 可读；此后才生成发布单审批待办。

### A.4 复现命令（一段可粘）

```bash
make platform-up && make platform-seed && make platform-demo-reset   # 74/74
# 第 1 步摘 R-04 → 建演示版本 → 挂需求 → 计划两用例全 PASS → test_pass →
# 三签 → 发布单 → 版本 LOCKED → 清空 MR 提交（409 MR_FILLED）→ 恢复 MR 提交（200，快照 round=1）→
# 发布单两级 + 版本三级 → PUBLISHED
bash scripts/release-flow-verify.sh          # 40/40，S7 就是上面这条链的自动版
```

---

## 附 B. r30 时点的实测记录（main = 177389a，已被 r32 取代，保留作对照）

下面这段不是设计意图，是真跑出来的结果与踩到的坑，按顺序复现即可。

### B.1 我跑通到哪一步（r30 时点）

| 步骤 | 结果 |
|---|---|
| 建版本（`releaseLevel=NORMAL`） | `201`，返回 `code=REL-1388`，`allowedActions=["lock","add_requirements","approval","milestone"]` |
| 挂需求 + 填 MR/人天 | `200`（`releaseId` 随之写入需求行） |
| 建计划 `TP-1364` | `201`，`status=PENDING_REVIEW` |
| **批计划** | `admin` / 计划负责人 `200`；**`qa1` 403 `PLAN_APPROVE_FORBIDDEN`**、`tl1` 也 403（见 A.2） |
| 启动 → 执行 PASS → 关轮次 → 完成 | 全部 `200`；执行 `201`，`executionId=12611`，轮次 `610` `CLOSED/PASS`，计划 `COMPLETED` |
| 建报告 + 三签 | `qa1 → pm → tl1` 依次 `200`，最终 `status=SIGNED、approved=3/3`，每步回显 `pendingRole` |
| 质量门评估 | `result=PASS`、`failedRules=[]`；**`snapshotId=0`**（见 A.2 第 4 条） |
| 建发布单 `DEP-0724` | `201`，`status=DRAFT` |
| 直接提交 | `409 RELEASE_NOT_LOCKED`（版本还是 `PLAN`）——**闸门有顺序** |
| 先发起版本审批（`POST /releases/2199/approval`） | `201`，`instanceId=5365`、`nodeKeys=["n1","n2","n3"]`，版本转 `LOCKED` |
| 再提交发布单 | `409 ADMISSION_FAILED`，`failed=["REQ_TEST_PASSED","REQ_APPROVALS_DONE","MR_FILLED","MAN_DAYS_FILLED"]`（逐项带 `fixHint`） |

### B.2 三个必须知道的实操事实（r30 时点，其中两条已并入 A.2）

1. **批计划的人不是 qa1**：`POST /test-plans/{id}/approve` 只允许「计划负责人 / PMO / 管理层」；演示时把 `ownerId` 设成 `admin`（`1`）后用 `admin` 批，或换成真正的 PMO。qa1 只负责执行与登记结果。
2. **用例列表路径是 `/api/deliver/testcases`**，不是 `/test-cases`。后者不返回 404，而是**挂住约 20 秒**（我实测 `curl` 15s 超时 0 字节）——如果照着错的路径调，看起来就像"服务挂了"。建议后续加一条 404 兜底或用例列表索引。
3. **`POST /quality-gates/{releaseId}/evaluate` 返回的是"评审"结果**：`result=PASS` 但 `snapshotId=0`；真正落库的快照是在发布单侧链路里出现的（我这次是 `#727`、`#728`）。所以「版本链路第③段由 MISSING 变 PASS」要用**发布单建好之后**的 `GET /releases/{id}` 看，不要用 evaluate 的返回值判断。

### B.3 这次演示卡在哪（r30 的卡点，r32 已补上）

`DEP-0724` 的 9 项准入实况：**通过 2 / 失败 4 / 关闭 3**。

| # | 项 | 结果 | 原因 |
|---|---|---|---|
| 1 | `REQ_TEST_PASSED` | FAIL | 关联需求 `DEMO-R-12` 仍是 `TODO`，没到「测试通过」 |
| 2 | `NO_OPEN_HIGH_DEFECT` | PASS | 无未关闭 P0/P1 |
| 3 | `REQ_APPROVALS_DONE` | FAIL | 需求自身提报/评审审批未发起 |
| 4 | `MR_FILLED` | FAIL | 需求未填 MR（演示数据里 12 条 `DEMO-R-*` 的 `mr_url` 全为空） |
| 5 | `MAN_DAYS_FILLED` | FAIL | 实际人天未填（预估 2.0 已有） |
| 6 | `QUALITY_GATE` | PASS | 5 条规则全过，快照 `#728` |
| 7–9 | 构建 / Sonar / 制品 | CLOSED | 三个集成未接入，按设计自动关闭（不计 FAIL，也不冒充"已校验"） |

也就是说：**测试计划、报告三签、质量门这三段已经全绿，"卡"在需求自身的流转与字段**——这正是联动该有的样子：版本侧再干净，需求没走到「测试通过」、MR 与人天没填，发布单一格都推不动。

要把这条链走到 `PUBLISHED`，还差的是把一条 RD 走完「提报→评审→排期→开发→提测→测试通过→合入→完成」，再补 MR 与实际人天。注意 `DEMO-R-12` 是 `RD` 且 `TODO`，`submit`/`schedule` 直接返回 `409 ILLEGAL_TRANSITION`（RD 的动作名与 BUS/PDR 不同），演示前先确认该类型的动作名，或改用一条已到 `PENDING_TEST` 的需求。

### B.4 复现命令（一段可粘，r30 时点）

```bash
make platform-up && make platform-seed && make platform-demo-reset
# 建版本 → 挂需求 → 建计划 → admin 批计划 → 执行 → 关轮次 → 完成
# 建报告 → qa1/pm/tl1 三签 → 建发布单 → 发起版本审批 → 提交发布单（会 409 ADMISSION_FAILED）
bash scripts/release-flow-verify.sh          # 32/32（r30 时点；r32 已是 40/40，本段只作对照）
```

截图（r30 时点）：`docs/teamflow/demo-shots/r30-release-chain.png`（版本详情四段链路 + 三级审批阶梯）、`docs/teamflow/demo-shots/r30-deploy-admission.png`（发布单 9 项准入明细 + 质量门 PASS 快照）。
