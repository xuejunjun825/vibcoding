# DEMO · 发版分级审批链（r30）

> 本文件是 r30「分级审批 + 四段同源链路 + 不可变提交快照」的**演示脚本**，命令可直接粘贴执行。
> 约束：演示与实现同账号、同路径、同端口（网关 8080 / req 8082 / deliver 8083），不另起环境、不改数据。
> 截图位统一放在任务夹 `docs/teamflow/20261118-r30-release-approval-chain/evidence/`，由 QA 阶段补图。

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
RD_ID=$(q "SELECT id FROM requirement WHERE tenant_id=$TID AND code='DEMO-R-08' LIMIT 1")
CASE_ID=$(q "SELECT id FROM test_case WHERE tenant_id=$TID ORDER BY id LIMIT 1")
```

> 下文 `→ 200` 指 http 状态；响应体用 `jq` 取字段。演示只读取，不手工改库。

---

## 1. 建一个「常规」版本（NORMAL 三级链）

```bash
REL=$(api "$TOK_PM" POST /api/req/releases '{
  "name":"r30 演示-常规发版","domainId":'"$DOMAIN"',"gateMode":"STRICT",
  "startDate":"2026-11-18","endDate":"2026-11-30","plannedReleaseDate":"2026-12-02",
  "releaseLevel":"NORMAL"}' | sed '$d' | jq -r .release.id)
api "$TOK_PM" GET "/api/req/releases/$REL" | head -1 | jq '.release | {code,releaseLevel,releaseLevelLabel,status}'
```
→ `200`，`releaseLevel:"NORMAL"`、`releaseLevelLabel:"常规"`、`status:"PLAN"`；`approvalLevels` 为空（未发起）。
`domainId` 取演示域：`mysql -N -e "SELECT id FROM devops_platform.biz_domain LIMIT 1"`。

**截图位 1**：版本详情「概览」顶部四段链路（全 MISSING）+ 侧栏等级「常规」。

## 2. 把需求挂进版本

```bash
api "$TOK_PM" POST "/api/req/releases/$REL/requirements" \
  '{"requirementIds":['"$RD_ID"']}'
```
→ `200`；`RD_ID` 用 `make platform-demo-reset` 后的 `DEMO-R-08`（已填 MR/工时，能过 9 项准入）。
此时 `flowChain.deployAdmission.status:"MISSING"`（还没有发布单），`testPlan.status:"MISSING"`。

## 3. 建测试计划并跑一轮 PASS

```bash
PLAN=$(api "$TOK_QA" POST /api/deliver/test-plans '{
  "releaseId":'"$REL"',"name":"r30 演示计划","ownerId":1,
  "windowStart":"2026-11-18","windowEnd":"2026-11-25","caseIds":['"$CASE_ID"'],"gateMode":"STRICT"}' \
  | sed '$d' | jq -r .plan.id)
for a in submit-review approve start; do api "$TOK_QA" POST "/api/deliver/test-plans/$PLAN/$a" >/dev/null; done
api "$TOK_QA" POST "/api/deliver/test-plans/$PLAN/execute" \
  '{"caseId":'"$CASE_ID"',"result":"PASS","note":"r30 演示"}'
RID=$(mysql -N -e "SELECT id FROM devops_platform.test_plan_round WHERE plan_id=$PLAN AND active=1")
api "$TOK_QA" POST "/api/deliver/test-plans/$PLAN/rounds/$RID/close" '{"conclusion":"PASS","note":"全通过"}'
api "$TOK_QA" POST "/api/deliver/test-plans/$PLAN/complete"
```
→ 计划 `APPROVED→IN_PROGRESS→COMPLETED`；版本详情的 `flowChain.testPlan` 由 `MISSING` 变 `PASS`，
`allApproved:true`、`plans[0].conclusion:"PASS"`。

**截图位 2**：链路第①段「测试计划」由虚线的「暂无」翻成 PASS。

## 4. 测试报告与三签（qa1 → pm → tl1）

```bash
RPT=$(api "$TOK_QA" POST /api/deliver/test-reports \
  '{"scope":"RELEASE","releaseId":'"$REL"',"note":"r30 演示报告"}' | sed '$d' | jq -r .report.id)
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
→ `result:"PASS"`、`failedRules:[]`；链路第③段 `qualityGate.result:"PASS"`、`computedAt` 非空。
门是**只读快照**：之后不再调用 evaluate，链路与快照读的都是这一份。

**截图位 4**：链路第③段「质量门」PASS + 规则明细。

## 6. 建发布单（含 9 项准入）

```bash
ORD=$(api "$TOK_PM" POST /api/req/deploy-orders '{
  "releaseId":'"$REL"',"env":"PROD",
  "windowStart":"2026-12-02T22:00:00+08:00","windowEnd":"2026-12-03T02:00:00+08:00",
  "artifactVersion":"r30-demo-1","rollbackPlan":"回滚到 r29 镜像","impact":"演示环境",
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

## 7. 发起审批并提交：快照就地固化

```bash
# 7a 版本进入 LOCKED（此后等级/需求都不可改）
api "$TOK_PM" POST "/api/req/releases/$REL/approval" | head -1 | jq '{instanceId,nodeKeys}'
# → {"instanceId":N,"nodeKeys":["n1","n2","n3"]}

# 7b 提交发布单：闸门顺序 计划→LOCKED→准入→门→快照→审批
api "$TOK_PM" POST "/api/req/deploy-orders/$ORD/submit" '{}' | head -1 | jq '{status}'
api "$TOK_PM" GET "/api/req/deploy-orders/$ORD" | head -1 \
  | jq '.flowSnapshot | {round,payloadHash,changedSinceSubmit}'
```
→ 提交 `200`；`flowSnapshot.round:1`、`payloadHash` 64 位小写 hex、`changedSinceSubmit:false`、
`flowSnapshotHistory[{round:1,...}]`。回放：`GET /api/req/deploy-orders/$ORD/flow-snapshots/1`。
提交后才产生 `PENDING_APPROVAL`，发布单审批链（两级）此时才有待办。

**截图位 6**：发布单详情「提交回执」卡：第 1 轮 + 12 位 hash + 段级「已变化」无印章。

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
ORD2=$(api "$TOK_PM" POST /api/req/deploy-orders '{...同步骤 6，artifactVersion:"r30-demo-2"...}' | sed '$d' | jq -r .order.id)
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
make release-flow-verify     # S1..S6 32 条，PASS 必须等于 TOTAL；结束自动 demo-reset
make release-flow-upgrade    # 连跑两次 /internal/seed，定义/绑定/version 不增（12 条）
```

`release-flow-verify` 的 `--static-only` 只跑不连栈的契约检查（脚本语法/断言分母/Makefile/DEMO 文档/EXIT trap），
用于改脚本时快速自检；正式门禁必须连栈跑满 32 条，**缺前置直接失败，不跳过**。
