#!/usr/bin/env bash
# platform-test-negative.sh — TECHNICAL §8.3 的 18 段负向用例（r17 起含动作级授权）。
# r42 增 18) 段：8080 与直连 8082 同一中文 keyword 的响应体逐字节 cmp 一致、.total 同源（网关只透传）。
# r23 增 4b) 段：流转前置门（拆解/直建/改父共用一道门）+ 提报 domainIds 必填，含正向回归。
# r26 增 4c) 段：工时评估门（六态拦人、驳回后仍拦、改人天超 20% 派生 STALE、越权提交 403）。
# r27 增 4d) 段：列表/搜索/详情四处 allowedActions 原始数组逐字符相等（R27_PARITY_MUTATE=order 变异自检）。
# r24 增 17) 段：VENDOR_PM 的第 11 个角色边界（厂商隔离 / 12 条写口 DENY / 账号必须绑厂商）。
# r28 重写 7/8 段：段内自造夹具（biz1 BUS→submit→pm 待办），两条断言恒定，夹具步骤只 fail 不计数。
# 每条都断言两件事：被拒绝（4xx + 错误码），且**没有落库**（表计数不变）。
# 绿色 = 非法路径确实走不通；不是"接口返回 200 但界面拦住了"。
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
# r34：夹具生命周期（退出复位 + 基线断言）只有一份实现，见 scripts/lib/gate-fixture.sh。
# 过去本脚本没有 trap：所有 neg-* 夹具留在演示租户里，后跑的门禁就得多看一堆脏数据。
# shellcheck source=lib/gate-fixture.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/gate-fixture.sh"

TOTAL=0; PASS=0; FAIL=0; FAILED_LINES=""
RUN_TAG="$(date +%H%M%S)"
TENANT="${PLATFORM_TENANT:-t1}"
ADMIN_USER="${PLATFORM_ADMIN_USER:-admin}"
ADMIN_PASS="${PLATFORM_ADMIN_PASS:-Admin@12345}"
HINT="需要 make platform-up && make platform-seed"

section() { printf '\n\033[1;34m---- %s ----\033[0m\n' "$*"; }
pass() { TOTAL=$((TOTAL+1)); PASS=$((PASS+1)); printf '  \033[1;32mPASS\033[0m %s\n' "$1"; }
fail() {
    TOTAL=$((TOTAL+1)); FAIL=$((FAIL+1))
    printf '  \033[1;31mFAIL\033[0m %s\n' "$1"
    [[ -n "${2:-}" ]] && printf '       修复: %s\n' "$2"
    FAILED_LINES="${FAILED_LINES}
  - $1"
}
deny() { # desc want_code needle fix
    if [[ "$HTTP_CODE" == "$2" ]] && { [[ -z "${3:-}" ]] || grep -qF -- "$3" <<<"$HTTP_BODY"; }; then
        pass "$1"
    else
        fail "$1 (期望 ${2}${3:+/含 '$3'}，实际 ${HTTP_CODE}: $(printf '%s' "$HTTP_BODY" | head -c 200))" "${4:-$HINT}"
    fi
}
same_count() { # desc table where before
    local now; now="$(db_count "$2" "$3")"
    if [[ "$now" == "$4" ]]; then pass "$1（计数不变 ${now}）"; else fail "$1（计数 ${4}→${now}，非法请求落库了）" "拒绝必须发生在写入之前"; fi
}
jget_() { printf '%s' "$1" | jq -r "$2" 2>/dev/null || true; }

req_ready || { err "req-service 未就绪，修复: make platform-up"; exit 1; }
deliver_ready || { err "deliver-service 未就绪，修复: make platform-up"; exit 1; }

GATE_FIXTURE_TENANT_CODE="$TENANT"
GATE_FIXTURE_USERS="pm tl1 vendor1 mgr1 admin qa1 po1"
# 起跑自愈 + 注册唯一 EXIT/INT/TERM 陷阱：必须在登录之前（复位可能重建种子账号）。
gate_fixture_begin || { err "gate-fixture 起跑失败（见上方 [gate] 输出）"; exit 1; }

api_call POST /api/req/auth/login "{\"tenantCode\":\"${TENANT}\",\"username\":\"${ADMIN_USER}\",\"password\":\"${ADMIN_PASS}\"}"
[[ "$HTTP_CODE" == "200" ]] || { err "管理员登录失败，修复: make platform-seed"; exit 1; }
API_TOKEN="$(jget_ "$HTTP_BODY" '.token')"; API_TENANT="$(jget_ "$HTTP_BODY" '.tenantId')"
ADMIN_TOKEN="$API_TOKEN"
TID="$(db_q "SELECT id FROM sys_tenant WHERE code='${TENANT}'")"
DOMAIN1="$(db_q "SELECT id FROM biz_domain WHERE tenant_id=${TID} ORDER BY id LIMIT 1")"
[[ -n "$DOMAIN1" ]] || DOMAIN1=1
ok "管理员登录完成（tenant=${TENANT}）"

# ---------------------------------------------------------------- 1
section "1) 缺 X-Tenant-Id 建需求 → 403 且不落库"
BEFORE="$(db_count requirement "tenant_id=${TID}")"
SAVED="$API_TENANT"; API_TENANT=""
api_call POST /api/req/requirements "{\"type\":\"RD\",\"title\":\"neg-no-tenant-${RUN_TAG}\",\"domainIds\":[$DOMAIN1]}"
deny "缺租户头创建需求 → 403" 403 "" "鉴权先于业务；AuthFilter 必须 fail-closed"
same_count "缺头请求未落库" requirement "tenant_id=${TID}" "$BEFORE"
API_TENANT="$SAVED"

# ---------------------------------------------------------------- 2
section "2) 厂商 B 直连厂商 A 的需求/缺陷/附件 → 403"
login_as() {
    api_call POST /api/req/auth/login "{\"tenantCode\":\"${TENANT}\",\"username\":\"$1\",\"password\":\"$2\"}"
    [[ "$HTTP_CODE" == "200" ]] && { API_TOKEN="$(jget_ "$HTTP_BODY" '.token')"; return 0; }
    return 1
}
V1_ID="$(db_q "SELECT id FROM sys_vendor WHERE tenant_id=${TID} ORDER BY id LIMIT 1")"
V2_ID="$(db_q "SELECT id FROM sys_vendor WHERE tenant_id=${TID} ORDER BY id DESC LIMIT 1")"
REQ_A="$(db_q "SELECT id FROM requirement WHERE tenant_id=${TID} AND owner_vendor_id=${V1_ID} ORDER BY id LIMIT 1")"
DEF_A="$(db_q "SELECT id FROM defect WHERE tenant_id=${TID} AND vendor_id=${V1_ID} ORDER BY id LIMIT 1")"
if login_as "vendor2" "Passw0rd!"; then
    if [[ -n "$REQ_A" ]]; then
        api_call GET "/api/req/requirements/${REQ_A}"
        deny "厂商 B 读厂商 A 需求 → 403" 403 "" "ScopeGuard.requireVisible 需按 vendor_id 判定"
    else
        fail "种子里没有厂商 A 的需求，无法造夹具" "make platform-seed 必须造跨厂商数据"
    fi
    if [[ -n "$DEF_A" ]]; then
        api_call GET "/api/deliver/defects/${DEF_A}"
        deny "厂商 B 读厂商 A 缺陷 → 403" 403 "" "缺陷详情同样走 ScopeGuard"
    else
        warn "种子里没有厂商 A 的缺陷，跳过缺陷断言"
    fi
    api_call GET "/api/req/attachments/999999/download"
    if [[ "$HTTP_CODE" == "403" || "$HTTP_CODE" == "404" ]]; then pass "越权附件下载被拒 (${HTTP_CODE})"; else fail "越权附件下载返回 ${HTTP_CODE}" "附件必须按附件自身 domain/vendor 判定"; fi
else
    fail "vendor2 登录失败" "make platform-seed 必须创建 vendor2/Passw0rd!"
fi
API_TOKEN="$ADMIN_TOKEN"

# ---------------------------------------------------------------- 3
section "3) PATCH 带 status → 400；4) RD 跳步 → 409"
# ---- r34：父链自建（不再「按状态取第一条 PDR」）----
# 旧写法在干净态取到的是种子 DEMO-R-02 的 PDR，于是负向夹具的子需求挂在演示需求上，
# 把 DEMO-R-02 的汇总人天从 5.0 推到 6.5、评估单被 20% 阈值判 STALE——后跑的门禁于是看到
# 与前一个门禁不同的世界（顺序依赖的真正来源）。自建 BUS→POOLED→PDR 后，写入只落在本轮
# GATE_TAG 上，退出即被 gate-fixture 复位，两个门禁互不可见。
# 夹具步骤一律「只 fail 不计数」：分母只统计断言，不能被建链步骤挤动。
api_call POST /api/req/requirements "{\"type\":\"BUS\",\"title\":\"gate-${GATE_TAG}-BUS\",\"domainIds\":[$DOMAIN1]}"
GATE_NEG_BUS="$(jget_ "$HTTP_BODY" '.id')"
[[ -n "$GATE_NEG_BUS" && "$GATE_NEG_BUS" != "null" ]] \
    || { err "建负向父链 BUS 失败：$(printf '%s' "$HTTP_BODY" | head -c 200)"; exit 1; }
gate_pool_bus "$GATE_NEG_BUS" "negative 父链 BUS" \
    || { err "负向父链 BUS 未入池（PDR 只能挂 POOLED 的 BUS）"; exit 1; }
api_call POST /api/req/requirements "{\"type\":\"PDR\",\"title\":\"gate-${GATE_TAG}-PDR\",\"parentId\":${GATE_NEG_BUS},\"domainIds\":[$DOMAIN1]}"
PDR_ID="$(jget_ "$HTTP_BODY" '.id')"
[[ -n "$PDR_ID" && "$PDR_ID" != "null" ]] \
    || { err "建负向父链 PDR 失败：$(printf '%s' "$HTTP_BODY" | head -c 200)"; exit 1; }
api_call POST "/api/req/requirements/${PDR_ID}/estimate" '{"estimateDays":2,"note":"r34 负向门禁父链 PDR"}'
[[ "$HTTP_CODE" == "201" ]] \
    || { err "负向父链 PDR 提交评估失败（HTTP ${HTTP_CODE}）：$(printf '%s' "$HTTP_BODY" | head -c 200)"; exit 1; }
gate_approve_biz "$PDR_ID" WBS_ESTIMATE \
    || { err "负向父链 PDR 评估未通过（审批待办为空或同意失败）"; exit 1; }
api_call POST "/api/req/requirements/${PDR_ID}/actions" '{"action":"schedule"}'
[[ "$HTTP_CODE" == "200" ]] \
    || { err "负向父链 PDR 排期失败（HTTP ${HTTP_CODE}）：$(printf '%s' "$HTTP_BODY" | head -c 200)"; exit 1; }
api_call POST /api/req/requirements "{\"type\":\"RD\",\"title\":\"neg-sm-${RUN_TAG}\",\"parentId\":${PDR_ID},\"domainIds\":[$DOMAIN1],\"priority\":\"P2\",\"expectedDeliveryDate\":\"2026-12-31\"}"
RD_ID="$(jget_ "$HTTP_BODY" '.id')"
[[ -n "$RD_ID" && "$RD_ID" != "null" ]] || { err "创建负向夹具 RD 失败: $(printf '%s' "$HTTP_BODY" | head -c 200)"; exit 1; }
api_call PATCH "/api/req/requirements/${RD_ID}" '{"status":"DONE"}'
deny "PATCH status → 400 STATUS_NOT_WRITABLE" 400 "STATUS_NOT_WRITABLE" "StatusForbiddenDto 的 setter 必须抛 400"
api_call POST "/api/req/requirements/${RD_ID}/actions" '{"action":"finish"}'
deny "RD TODO --finish--> 409（跳步）" 409 "ILLEGAL_TRANSITION" "状态表是唯一来源，非法动作给 allowedTargets"
# r26 起 start_dev 还受工时评估门约束：这张夹具没有评估单，所以这步会 409 并停在 TODO，
# 下面的 test_pass 依旧从 TODO 触发（断言仍成立）。评估门本身在 4c) 段逐态断言。
api_call POST "/api/req/requirements/${RD_ID}/actions" '{"action":"start_dev"}'
api_call POST "/api/req/requirements/${RD_ID}/actions" '{"action":"test_pass"}'
deny "RD IN_DEV --test_pass--> 409（未提交测试不可通过）" 409 "ILLEGAL_TRANSITION"

# ---------------------------------------------------------------- 4b
section "4b) 流转前置门（r23）：拆解/直建/改父共用同一道门；提报 domainIds 必填"
allow() { # desc want_code needle fix —— 正向回归断言，与 deny 同构
    if [[ "$HTTP_CODE" == "$2" ]] && { [[ -z "${3:-}" ]] || grep -qF -- "$3" <<<"$HTTP_BODY"; }; then
        pass "$1"
    else
        fail "$1 (期望 ${2}${3:+/含 '$3'}，实际 ${HTTP_CODE}: $(printf '%s' "$HTTP_BODY" | head -c 200))" "${4:-$HINT}"
    fi
}

# 门必须在写入之前拒绝：每条拒绝都配 requirement 计数或 parent_id 未变的断言。
# 1) 正向回归：只有已入池（POOLED）的 BUS 能拆出 PDR（AC-5）。
SEED_POOLED_BUS="$(db_q "SELECT id FROM requirement WHERE tenant_id=${TID} AND type='BUS' AND status='POOLED' ORDER BY id LIMIT 1")"
[[ -n "$SEED_POOLED_BUS" ]] || { err "种子里没有 POOLED 的 BUS，前置门正向回归无法执行：make platform-seed"; exit 1; }
api_call POST "/api/req/requirements/${SEED_POOLED_BUS}/decompose" "{\"title\":\"neg-gate-pdr-${RUN_TAG}\",\"domainIds\":[$DOMAIN1]}"
allow "已入池 BUS 拆解 → 201（正向回归 AC-5）" 201 ""
GATE_PDR="$(jget_ "$HTTP_BODY" '.id')"
[[ -n "$GATE_PDR" && "$GATE_PDR" != "null" ]] || { err "拆解入池 BUS 未返回子需求 id，后续前置门用例无法执行"; exit 1; }

# 2) 未入池 BUS（新建停在 SUBMITTED）不可拆解出 PDR。
api_call POST /api/req/requirements "{\"type\":\"BUS\",\"title\":\"neg-gate-bus-${RUN_TAG}\",\"domainIds\":[$DOMAIN1]}"
SUBMITTED_BUS="$(jget_ "$HTTP_BODY" '.id')"
[[ -n "$SUBMITTED_BUS" && "$SUBMITTED_BUS" != "null" ]] || { err "创建未入池 BUS 夹具失败：$(printf '%s' "$HTTP_BODY" | head -c 200)"; exit 1; }
BEFORE_GATE="$(db_count requirement "tenant_id=${TID}")"
api_call POST "/api/req/requirements/${SUBMITTED_BUS}/decompose" "{\"title\":\"neg-gate-pdr2-${RUN_TAG}\",\"domainIds\":[$DOMAIN1]}"
deny "未入池 BUS 拆解 → 409 PARENT_NOT_POOLED" 409 "PARENT_NOT_POOLED"
deny "409 detail 指明父状态要求 POOLED" 409 '"required":"POOLED"'
same_count "未入池 BUS 拆解未落库" requirement "tenant_id=${TID}" "$BEFORE_GATE"

# 3) 直建入口与拆解同源：未入池 BUS 下直建 PDR 同样 409。
api_call POST /api/req/requirements "{\"type\":\"PDR\",\"title\":\"neg-gate-pdr3-${RUN_TAG}\",\"parentId\":${SUBMITTED_BUS},\"domainIds\":[$DOMAIN1]}"
deny "未入池 BUS 下直建 PDR → 409 PARENT_NOT_POOLED" 409 "PARENT_NOT_POOLED"
same_count "直建 PDR 被拒未落库" requirement "tenant_id=${TID}" "$BEFORE_GATE"

# 4) 未排期 PDR（上一步产物停在 PENDING_ANALYSIS）不可拆解：RD 只能由 SCHEDULED/IN_DEV 的 PDR 产出。
api_call POST "/api/req/requirements/${GATE_PDR}/decompose" "{\"title\":\"neg-gate-rd0-${RUN_TAG}\",\"domainIds\":[$DOMAIN1]}"
deny "未排期 PDR 拆解 → 409 PARENT_NOT_SCHEDULED" 409 "PARENT_NOT_SCHEDULED"
deny "409 detail 给出允许的父状态 SCHEDULED/IN_DEV" 409 '"allowed":["SCHEDULED","IN_DEV"]'
same_count "未排期 PDR 拆解未落库" requirement "tenant_id=${TID}" "$BEFORE_GATE"

# 5) 未排期 PDR 下直建 RD 同样 409（三条写入口同码同结构）。
api_call POST /api/req/requirements "{\"type\":\"RD\",\"title\":\"neg-gate-rd1-${RUN_TAG}\",\"parentId\":${GATE_PDR},\"domainIds\":[$DOMAIN1]}"
deny "未排期 PDR 下直建 RD → 409 PARENT_NOT_SCHEDULED" 409 "PARENT_NOT_SCHEDULED"
same_count "直建 RD 被拒未落库" requirement "tenant_id=${TID}" "$BEFORE_GATE"

# 6) PUT 改父复用同一道门：把已有 RD 改挂到未排期 PDR 必须 409，且 parent_id 不变。
PARENT_BEFORE="$(db_q "SELECT parent_id FROM requirement WHERE id=${RD_ID}")"
api_call PUT "/api/req/requirements/${RD_ID}" "{\"parentId\":${GATE_PDR}}"
deny "RD 改挂未排期 PDR → 409 PARENT_NOT_SCHEDULED" 409 "PARENT_NOT_SCHEDULED"
PARENT_AFTER="$(db_q "SELECT parent_id FROM requirement WHERE id=${RD_ID}")"
if [[ -n "$PARENT_BEFORE" && "$PARENT_AFTER" == "$PARENT_BEFORE" ]]; then
    pass "改父被拒后 parent_id 未变（${PARENT_AFTER}）"
else
    fail "改父被拒但 parent_id ${PARENT_BEFORE}→${PARENT_AFTER}" "门必须在写入之前拒绝"
fi

# 7) 提报必填：直建入口缺 domainIds → 400 且 detail 指向字段，不落库。
api_call POST /api/req/requirements "{\"type\":\"BUS\",\"title\":\"neg-gate-nodomain-${RUN_TAG}\"}"
deny "提报缺 domainIds → 400 VALIDATION_FAILED" 400 "VALIDATION_FAILED"
deny "400 detail 指向 domainIds 字段" 400 '"field":"domainIds"'
same_count "缺 domainIds 提报未落库" requirement "tenant_id=${TID}" "$BEFORE_GATE"

# 8) 正向回归：合法父直建 RD 201，改挂到另一合法 PDR 与改回原父都 200（门不误伤合法写）。
LEGAL_PDR="$(db_q "SELECT id FROM requirement WHERE tenant_id=${TID} AND type='PDR' AND status IN ('SCHEDULED','IN_DEV') AND id<>${PDR_ID} ORDER BY id LIMIT 1")"
[[ -n "$LEGAL_PDR" ]] || { err "种子里没有第二个可用的 PDR 父（SCHEDULED/IN_DEV）：make platform-seed"; exit 1; }
api_call POST /api/req/requirements "{\"type\":\"RD\",\"title\":\"neg-gate-rd-ok-${RUN_TAG}\",\"parentId\":${PDR_ID},\"domainIds\":[$DOMAIN1]}"
allow "合法父 PDR 下直建 RD → 201（正向回归）" 201 ""
GATE_RD="$(jget_ "$HTTP_BODY" '.id')"
api_call PUT "/api/req/requirements/${GATE_RD}" "{\"parentId\":${LEGAL_PDR}}"
allow "RD 改挂另一合法 PDR → 200" 200 ""
api_call PUT "/api/req/requirements/${GATE_RD}" "{\"parentId\":${PDR_ID}}"
allow "RD 改回原父 → 200（合法写不被误伤）" 200 ""

# ---------------------------------------------------------------- 4c
section "4c) 工时评估门（r26）：未评估/待审核/已驳回/已失效都拦，通过才放行，越权 403"
# 夹具沿用 4b 的 GATE_RD（父 PDR_ID=SCHEDULED、状态 TODO），它没有评估单 → 六态里的 NONE。
# 判定顺序必须是 403 → ILLEGAL_TRANSITION → r23 前置门 → 409 ESTIMATE_NOT_APPROVED：
# TODO→start_dev 是合法流转，所以这里命中的是评估门本身，而不是状态机或父状态门。
api_call POST "/api/req/requirements/${GATE_RD}/actions" '{"action":"start_dev"}'
deny "未评估 RD 开始开发 → 409 ESTIMATE_NOT_APPROVED" 409 "ESTIMATE_NOT_APPROVED" \
    "门必须在 RequirementService.transition 里、状态判定之后抛 409"
deny "409 detail 给出六态 status=NONE" 409 '"status":"NONE"' "前端按 detail.status 就地刷新卡片，不能只给一句文案"
deny "409 detail 给出 required=ESTIMATE_APPROVED" 409 '"required":"ESTIMATE_APPROVED"' "缺什么必须机器可读"

# 未评估的 PDR 同样排不了期（AC-16 要求 schedule 与 start_dev 成对覆盖）。
api_call POST "/api/req/requirements/${GATE_PDR}/actions" '{"action":"schedule"}'
deny "未评估 PDR 排期 → 409 ESTIMATE_NOT_APPROVED" 409 "ESTIMATE_NOT_APPROVED" \
    "两个受门动作（schedule/start_dev）必须同源判定"

# 待审核（PENDING）同样拦：单据在途 ≠ 已通过。
api_call POST "/api/req/requirements/${GATE_RD}/estimate" '{"estimateDays":2,"note":"负向：待审核"}'
allow "提交工时评估单 → 201（负向夹具就绪）" 201 "" "POST /{id}/estimate 与 /{id}/deliver 同址同风格"
GATE_EST_INST="$(jget_ "$HTTP_BODY" '.instanceId')"
api_call POST "/api/req/requirements/${GATE_RD}/actions" '{"action":"start_dev"}'
deny "待审核 RD 开始开发 → 409 ESTIMATE_NOT_APPROVED(status=PENDING)" 409 '"status":"PENDING"' \
    "PENDING 不是通过，门不得放行"

# 驳回：结论与原因都要留痕，驳回后门仍关。
login_as "pm" "Passw0rd!" || fail "pm 登录失败（工时评估审批）" "make platform-seed 必须创建 pm/Passw0rd!"
api_call GET /api/req/approvals/todo?page=1\&size=100
EST_TASK="$(jget_ "$HTTP_BODY" "[.items[] | select(.bizId == ${GATE_RD} and .bizType == \"WBS_ESTIMATE\")][0].taskId")"
if [[ -n "$EST_TASK" && "$EST_TASK" != "null" ]]; then
    # D-01（AC-4）：驳回必须带原因。引擎级约束，所有五类单据共用；校验必须发生在任何写入之前。
    api_call POST "/api/req/approvals/tasks/${EST_TASK}/actions" '{"action":"REJECT"}'
    deny "驳回不带原因 → 400 REASON_REQUIRED" 400 "REASON_REQUIRED" \
        "原因缺失必须拒绝，不能静默驳回成 REJECTED"
    api_call POST "/api/req/approvals/tasks/${EST_TASK}/actions" '{"action":"REJECT","comment":"   "}'
    deny "驳回原因全空白 → 400 REASON_REQUIRED" 400 "REASON_REQUIRED" "空白等于没填，同样 400"
    api_call GET "/api/req/approvals/instances/${GATE_EST_INST}"
    deny "两次非法驳回后实例仍 PENDING（未落驳回结果）" 200 '"status":"PENDING"' \
        "校验要在 task/instance 写入之前，否则待办已被吃掉"
    api_call POST "/api/req/approvals/tasks/${EST_TASK}/actions" '{"action":"REJECT","comment":"人天评估偏高，重新拆"}'
    allow "驳回工时评估（带原因）→ 200" 200 "" ""
else
    fail "审批人待办里没有工时评估单（instance=${GATE_EST_INST:-?}）" "或签节点必须解析出域负责人/产品经理"
fi
api_call GET "/api/req/requirements/${GATE_RD}"
deny "驳回原因进需求动态/评估块" 200 "人天评估偏高，重新拆" "结论文案要能让研发看到为什么被驳回"
API_TOKEN="$ADMIN_TOKEN"
api_call POST "/api/req/requirements/${GATE_RD}/actions" '{"action":"start_dev"}'
deny "已驳回 RD 开始开发 → 409 ESTIMATE_NOT_APPROVED(status=REJECTED)" 409 '"status":"REJECTED"' \
    "驳回后必须重新提交并审批通过才能排期/开发"

# 通过 → 改人天超 20% → 读时派生 STALE，门再次关；改回后门重新打开（同一函数派生，AC-11）。
api_call POST "/api/req/requirements/${GATE_RD}/estimate" '{"estimateDays":2,"note":"负向：重提"}'
allow "重提工时评估单 → 201（旧单转 SUPERSEDED）" 201 "" "重提必须先把旧 PENDING 置终态，否则唯一键冲突"
login_as "pm" "Passw0rd!" || warn "pm 登录失败（重提审批）"
api_call GET /api/req/approvals/todo?page=1\&size=100
EST_TASK2="$(jget_ "$HTTP_BODY" "[.items[] | select(.bizId == ${GATE_RD} and .bizType == \"WBS_ESTIMATE\")][0].taskId")"
[[ -n "$EST_TASK2" && "$EST_TASK2" != "null" ]] \
    && api_call POST "/api/req/approvals/tasks/${EST_TASK2}/actions" '{"action":"APPROVE","comment":"重提通过"}'
API_TOKEN="$ADMIN_TOKEN"
api_call PUT "/api/req/requirements/${GATE_RD}" '{"estimateDays":3}'
allow "通过后改人天到 3（偏差 50%）→ 200" 200 "" "写路径不改：STALE 是读时派生的"
api_call GET "/api/req/requirements/${GATE_RD}"
deny "detail.estimate.status 派生为 STALE" 200 '"status":"STALE"' "偏差 > 20% 即失效，判定只在服务端一处"
api_call POST "/api/req/requirements/${GATE_RD}/actions" '{"action":"start_dev"}'
deny "已失效 RD 开始开发 → 409 ESTIMATE_NOT_APPROVED(status=STALE)" 409 '"status":"STALE"' \
    "STALE 必须与 PENDING/REJECTED 同样拦人"
api_call PUT "/api/req/requirements/${GATE_RD}" '{"estimateDays":2}'
api_call POST "/api/req/requirements/${GATE_RD}/actions" '{"action":"start_dev"}'
allow "人天改回评估值 → 门重新放行 start_dev 200" 200 "" "六态与门判定同源：偏差收敛即恢复"

# 越权：厂商账号没有 req:deliver，提交评估 403（且不落库）。
if login_as "vpm1" "Passw0rd!"; then
    BEFORE_EST="$(db_count wf_instance "tenant_id=${TID} AND biz_type='WBS_ESTIMATE'")"
    api_call POST "/api/req/requirements/${GATE_RD}/estimate" '{"estimateDays":1,"note":"越权"}'
    deny "厂商账号提交工时评估 → 403" 403 "" "req:deliver 缺失必须 403，不能落一张单"
    same_count "越权提交未建单" wf_instance "tenant_id=${TID} AND biz_type='WBS_ESTIMATE'" "$BEFORE_EST"
    API_TOKEN="$ADMIN_TOKEN"
else
    fail "vpm1 登录失败（评估越权用例）" "make platform-seed 必须创建 vpm1/Passw0rd!"
fi

# ---------------------------------------------------------------- 4d
section "4d) 列表/搜索/详情同源（r27）：同一调用者、同一 id，四处 allowedActions 原始数组逐字符相等"
# AC-1/AC-4/AC-5/AC-6/AC-7 的断言口径：只比较 jq -c 后的原始数组文本（含顺序），
# 禁止 length/包含/sort 替代——「列表漏了门」正是靠字符串不等暴露的。
# 四处 = 列表行（?keyword=）、搜索结果行（?q=）、详情根、详情 .view。
# R27_PARITY_MUTATE=order 把列表侧数组翻转，用于自检这几条断言真的在比顺序。
actions_of() { printf '%s' "$1" | jq -c '.allowedActions // []' 2>/dev/null || printf 'ERR'; }
row_actions() { printf '%s' "$2" | jq -c --argjson id "$1" '(.items // [])[] | select(.id==$id) | .allowedActions' 2>/dev/null || printf 'ERR'; }
parity() { # desc id keyword jq-cond
    local desc="$1" id="$2" kw="$3" cond="$4" row srow droot dview
    api_call GET "/api/req/requirements?keyword=${kw}&size=50"
    row="$(row_actions "$id" "$HTTP_BODY")"
    api_call GET "/api/req/requirements/search?q=${kw}&size=50"
    srow="$(row_actions "$id" "$HTTP_BODY")"
    api_call GET "/api/req/requirements/${id}"
    droot="$(actions_of "$HTTP_BODY")"
    dview="$(printf '%s' "$HTTP_BODY" | jq -c '.view.allowedActions // []' 2>/dev/null || printf 'ERR')"
    [[ "${R27_PARITY_MUTATE:-}" == "order" ]] && row="$(printf '%s' "$row" | jq -c 'reverse' 2>/dev/null || printf 'ERR')"
    if [[ -z "$row" || -z "$srow" || -z "$droot" || -z "$dview" ]]; then
        fail "${desc}：四处里有一处没取到 allowedActions（列表='${row}' 搜索='${srow}' 详情='${droot}' view='${dview}'）" \
            "keyword=标题 与 q=标题 都必须命中同一 id；列表行缺失说明池查询把该 id 过滤掉了"
        return
    fi
    if [[ "$row" != "$srow" || "$row" != "$droot" || "$row" != "$dview" ]]; then
        fail "${desc}：四处不同源（列表${row} 搜索${srow} 详情${droot} view${dview}）" \
            "判定只能有 RequirementService 一处：池/搜索/导出/详情都必须调它（AC-2）"
        return
    fi
    if ! printf '%s' "$row" | jq -e "$cond" >/dev/null 2>&1; then
        fail "${desc}：四处一致但内容不符（${row} 不满足 ${cond}）" \
            "权限矩阵 ∩ 状态机 ∩ r26 评估门，三者同时成立才算对"
        return
    fi
    pass "${desc}（${row}）"
}
approve_est() { # req_id desc —— 产品经理初审通过一张 WBS_ESTIMATE 单
    local rid="$1" desc="$2" saved="$API_TOKEN" task code body
    if ! login_as "pm" "Passw0rd!"; then
        fail "${desc}：pm 登录失败" "make platform-seed 必须创建 pm/Passw0rd!"
        API_TOKEN="$saved"; return
    fi
    api_call GET /api/req/approvals/todo?page=1\&size=200
    task="$(jget_ "$HTTP_BODY" "[.items[] | select(.bizId == ${rid} and .bizType == \"WBS_ESTIMATE\")][0].taskId")"
    if [[ -z "$task" || "$task" == "null" ]]; then
        fail "${desc}：审批待办里没有 WBS_ESTIMATE 单（bizId=${rid}）" "或签节点必须解析到域负责人/产品经理"
        API_TOKEN="$saved"; return
    fi
    api_call POST "/api/req/approvals/tasks/${task}/actions" '{"action":"APPROVE","comment":"r27 4d 初审通过"}'
    code="$HTTP_CODE"; body="$HTTP_BODY"
    API_TOKEN="$saved"
    if [[ "$code" == "200" ]]; then pass "${desc}（初审通过）"
    else fail "${desc}：审批返回 ${code}" "$(printf '%s' "$body" | head -c 160)"; fi
}

# 4d.1 未评估 PDR：六态 NONE → schedule 被门摘掉，四处必须都是 []。
R27_PDR_KW="$(db_q "SELECT title FROM requirement WHERE id=${GATE_PDR}")"
parity "4d.1 未评估 PDR 四处一致且为 []" "$GATE_PDR" "$R27_PDR_KW" 'length == 0'

# 4d.2 同一 PDR 过门后：四处都含 schedule（列表不能再停在状态机直出的旧值）。
api_call POST "/api/req/requirements/${GATE_PDR}/estimate" '{"estimateDays":2,"note":"r27 4d：PDR 评估"}'
allow "4d.2 提交 PDR 工时评估 → 201" 201 "" "POST /{id}/estimate 与 /{id}/deliver 同址同风格"
approve_est "$GATE_PDR" "4d.2 PDR 评估初审"
parity "4d.2 PDR 评估通过后四处一致且含 schedule" "$GATE_PDR" "$R27_PDR_KW" 'any(.[]; . == "schedule")'

# 4d.3 已通过评估的 RD：start_dev 必须出现在四处（4c 的 GATE_RD 已进 IN_DEV，另造一张干净的）。
api_call POST /api/req/requirements "{\"type\":\"RD\",\"title\":\"neg-r27-rd-${RUN_TAG}\",\"parentId\":${PDR_ID},\"domainIds\":[$DOMAIN1]}"
allow "4d.3 造一张 TODO RD（父 PDR 已排期）→ 201" 201 "" "start_dev 只在 TODO 的 RD 上出现"
R27_RD="$(jget_ "$HTTP_BODY" '.id')"
if [[ -n "$R27_RD" && "$R27_RD" != "null" ]]; then
    R27_RD_KW="$(db_q "SELECT title FROM requirement WHERE id=${R27_RD}")"
    api_call POST "/api/req/requirements/${R27_RD}/estimate" '{"estimateDays":1.5,"note":"r27 4d：RD 评估"}'
    allow "4d.3 RD 提交工时评估 → 201" 201 "" "评估单是六态的输入，无单即 NONE"
    approve_est "$R27_RD" "4d.3 RD 评估初审"
    parity "4d.3 已通过评估的 RD 四处一致且含 start_dev" "$R27_RD" "$R27_RD_KW" 'any(.[]; . == "start_dev")'
else
    fail "4d.3 造 RD 失败：$(printf '%s' "$HTTP_BODY" | head -c 160)" "父 PDR（PDR_ID=${PDR_ID}）必须是 SCHEDULED/IN_DEV"
fi

# 4d.4/4d.5/4d.6 用 biz1 自己的 BUS 串起来：SUBMITTED（自服务 submit）→ APPROVING（pm 看 approve）
# → PENDING_REVIEW（审阅态只有 review_*，没有 approve）。
if login_as "biz1" "Passw0rd!"; then
    api_call POST /api/req/requirements "{\"type\":\"BUS\",\"title\":\"neg-r27-bus-${RUN_TAG}\",\"domainIds\":[$DOMAIN1]}"
    allow "4d.4 biz1 自建 BUS → 201（SUBMITTED）" 201 "" "biz1 需 req:report:bus"
    R27_BUS="$(jget_ "$HTTP_BODY" '.id')"
    if [[ -n "$R27_BUS" && "$R27_BUS" != "null" ]]; then
        R27_BUS_KW="$(db_q "SELECT title FROM requirement WHERE id=${R27_BUS}")"
        parity "4d.4 biz1 自建 BUS 四处一致且含 submit" "$R27_BUS" "$R27_BUS_KW" 'any(.[]; . == "submit")'
        api_call POST "/api/req/requirements/${R27_BUS}/actions" '{"action":"submit"}'
        allow "4d.4 biz1 提交自己的 BUS → 200（SUBMITTED→APPROVING）" 200 "" "自服务动作按 reporter 判定，不看角色"
        if login_as "pm" "Passw0rd!"; then
            parity "4d.6 反假修复：pm 看同一 BUS 四处一致且含 approve" "$R27_BUS" "$R27_BUS_KW" 'any(.[]; . == "approve")'
            api_call GET /api/req/approvals/todo?page=1\&size=200
            R27_BUS_TASK="$(jget_ "$HTTP_BODY" "[.items[] | select(.bizId == ${R27_BUS} and .bizType == \"REQ_SUBMIT\")][0].taskId")"
            if [[ -n "$R27_BUS_TASK" && "$R27_BUS_TASK" != "null" ]]; then
                api_call POST "/api/req/approvals/tasks/${R27_BUS_TASK}/actions" '{"action":"APPROVE","comment":"r27 4d 审阅通过"}'
                allow "4d.5 pm 审阅通过 BUS → 200（APPROVING→PENDING_REVIEW）" 200 "" "先有审阅态才有 4d.5 的现场"
            else
                fail "4d.5 审批待办里没有该 BUS 的 REQ_SUBMIT 待办（bizId=${R27_BUS}）" "或签节点必须解析到产品经理"
            fi
        else
            fail "4d.6 pm 登录失败" "make platform-seed 必须创建 pm/Passw0rd!"
        fi
        if login_as "biz1" "Passw0rd!"; then
            parity "4d.5 提交后 PENDING_REVIEW 四处一致且不含 approve/review_*" "$R27_BUS" "$R27_BUS_KW" \
                'all(.[]; ((. == "approve") or startswith("review_")) | not)'
        else
            fail "4d.5 biz1 登录失败" "make platform-seed 必须创建 biz1/Passw0rd!"
        fi
    else
        fail "4d.4 biz1 建 BUS 失败：$(printf '%s' "$HTTP_BODY" | head -c 160)" "biz1 需 req:report:bus"
    fi
else
    fail "4d.4 biz1 登录失败" "make platform-seed 必须创建 biz1/Passw0rd!"
fi
API_TOKEN="$ADMIN_TOKEN"

# 4d.7 整页逐行抽样：修头几行不算修好，前 5 行的列表值必须逐行等于它自己的详情。
api_call GET "/api/req/requirements?page=1&size=50"
R27_PAGE="$HTTP_BODY"; R27_PAGE_CODE="$HTTP_CODE"
R27_IDS="$(printf '%s' "$R27_PAGE" | jq -r '(.items // [])[0:5][].id' 2>/dev/null)"
R27_ROWS="$(printf '%s' "$R27_IDS" | grep -c . || true)"
if [[ "$R27_PAGE_CODE" == "200" && "${R27_ROWS:-0}" -ge 5 ]]; then
    R27_I=0
    while read -r R27_ID; do
        [[ -z "$R27_ID" ]] && continue
        R27_I=$((R27_I+1))
        R27_ROW="$(row_actions "$R27_ID" "$R27_PAGE")"
        [[ "${R27_PARITY_MUTATE:-}" == "order" ]] && R27_ROW="$(printf '%s' "$R27_ROW" | jq -c 'reverse' 2>/dev/null || printf 'ERR')"
        api_call GET "/api/req/requirements/${R27_ID}"
        R27_DROOT="$(actions_of "$HTTP_BODY")"
        R27_DVIEW="$(printf '%s' "$HTTP_BODY" | jq -c '.view.allowedActions // []' 2>/dev/null || printf 'ERR')"
        if [[ -n "$R27_ROW" && "$R27_ROW" == "$R27_DROOT" && "$R27_ROW" == "$R27_DVIEW" ]]; then
            pass "4d.7 第 ${R27_I} 行（id=${R27_ID}）列表=${R27_ROW} 与详情一致"
        else
            fail "4d.7 第 ${R27_I} 行（id=${R27_ID}）列表='${R27_ROW}' 详情='${R27_DROOT}' view='${R27_DVIEW}'" \
                "整页每一行都必须与详情同源，不能只修头几行"
        fi
    done <<< "$R27_IDS"
else
    fail "4d.7 取整页失败或不足 5 行（HTTP ${R27_PAGE_CODE:-?}，行数 ${R27_ROWS:-0}）" "make platform-seed 必须造出足够的需求行"
fi
API_TOKEN="$ADMIN_TOKEN"

# ---------------------------------------------------------------- 5/6
section "5) 无审批人发布 → 400；6) 编辑已发布流程 → 409"
wf() { jq -cn --arg n "$1" --arg r "$2" '{name:$n,bizType:"REQ_SUBMIT",reqType:"RD",domainId:0,definition:{schemaVersion:1,start:"n1",nodes:[{key:"n1",type:"APPROVAL",name:"评审",mode:"ANY",approvers:[{type:"ROLE",value:$r}],next:"end"}]}}'; }
api_call POST /api/req/admin/workflows "$(wf "neg-wf-${RUN_TAG}" "NO_SUCH_ROLE")"
BAD_WF="$(jget_ "$HTTP_BODY" '.id')"
api_call POST "/api/req/admin/workflows/${BAD_WF}/publish"
deny "无审批人发布 → 400 APPROVER_UNRESOLVED" 400 "APPROVER_UNRESOLVED" "发布期校验，不留运行时死锁"

api_call POST /api/req/admin/workflows "$(wf "neg-wf-ok-${RUN_TAG}" "PRODUCT")"
OK_WF="$(jget_ "$HTTP_BODY" '.id')"
api_call POST "/api/req/admin/workflows/${OK_WF}/publish"
api_call PUT "/api/req/admin/workflows/${OK_WF}" "$(wf "neg-wf-ok-${RUN_TAG}-edited" "PRODUCT")"
deny "编辑已发布流程 → 409 WORKFLOW_PUBLISHED_IMMUTABLE" 409 "WORKFLOW_PUBLISHED_IMMUTABLE" "已发布版本只读，修改=新版本行"

# ---------------------------------------------------------------- 7/8
section "7) 减签空原因 → 400；8) 已处理任务加签 → 409"
# r28：段内自造夹具，不再依赖「环境恰好有 pm 待办」——biz1 建 BUS → 自服务 submit → 从 pm 待办
# 按 bizId+bizType 精确取 taskId。夹具步骤一律 fail（绝不 pass/warn），故两条断言恒定执行、总数恒定。
R28_BUS=""
if ! login_as "biz1" "Passw0rd!"; then
    fail "7) biz1 登录失败，无法造减签/加签夹具" "make platform-seed 必须创建 biz1/Passw0rd!"
else
    api_call POST /api/req/requirements "{\"type\":\"BUS\",\"title\":\"neg-r28-bus-${RUN_TAG}\",\"domainIds\":[$DOMAIN1]}"
    R28_BUS="$(jget_ "$HTTP_BODY" '.id')"
    if [[ "$HTTP_CODE" != "201" || -z "$R28_BUS" || "$R28_BUS" == "null" ]]; then
        fail "7) 造 BUS 夹具失败（HTTP ${HTTP_CODE}，body=$(printf '%s' "$HTTP_BODY" | head -c 160)）" \
            "biz1 需 req:report:bus；POST /requirements {type:BUS} 必须返回 201 + id"
        R28_BUS=""
    else
        api_call POST "/api/req/requirements/${R28_BUS}/actions" '{"action":"submit"}'
        if [[ "$HTTP_CODE" != "200" ]]; then
            fail "7) 夹具 BUS submit 失败（HTTP ${HTTP_CODE}，body=$(printf '%s' "$HTTP_BODY" | head -c 160)）" \
                "默认 REQ_SUBMIT/BUS 流程必须允许 biz1 自服务提交"
            R28_BUS=""
        fi
    fi
fi

R28_TASK=""
if [[ -n "$R28_BUS" ]]; then
    if ! login_as "pm" "Passw0rd!"; then
        fail "7) pm 登录失败，取不到审批待办" "make platform-seed 必须创建 pm/Passw0rd!"
    else
        api_call GET "/api/req/approvals/todo?page=1&size=200"
        if [[ "$HTTP_CODE" != "200" ]]; then
            fail "7) pm 待办列表返回 ${HTTP_CODE}，取不到夹具 taskId" "GET /approvals/todo 必须 200"
        else
            R28_TASK="$(jget_ "$HTTP_BODY" "[.items[] | select(.bizId == ${R28_BUS} and .bizType == \"REQ_SUBMIT\")][0].taskId")"
            if [[ -z "$R28_TASK" || "$R28_TASK" == "null" ]]; then
                fail "7) pm 待办里没有夹具 BUS 的 REQ_SUBMIT 待办（bizId=${R28_BUS}）" \
                    "默认流程 approver=ROLE PRODUCT（mode=ALL）必须解析到 pm"
            fi
        fi
    fi
fi

if [[ -n "$R28_TASK" && "$R28_TASK" != "null" ]]; then
    api_call POST "/api/req/approvals/tasks/${R28_TASK}/remove-sign" '{"targetUserId":1,"reason":""}'
    deny "减签空原因 → 400 REASON_REQUIRED" 400 "REASON_REQUIRED" "减签必须留原因"
    api_call POST "/api/req/approvals/tasks/${R28_TASK}/actions" '{"action":"APPROVE","comment":"r28 夹具：先处理"}'
    if [[ "$HTTP_CODE" != "200" ]]; then
        fail "8) 夹具通过任务失败（HTTP ${HTTP_CODE}，body=$(printf '%s' "$HTTP_BODY" | head -c 160)）" \
            "pm 是该夹具 BUS 的当前审批人，通过必须 200"
    else
        api_call POST "/api/req/approvals/tasks/${R28_TASK}/add-sign" '{"type":"AFTER","userIds":[1],"comment":"x"}'
        deny "已处理任务加签 → 409 TASK_ALREADY_HANDLED" 409 "TASK_ALREADY_HANDLED" "加签只对 PENDING 任务生效"
    fi
else
    fail "8) 无夹具 taskId，加签负向未执行" "第 7 段夹具必须先造出 bizId=${R28_BUS:-?} 的 REQ_SUBMIT 待办"
fi
API_TOKEN="$ADMIN_TOKEN"

# ---------------------------------------------------------------- 9/10
section "9) 准入 FAIL 直接 submit → 409；10) 强制放行已退役 → 409"
api_call POST /api/req/releases "{\"name\":\"neg-rel-${RUN_TAG}\",\"domainId\":${DOMAIN1},\"startDate\":\"2026-11-01\",\"endDate\":\"2026-11-30\",\"plannedReleaseDate\":\"2026-12-05\",\"gateMode\":\"STRICT\"}"
REL="$(jget_ "$HTTP_BODY" '.id')"
api_call POST /api/req/releases/${REL}/requirements "{\"requirementIds\":[${RD_ID}]}"
# r30：submit 新增「版本必须 LOCKED」前置（DeployOrderService.requireReleaseLocked）。
# 本段要验的是第 6 项质量门，不是锁定闸门，所以先发起版本审批把 REL 锁住；
# 只看 HTTP 码，不计入断言数（失败会由下一条 QUALITY_GATE_FAILED 的期望值暴露）。
api_call POST "/api/req/releases/${REL}/approval"
[[ "$HTTP_CODE" == "200" || "$HTTP_CODE" == "201" ]] || \
    fail "9) 夹具版本发起审批失败（HTTP ${HTTP_CODE}），锁定前置未建立" \
        "版本必须是 PLAN 且需求已挂；否则 submit 会先撞 RELEASE_NOT_LOCKED 而不是质量门"
api_call POST /api/req/deploy-orders "$(jq -cn --argjson r "$REL" --argjson q "$RD_ID" '{releaseId:$r,env:"UAT",windowStart:"2026-12-02T10:00:00+08:00",windowEnd:"2026-12-02T12:00:00+08:00",artifactVersion:"neg",steps:[{name:"发布",order:1}],rollbackPlan:"回滚",impact:"无",ownerId:1,requirementIds:[$q]}')"
DEP="$(jget_ "$HTTP_BODY" '.id')"
api_call POST "/api/req/deploy-orders/${DEP}/submit"
deny "准入未过直接 submit → 409 QUALITY_GATE_FAILED" 409 "QUALITY_GATE_FAILED" "submit 必须服务端重算六项准入（第 6 项质量门先判）"
api_call POST "/api/req/deploy-orders/${DEP}/force-release" '{"reason":"  "}'
deny "强制放行已退役 → 409 FORCE_RELEASE_RETIRED" 409 "FORCE_RELEASE_RETIRED" "退役入口不得再接受任何原因"

# ---------------------------------------------------------------- 11
section "11) 第 4 层测试模块 → 400"
api_call POST /api/deliver/test-modules "$(jq -cn --arg n "neg-L1-${RUN_TAG}" '{parentId:null,name:$n}')"
NM1="$(jget_ "$HTTP_BODY" '.id')"
api_call POST /api/deliver/test-modules "$(jq -cn --argjson p "${NM1:-0}" --arg n "neg-L2-${RUN_TAG}" '{parentId:$p,name:$n}')"
NM2="$(jget_ "$HTTP_BODY" '.id')"
api_call POST /api/deliver/test-modules "$(jq -cn --argjson p "${NM2:-0}" --arg n "neg-L3-${RUN_TAG}" '{parentId:$p,name:$n}')"
NM3="$(jget_ "$HTTP_BODY" '.id')"
BEFORE_M="$(db_count test_module "tenant_id=${TID}")"
api_call POST /api/deliver/test-modules "$(jq -cn --argjson p "${NM3:-0}" --arg n "neg-L4-${RUN_TAG}" '{parentId:$p,name:$n}')"
deny "第 4 层模块 → 400" 400 "" "层级上限 3，服务端校验而不是前端隐藏按钮"
same_count "第 4 层模块未落库" test_module "tenant_id=${TID}" "$BEFORE_M"

# ---------------------------------------------------------------- 12
section "12) 开放 API：重复 200 幂等 / 未知 case 422 / NOT_RUN 400，且不落库"
api_call POST /api/deliver/admin/api-clients "{\"name\":\"neg-ci-${RUN_TAG}\",\"scopes\":[\"test-execution\"],\"ipWhitelist\":[\"*\"],\"rateLimitQps\":1000}"
APP_KEY="$(jget_ "$HTTP_BODY" '.appKey')"
APP_SECRET="$(jget_ "$HTTP_BODY" '.secret // .clientSecret // .secretOnce')"
api_call POST /api/deliver/testcases "{\"moduleId\":${NM3},\"title\":\"neg-case-${RUN_TAG}\",\"type\":\"FUNC\",\"priority\":\"P2\",\"precondition\":\"-\",\"steps\":[{\"stepNo\":1,\"action\":\"do\",\"expected\":\"ok\"}]}"
NCASE="$(jget_ "$HTTP_BODY" '.id')"
if [[ -n "$APP_KEY" && "$APP_KEY" != "null" ]]; then
    OAPI="/openapi/v1/test-executions"
    oapi() { curl -sS --max-time 10 -o /tmp/neg.$$ -w '%{http_code}' -X POST "${GATEWAY_URL}${OAPI}" -H "X-App-Key: ${APP_KEY}" -H "X-Timestamp: $1" -H "X-Signature: $2" -H 'Content-Type: application/json' -d "$3" 2>/dev/null; }
    sign() {
        local d; d="$(printf '%s' "$2" | openssl dgst -sha256 -hex | awk '{print $NF}')"
        printf '%s' "POST
${OAPI}
${APP_KEY}
${1}
${d}" | openssl dgst -sha256 -hmac "$APP_SECRET" -hex | awk '{print $NF}'
    }
    TS="$(date +%s)"
    GOOD="$(jq -cn --argjson c "$NCASE" --arg r "run-neg-${RUN_TAG}" '{run_id:$r,case_id:$c,result:"PASS",duration_ms:10,executed_at:"2026-11-09T00:00:00+08:00"}')"
    BEFORE_E="$(db_count test_execution "case_id=${NCASE}")"
    C1="$(oapi "$TS" "$(sign "$TS" "$GOOD")" "$GOOD")"
    C2="$(oapi "$TS" "$(sign "$TS" "$GOOD")" "$GOOD")"
    IDEM="$(jq -r '.idempotent' /tmp/neg.$$ 2>/dev/null || true)"
    [[ "$C2" == "200" && "$IDEM" == "true" ]] && pass "重复上报 → 200 idempotent=true" || fail "重复上报 code=${C2} idempotent=${IDEM}" "唯一键冲突后回查"
    same_count "重复上报只 1 行执行记录" test_execution "case_id=${NCASE}" "$((BEFORE_E + 1))"
    UNKNOWN="$(jq -cn '{run_id:"neg-unknown",case_id:99999999,result:"PASS",duration_ms:1,executed_at:"2026-11-09T00:00:00+08:00"}')"
    C3="$(oapi "$TS" "$(sign "$TS" "$UNKNOWN")" "$UNKNOWN")"
    [[ "$C3" == "422" ]] && pass "未知 case → 422" || fail "未知 case 返回 ${C3}，期望 422" "跨租户/不存在的 case 一律 422"
    NOTRUN="$(jq -cn --argjson c "$NCASE" '{run_id:"neg-notrun",case_id:$c,result:"NOT_RUN",duration_ms:1,executed_at:"2026-11-09T00:00:00+08:00"}')"
    B2="$(db_count test_execution "case_id=${NCASE}")"
    C4="$(oapi "$TS" "$(sign "$TS" "$NOTRUN")" "$NOTRUN")"
    [[ "$C4" == "400" ]] && pass "result=NOT_RUN → 400" || fail "NOT_RUN 返回 ${C4}，期望 400" "自动上报不允许 NOT_RUN"
    same_count "失败上报不落执行记录" test_execution "case_id=${NCASE}" "$B2"
    rm -f /tmp/neg.$$
else
    fail "种子响应缺开放 API 凭据" "SeedService 必须回传 openapi.appKey/secret"
fi

# ---------------------------------------------------------------- 13
section "13) 关闭有未完成任务迭代 → 409"
api_call POST /api/deliver/tasks "$(jq -cn --argjson q "$RD_ID" '{requirementId:$q,title:"neg-task",assigneeId:1,estimateHours:2,startDate:"2026-11-01",dueDate:"2026-11-09"}')"
NTASK="$(jget_ "$HTTP_BODY" '.id')"
api_call POST /api/deliver/sprints "{\"name\":\"neg-sprint-${RUN_TAG}\",\"goal\":\"neg\",\"startDate\":\"2026-11-01\",\"endDate\":\"2026-11-30\",\"capacityDays\":5}"
NSPRINT="$(jget_ "$HTTP_BODY" '.id')"
api_call PATCH "/api/deliver/tasks/${NTASK}" "{\"sprintId\":${NSPRINT}}"
api_call POST "/api/deliver/sprints/${NSPRINT}/close"
deny "有未完成任务关闭迭代 → 409" 409 "" "必须要求迁移或显式遗留确认"

# ---------------------------------------------------------------- 14
# 导出必须与列表同源 scope：断言「导出的数据行数 == 该账号列表的 total」，
# 而不是写死 0 行。r24 之后 VEN-B 会有自己的需求（第 17 段的夹具），写死 0 行会让这条
# 断言随夹具漂移——那是断言写错，不是泄露。
section "14) 导出越权 → 与列表同源（行数 == total，不是 500）"
if login_as "vendor2" "Passw0rd!"; then
    api_call GET "/api/req/requirements?page=1&size=50"
    V2_LIST_TOTAL="$(jget_ "$HTTP_BODY" '.total')"
    V2_LIST_PAGE="$(jget_ "$HTTP_BODY" '.items | length')"
    api_call GET "/api/req/requirements/export.csv?page=1&size=50"
    if [[ "$HTTP_CODE" == "200" ]]; then
        LINES="$(printf '%s' "$HTTP_BODY" | grep -c . || true)"
        # 首行是水印元信息、次行是表头（PRD），数据行 = 总行数 - 2。
        DATA_LINES=$(( LINES > 2 ? LINES - 2 : 0 ))
        if [[ -n "$V2_LIST_PAGE" && "$DATA_LINES" == "$V2_LIST_PAGE" ]]; then
            pass "导出与列表同行数（${DATA_LINES} 行数据，total=${V2_LIST_TOTAL:-0}）：scope 同源"
        else
            fail "导出 ${DATA_LINES} 行 ≠ 列表本页 ${V2_LIST_PAGE:-?} 行（total=${V2_LIST_TOTAL:-?}）" \
                "导出必须复用列表的 scope 谓词，不能各自拼 SQL"
        fi
    else
        fail "导出返回 ${HTTP_CODE}，期望 200" "越权不是 500：空集也必须 200"
    fi
else
    warn "vendor2 登录失败，跳过导出同源断言"
fi
API_TOKEN="$ADMIN_TOKEN"

# ---------------------------------------------------------------- 15
section "15) 已发布/锁定 Release 增删需求 → 409"
api_call POST /api/req/releases "{\"name\":\"neg-rel2-${RUN_TAG}\",\"domainId\":${DOMAIN1},\"startDate\":\"2026-11-01\",\"endDate\":\"2026-11-30\",\"plannedReleaseDate\":\"2026-12-05\",\"gateMode\":\"LOOSE\"}"
REL2="$(jget_ "$HTTP_BODY" '.id')"
api_call POST "/api/req/releases/${REL2}/requirements" "{\"requirementIds\":[${RD_ID}]}"
api_call POST "/api/req/releases/${REL2}/approval"
if [[ "$HTTP_CODE" == "200" || "$HTTP_CODE" == "201" ]]; then
    api_call POST "/api/req/releases/${REL2}/requirements" "{\"requirementIds\":[${RD_ID}]}"
    deny "锁定后新增需求 → 409" 409 "RELEASE_LOCKED" "已提交审批的版本只读"
    api_call DELETE "/api/req/releases/${REL2}/requirements/${RD_ID}"
    deny "锁定后移除需求 → 409" 409 "RELEASE_LOCKED" "锁定后删除同样拒绝"
else
    warn "版本审批未启动（HTTP ${HTTP_CODE}），跳过锁定负向"
fi

# ---------------------------------------------------------------- 16
section "16) 需求流转动作级授权（r17）：越权动作 403 且不落库"

# 复合断言：一次 403 必须同时满足三条 —— 被拒绝、状态没动、没有生成审批实例。
# 只断言 403 不够：先写状态再报错、或先起 REQ_SUBMIT 审批再鉴权，都还是会造出脏数据。
deny_action() { # desc req_id expect_status [needle] [inst_type] [inst_expect]
    local desc="$1" rid="$2" keep="$3" needle="${4:-}" errs="" status inst
    local inst_type="${5:-REQ_SUBMIT}" inst_expect="${6:-0}"
    status="$(db_q "SELECT status FROM requirement WHERE id=${rid}")"
    inst="$(db_count wf_instance "tenant_id=${TID} AND biz_type='${inst_type}' AND biz_id=${rid}")"
    [[ "$HTTP_CODE" == "403" ]] || errs="${errs}HTTP ${HTTP_CODE}（期望 403）；"
    if [[ -n "$needle" ]] && ! grep -qF -- "$needle" <<<"$HTTP_BODY"; then
        errs="${errs}响应未含 ${needle}；"
    fi
    [[ "$status" == "$keep" ]] || errs="${errs}状态 ${status}（应仍为 ${keep}）；"
    [[ "${inst:-0}" == "$inst_expect" ]] || errs="${errs}${inst_type} 实例 ${inst} 条（应 ${inst_expect}）；"
    if [[ -z "$errs" ]]; then
        pass "${desc}（403 + 状态仍 ${keep} + ${inst_type} 实例 ${inst_expect}）"
    else
        fail "${desc}（${errs}）" \
            "越权必须在写状态/建审批实例之前被拒：$(printf '%s' "$HTTP_BODY" | head -c 160)"
    fi
}

B_ID=""
P_ID=""
# 夹具 B：业务角色自己的 BUS 需求（提交状态）；夹具 P：产品经理报的 BUS 需求（用来验证「不能代他人提交」）
if login_as "biz1" "Passw0rd!"; then
    api_call POST /api/req/requirements "{\"type\":\"BUS\",\"title\":\"neg-r17-biz-${RUN_TAG}\",\"domainIds\":[${DOMAIN1}],\"priority\":\"P2\",\"expectedDeliveryDate\":\"2026-12-31\"}"
    B_ID="$(jget_ "$HTTP_BODY" '.id')"
    [[ -n "$B_ID" && "$B_ID" != "null" ]] || warn "biz1 建 BUS 夹具失败: $(printf '%s' "$HTTP_BODY" | head -c 160)"
else
    warn "biz1 登录失败，跳过 r17 动作级授权负向"
fi
API_TOKEN="$ADMIN_TOKEN"
if login_as "pm" "Passw0rd!"; then
    api_call POST /api/req/requirements "{\"type\":\"BUS\",\"title\":\"neg-r17-pm-${RUN_TAG}\",\"domainIds\":[${DOMAIN1}],\"priority\":\"P2\",\"expectedDeliveryDate\":\"2026-12-31\"}"
    P_ID="$(jget_ "$HTTP_BODY" '.id')"
    [[ -n "$P_ID" && "$P_ID" != "null" ]] || warn "pm 建 BUS 夹具失败: $(printf '%s' "$HTTP_BODY" | head -c 160)"
else
    warn "pm 登录失败，跳过「代他人提交」负向"
fi
API_TOKEN="$ADMIN_TOKEN"

if [[ -n "$B_ID" && "$B_ID" != "null" && -n "$P_ID" && "$P_ID" != "null" ]]; then
    # a) 厂商账号（vendor1 的角色码就是 PMO，持 req:review）不能提交他人（业务方）的需求
    if login_as "vendor1" "Passw0rd!"; then
        api_call POST "/api/req/requirements/${B_ID}/actions" '{"action":"submit"}'
        deny_action "厂商角色提交他人业务需求 → 403（不得代提交）" "$B_ID" "SUBMITTED" '"action":"submit"'
    else
        warn "vendor1 登录失败，跳过 a) 越权提交"
    fi
    API_TOKEN="$ADMIN_TOKEN"

    # b) 业务角色不能提交别人（产品经理）报的需求
    if login_as "biz1" "Passw0rd!"; then
        api_call POST "/api/req/requirements/${P_ID}/actions" '{"action":"submit"}'
        deny_action "业务角色提交他人需求 → 403（只能提报自己的）" "$P_ID" "SUBMITTED" '"action":"submit"'
    else
        warn "biz1 登录失败，跳过 b) 越权提交"
    fi
    API_TOKEN="$ADMIN_TOKEN"

    # c) withdraw 在 SUBMITTED 上本来是 409（非法流转）：这里拿到 403，证明「先鉴权后状态」，
    #    且 403 不泄露 from/allowedTargets。
    if login_as "vendor1" "Passw0rd!"; then
        api_call POST "/api/req/requirements/${B_ID}/actions" '{"action":"withdraw"}'
        deny_action "厂商撤回他人需求 → 403（先鉴权后状态，不是 409）" "$B_ID" "SUBMITTED" '"action":"withdraw"'
    else
        warn "vendor1 登录失败，跳过 c) 越权撤回"
    fi
    API_TOKEN="$ADMIN_TOKEN"

    # d) BUG-1（r17 QA）：厂商账号即使持 req:review（vendor1 的角色码就是 PMO）也不得评审内部需求。
    #    先把 B 推到 PENDING_REVIEW —— biz1 自提交，pm 在审批中心通过（source=APPROVAL 回调）。
    if login_as "biz1" "Passw0rd!"; then
        api_call POST "/api/req/requirements/${B_ID}/actions" '{"action":"submit"}'
        if [[ "$HTTP_CODE" == "200" ]]; then
            API_TOKEN="$ADMIN_TOKEN"
            if login_as "pm" "Passw0rd!"; then
                api_call GET "/api/req/approvals/todo?page=1&size=200"
                TASK_ID="$(printf '%s' "$HTTP_BODY" | jq -r --argjson rid "$B_ID" '(.items // []) | map(select(.bizId == $rid and .bizType == "REQ_SUBMIT")) | .[0].taskId // empty')"
                [[ -n "$TASK_ID" ]] && api_call POST "/api/req/approvals/tasks/${TASK_ID}/actions" \
                    '{"action":"APPROVE","comment":"r17 negative review fixture"}'
            fi
        fi
    fi
    API_TOKEN="$ADMIN_TOKEN"
    B_STATUS="$(db_q "SELECT status FROM requirement WHERE id=${B_ID}")"
    if [[ "$B_STATUS" == "PENDING_REVIEW" ]]; then
        if login_as "vendor1" "Passw0rd!"; then
            api_call POST "/api/req/requirements/${B_ID}/actions" '{"action":"review_start"}'
            deny_action "厂商（角色码 PMO）评审内部需求 → 403（不得入池）" \
                "$B_ID" "PENDING_REVIEW" '"action":"review_start"' "REQ_REVIEW" 0
            # 按钮列表与执行共用同一谓词：厂商看到空动作集，不会出现必然 403 的按钮（AC-6）。
            api_call GET "/api/req/requirements/${B_ID}"
            B_ALLOWED="$(jget_ "$HTTP_BODY" '.allowedActions | length')"
            if [[ "$B_ALLOWED" == "0" ]]; then
                pass "厂商 allowedActions 对评审中的内部需求为空（按钮不会错误出现）"
            else
                fail "厂商 allowedActions 仍有 ${B_ALLOWED} 个动作（应与执行同口径）" \
                    "allowedActions 必须复用 RequirementActionMatrix.filter"
            fi
        else
            warn "vendor1 登录失败，跳过 d) 厂商评审越权"
        fi
    else
        warn "夹具 B 未到 PENDING_REVIEW（status=${B_STATUS:-空}），跳过 d) 厂商评审越权"
    fi
    API_TOKEN="$ADMIN_TOKEN"
else
    warn "r17 夹具不完整（B=${B_ID:-空} P=${P_ID:-空}），跳过第 16 段"
fi

# ---------------------------------------------------------------- 17
section "17) 第 11 个角色 VENDOR_PM（r24）：按厂商隔离、写口全关、账号必须绑厂商"

# 演示夹具里唯一版本只含内部需求（TECHNICAL §0 C4），厂商视角本来就看它不见；
# 所以第 17 段的正例（版本 200、排期 200）必须自建 VEN-A 数据，不能拿演示版本充数。
VPM_VEN_A="$(db_q "SELECT id FROM sys_vendor WHERE tenant_id=${TID} AND code='VEN-A'")"
VPM_VEN_B="$(db_q "SELECT id FROM sys_vendor WHERE tenant_id=${TID} AND code='VEN-B'")"
# 排期只合法于 PDR 的 PENDING_ANALYSIS（StateTables），而 PDR 只能挂在已入池的 BUS 下，
# 种子里的 POOLED BUS 就是 BUS-0001（platform-verify 也这样造夹具）。
VPM_PARENT_BUS="$(db_q "SELECT id FROM requirement WHERE tenant_id=${TID} AND type='BUS' AND status='POOLED' ORDER BY id LIMIT 1")"

vpm_fixture() { # <tag> <vendor_id> [parent_bus_id] → 打印 "<reqId> <relId>"
    local tag="$1" vid="$2" parent="${3:-}" rid relid body
    if [[ -n "$parent" ]]; then
        body="{\"type\":\"PDR\",\"title\":\"NEG-VPM-${tag}-${RUN_TAG}\",\"parentId\":${parent},\"domainIds\":[${DOMAIN1}],\"priority\":\"P2\",\"expectedDeliveryDate\":\"2026-12-31\"}"
    else
        body="{\"type\":\"BUS\",\"title\":\"NEG-VPM-${tag}-${RUN_TAG}\",\"domainIds\":[${DOMAIN1}],\"priority\":\"P2\",\"expectedDeliveryDate\":\"2026-12-31\"}"
    fi
    api_call POST /api/req/requirements "$body"
    rid="$(jget_ "$HTTP_BODY" '.id')"
    if [[ -z "$rid" || "$rid" == "null" ]]; then printf ''; return 0; fi
    api_call POST "/api/req/requirements/${rid}/assign-vendor" \
        "{\"vendorId\":${vid},\"reason\":\"r24 vendor-pm negative fixture ${tag}\"}"
    [[ "$HTTP_CODE" == "200" ]] || warn "夹具 ${tag} 转派厂商失败（HTTP ${HTTP_CODE}）"
    api_call POST /api/req/releases \
        "{\"name\":\"NEG-VPM-REL-${tag}-${RUN_TAG}\",\"domainId\":${DOMAIN1},\"startDate\":\"2026-11-01\",\"endDate\":\"2026-11-30\",\"plannedReleaseDate\":\"2026-12-05\",\"gateMode\":\"LOOSE\"}"
    relid="$(jget_ "$HTTP_BODY" '.id')"
    if [[ -n "$relid" && "$relid" != "null" ]]; then
        api_call POST "/api/req/releases/${relid}/requirements" "{\"requirementIds\":[${rid}]}"
        [[ "$HTTP_CODE" == "200" ]] || warn "夹具 ${tag} 挂版本失败（HTTP ${HTTP_CODE}）"
    fi
    printf '%s %s' "$rid" "${relid:-}"
}

read -r VPM_REQ_A VPM_REL_A <<<"$(vpm_fixture A "${VPM_VEN_A}")"
read -r VPM_REQ_B VPM_REL_B <<<"$(vpm_fixture B "${VPM_VEN_B}")"
read -r VPM_REQ_SCHEDULE VPM_REL_SCHEDULE <<<"$(vpm_fixture SCHED "${VPM_VEN_A}" "${VPM_PARENT_BUS}")"
VPM_USER_ID="$(db_q "SELECT id FROM sys_user WHERE tenant_id=${TID} AND username='vpm1'")"
VPM_REL_COUNT="$(db_count "\`release\`" "tenant_id=${TID}")"

if [[ -n "$VPM_VEN_A" && -n "$VPM_REQ_A" && -n "$VPM_REQ_B" ]]; then
    # ---- ① 会话身份：角色 + 厂商
    if login_as "vpm1" "Passw0rd!"; then
        pass "vpm1 可登录（VENDOR_PM 是内置角色，不是自定义角色）"
        VPM_ROLES="$(jget_ "$HTTP_BODY" '.user.roles | join(",")')"
        VPM_VENDOR="$(jget_ "$HTTP_BODY" '.user.vendorId')"
        if grep -qF 'VENDOR_PM' <<<"$VPM_ROLES"; then
            pass "vpm1 角色含 VENDOR_PM（${VPM_ROLES}）"
        else
            fail "vpm1 角色为 ${VPM_ROLES:-<空>}，不含 VENDOR_PM" "SeedService 必须给 vpm1 写 VENDOR_PM 角色"
        fi
        if [[ "$VPM_VENDOR" == "$VPM_VEN_A" ]]; then
            pass "vpm1 会话绑定厂商 VEN-A（vendorId=${VPM_VENDOR}）"
        else
            fail "vpm1 会话厂商 ${VPM_VENDOR:-<空>}，期望 ${VPM_VEN_A}" "账号未绑厂商 ⇒ 数据域退化，必须 vendor_id 非空"
        fi

        # ---- ② 需求列表只看本厂商（内部需求是共享池，不算越界）
        api_call GET "/api/req/requirements?page=1&size=200"
        allow "VENDOR_PM 读需求列表 → 200（不是禁读角色）" 200 "" "VENDOR_PM 必须能跟踪本厂商交付"
        VPM_BAD_ROWS="$(printf '%s' "$HTTP_BODY" | jq -r --argjson a "${VPM_VEN_A}" \
            '[.items[]? | select([(.ownerVendorId // 0), (.assigneeVendorId // 0)] | map(select(. != 0)) | any(. != $a))] | length' 2>/dev/null || echo x)"
        if [[ "$VPM_BAD_ROWS" == "0" ]]; then
            pass "VENDOR_PM 需求列表无他厂商行（owner/assignee 非空即本厂商）"
        else
            fail "VENDOR_PM 需求列表出现 ${VPM_BAD_ROWS} 行他厂商数据" "ScopeSql 的 requirement 谓词必须按 owner/assignee 厂商取并集"
        fi
        api_call GET "/api/req/requirements?page=1&size=1"
        VPM_REQ_TOTAL="$(jget_ "$HTTP_BODY" '.total')"
        if login_as "vendor1" "Passw0rd!"; then
            api_call GET "/api/req/requirements?page=1&size=1"
            V1_REQ_TOTAL="$(jget_ "$HTTP_BODY" '.total')"
            if [[ "$VPM_REQ_TOTAL" == "$V1_REQ_TOTAL" ]]; then
                pass "VENDOR_PM 与同厂商 PMO 的数据域一致（total=${VPM_REQ_TOTAL}，角色不同、范围相同）"
            else
                fail "VENDOR_PM 总数 ${VPM_REQ_TOTAL} ≠ 同厂商 PMO ${V1_REQ_TOTAL}" "同厂商两角色应看到同一批需求"
            fi
        else
            warn "vendor1 登录失败，跳过「同厂商同范围」对照"
        fi
        API_TOKEN="$ADMIN_TOKEN"
        api_call GET "/api/req/requirements?page=1&size=1"
        ADMIN_REQ_TOTAL="$(jget_ "$HTTP_BODY" '.total')"
        if [[ -n "$VPM_REQ_TOTAL" && -n "$ADMIN_REQ_TOTAL" ]] && (( VPM_REQ_TOTAL < ADMIN_REQ_TOTAL )); then
            pass "VENDOR_PM 范围小于管理员（${VPM_REQ_TOTAL} < ${ADMIN_REQ_TOTAL}，隔离真的在生效）"
        else
            fail "VENDOR_PM 总数 ${VPM_REQ_TOTAL:-空} 未小于管理员 ${ADMIN_REQ_TOTAL:-空}" "厂商账号不该看到全租户需求"
        fi

        if login_as "vpm1" "Passw0rd!"; then
            # ---- ③ 他厂商需求详情：403
            api_call GET "/api/req/requirements/${VPM_REQ_B}"
            deny "VENDOR_PM 读他厂商需求详情 → 403 OUT_OF_SCOPE" 403 "OUT_OF_SCOPE" \
                "ScopeGuard.requireVisible 必须按 vendor_id 判定厂商隔离"
            api_call GET "/api/req/requirements/${VPM_REQ_A}"
            allow "VENDOR_PM 读本厂商需求详情 → 200（正例）" 200 "" "本厂商数据必须可见"

            # ---- ④ 审批：厂商永远不能评审（REVIEW_SIDE 对 vendorId 非空一律 false）
            API_TOKEN="$ADMIN_TOKEN"
            api_call POST "/api/req/requirements/${VPM_REQ_A}/actions" '{"action":"submit"}'
            [[ "$HTTP_CODE" == "200" ]] || warn "夹具 A 提交失败（HTTP ${HTTP_CODE}），审批越权断言可能失真"
            if login_as "vpm1" "Passw0rd!"; then
                # 这条 403 由路由级 bodyAction 判定（动作码被 DENY，请求体还没进服务层），
                # 所以 detail 里回显的是动作码 req.action.approve，而不是裸动作名 approve。
                api_call POST "/api/req/requirements/${VPM_REQ_A}/actions" '{"action":"approve"}'
                deny_action "VENDOR_PM 审批本厂商需求 → 403（评审是内部职责）" \
                    "$VPM_REQ_A" "APPROVING" '"action":"req.action.approve"' "REQ_SUBMIT" 1
                api_call POST "/api/req/requirements/${VPM_REQ_A}/actions" '{"action":"reject"}'
                deny_action "VENDOR_PM 驳回需求 → 403" "$VPM_REQ_A" "APPROVING" '"action":"req.action.reject"' "REQ_SUBMIT" 1

                # ---- ⑤ 正例：排期是 VENDOR_PM 唯一的需求写动作（release:plan 派生）
                # r26 起排期多了一道工时评估门：先以内部身份把这条 PDR 的评估单批掉，
                # 否则权限正确也会被 409 ESTIMATE_NOT_APPROVED 拦住（门与权限是两件事）。
                API_TOKEN="$ADMIN_TOKEN"
                api_call POST "/api/req/requirements/${VPM_REQ_SCHEDULE}/estimate" '{"estimateDays":5,"note":"负向夹具：厂商排期前评估"}'
                [[ "$HTTP_CODE" == "201" ]] || warn "厂商 PDR 评估单提交失败（HTTP ${HTTP_CODE}）：排期断言可能被评估门挡住"
                login_as "pm" "Passw0rd!" || warn "pm 登录失败（厂商排期夹具）"
                api_call GET /api/req/approvals/todo?page=1\&size=100
                VPM_EST_TASK="$(jget_ "$HTTP_BODY" "[.items[] | select(.bizId == ${VPM_REQ_SCHEDULE} and .bizType == \"WBS_ESTIMATE\")][0].taskId")"
                [[ -n "$VPM_EST_TASK" && "$VPM_EST_TASK" != "null" ]] \
                    && api_call POST "/api/req/approvals/tasks/${VPM_EST_TASK}/actions" '{"action":"APPROVE","comment":"厂商排期前评估通过"}'
                API_TOKEN="$ADMIN_TOKEN"
                if login_as "vpm1" "Passw0rd!"; then
                    api_call POST "/api/req/requirements/${VPM_REQ_SCHEDULE}/actions" '{"action":"schedule"}'
                    if [[ "$HTTP_CODE" == "200" ]]; then
                        pass "VENDOR_PM 排期本厂商 PDR → 200（唯一的需求写动作确实开着）"
                    else
                        fail "VENDOR_PM 排期本厂商 PDR 返回 ${HTTP_CODE}: $(printf '%s' "$HTTP_BODY" | head -c 160)" \
                            "req.action.schedule 对 VENDOR_PM 应为 ALLOW（release:plan 派生），且评估单已批"
                    fi
                fi
            fi

            # ---- ⑥ 版本写口全关
            if login_as "vpm1" "Passw0rd!"; then
                api_call POST /api/req/releases \
                    "{\"name\":\"NEG-VPM-DENY-${RUN_TAG}\",\"domainId\":${DOMAIN1},\"startDate\":\"2026-11-01\",\"endDate\":\"2026-11-30\",\"plannedReleaseDate\":\"2026-12-05\",\"gateMode\":\"LOOSE\"}"
                deny "VENDOR_PM 创建版本 → 403（release.action.create 被 DENY 覆盖）" 403 "" \
                    "RbacSeeder.ACTION_DENY_OVERRIDES 必须覆盖 release.action.create"
                same_count "越权建版本未落库" "\`release\`" "tenant_id=${TID}" "$VPM_REL_COUNT"

                VPM_REL_STATUS="$(db_q "SELECT status FROM \`release\` WHERE id=${VPM_REL_A}")"
                api_call POST "/api/req/releases/${VPM_REL_A}/approval"
                deny "VENDOR_PM 提交版本审批 → 403" 403 "" "版本审批是 PMO/管理员职责"
                [[ "$(db_q "SELECT status FROM \`release\` WHERE id=${VPM_REL_A}")" == "$VPM_REL_STATUS" ]] \
                    && pass "越权提交审批未改版本状态（仍 ${VPM_REL_STATUS}）" \
                    || fail "越权提交审批后版本状态被改（${VPM_REL_STATUS} → $(db_q "SELECT status FROM \`release\` WHERE id=${VPM_REL_A}")）" \
                        "拒绝必须发生在写状态之前"

                api_call POST "/api/req/releases/${VPM_REL_A}/reviews" \
                    '{"roundName":"neg-vpm","reviewers":[],"comment":"越权"}'
                deny "VENDOR_PM 创建评审轮次 → 403（页面码已改为 release.action.update）" 403 "" \
                    "POST /releases/{id}/reviews 必须是动作码守卫，不能只判页面码"

                api_call POST "/api/req/releases/${VPM_REL_A}/lock"
                deny "VENDOR_PM 锁定版本 → 403" 403 "" "锁定是版本治理动作"
                api_call PATCH "/api/req/releases/${VPM_REL_A}/quality-settings" '{"gateMode":"STRICT"}'
                deny "VENDOR_PM 改版本质量设置 → 403" 403 "" "release.action.update 必须被 DENY"
                api_call POST "/api/req/releases/${VPM_REL_A}/requirements" "{\"requirementIds\":[${VPM_REQ_A}]}"
                deny "VENDOR_PM 往版本里加需求 → 403" 403 "" "写口收口到 release.action.update"
                [[ "$(db_q "SELECT status FROM \`release\` WHERE id=${VPM_REL_A}")" == "$VPM_REL_STATUS" ]] \
                    && pass "版本写口全部拒绝后状态未变（仍 ${VPM_REL_STATUS}）" \
                    || fail "版本写口尝试后状态漂移" "所有版本写方法都必须先过 requireVisible"
            fi

            # ---- ⑦ 版本读：只看得见「含本厂商需求」的版本（ScopeSql 派生 EXISTS）
            if login_as "vpm1" "Passw0rd!"; then
                api_call GET "/api/req/releases?page=1&size=100"
                allow "VENDOR_PM 读版本列表 → 200" 200 "" "派生谓词必须让本厂商版本进入列表"
                VPM_HAS_A="$(printf '%s' "$HTTP_BODY" | jq -r --argjson r "${VPM_REL_A}" '[.items[]? | select(.id == $r)] | length' 2>/dev/null || echo x)"
                VPM_HAS_B="$(printf '%s' "$HTTP_BODY" | jq -r --argjson r "${VPM_REL_B}" '[.items[]? | select(.id == $r)] | length' 2>/dev/null || echo x)"
                [[ "$VPM_HAS_A" == "1" ]] \
                    && pass "本厂商版本出现在 VENDOR_PM 列表（release ${VPM_REL_A}）" \
                    || fail "本厂商版本 ${VPM_REL_A} 未出现在 VENDOR_PM 版本列表" \
                        "release 的 vendor 谓词必须是 release_requirement→requirement 的 EXISTS 派生"
                [[ "$VPM_HAS_B" == "0" ]] \
                    && pass "他厂商版本不出现在 VENDOR_PM 列表（release ${VPM_REL_B}）" \
                    || fail "他厂商版本 ${VPM_REL_B} 出现在 VENDOR_PM 版本列表" "派生 EXISTS 必须带 vendor 条件"
                api_call GET "/api/req/releases/${VPM_REL_A}"
                allow "VENDOR_PM 读本厂商版本详情 → 200（正例）" 200 "" "列表与详情必须同一谓词"
                api_call GET "/api/req/releases/${VPM_REL_B}"
                deny "VENDOR_PM 读他厂商版本详情 → 403 OUT_OF_SCOPE" 403 "OUT_OF_SCOPE" \
                    "ReleaseService.requireVisible 必须复用同一 scope marker"
            fi

            # ---- ⑧ 系统管理：角色/用户/租户全部不可达
            if login_as "vpm1" "Passw0rd!"; then
                VPM_ROLE_BEFORE="$(db_count sys_role "tenant_id=${TID}")"
                api_call POST /api/req/admin/roles "{\"code\":\"NEG_VPM_ROLE\",\"name\":\"负向角色\"}"
                deny "VENDOR_PM 建角色 → 403" 403 "" "role.action.create 对厂商角色必须 DENY"
                same_count "越权建角色未落库" sys_role "tenant_id=${TID}" "$VPM_ROLE_BEFORE"
                VPM_USER_BEFORE="$(db_count sys_user "tenant_id=${TID}")"
                api_call POST /api/req/admin/users \
                    "{\"username\":\"neg-vpm-user-${RUN_TAG}\",\"roles\":[\"DEV\"]}"
                deny "VENDOR_PM 建用户 → 403" 403 "" "user.action.create 对厂商角色必须 DENY"
                same_count "越权建用户未落库" sys_user "tenant_id=${TID}" "$VPM_USER_BEFORE"
                api_call PUT /api/req/admin/users "{\"id\":${VPM_USER_ID},\"name\":\"tampered\"}"
                deny "VENDOR_PM 改用户 → 403" 403 "" "user.action.update 对厂商角色必须 DENY"
            fi
            API_TOKEN="$ADMIN_TOKEN"

            # ---- ⑨ 账号必须绑厂商：create 缺 vendorId → 400 且不落库
            VPM_USER_BEFORE="$(db_count sys_user "tenant_id=${TID}")"
            api_call POST /api/req/admin/users \
                "{\"username\":\"neg-vpm-novendor-${RUN_TAG}\",\"roles\":[\"VENDOR_PM\"],\"password\":\"Passw0rd!\"}"
            deny "管理员建 VENDOR_PM 用户但不给 vendorId → 400 VALIDATION_FAILED" 400 "VALIDATION_FAILED" \
                "RoleCatalog.requiresVendor 必须先于 INSERT 拦下"
            grep -qF '"field":"vendorId"' <<<"$HTTP_BODY" \
                && pass "400 指明缺的是 vendorId 字段（前端能定位到表单项）" \
                || fail "400 响应未指明 field=vendorId: $(printf '%s' "$HTTP_BODY" | head -c 160)" \
                    "ApiException 的 detail.field 是前端回填契约"
            same_count "无厂商的 VENDOR_PM 账号未落库" sys_user "tenant_id=${TID}" "$VPM_USER_BEFORE"

            # ---- ⑩ update 用「将生效角色」：只清 vendorId、不带 roles → 仍必须 400
            if [[ -n "$VPM_USER_ID" ]]; then
                api_call PUT /api/req/admin/users "{\"id\":${VPM_USER_ID},\"vendorId\":null}"
                deny "管理员清空 VENDOR_PM 用户的厂商 → 400（角色未变，仍强制厂商）" 400 "VALIDATION_FAILED" \
                    "update 必须校验将生效角色，而不是只校验 body 里的 roles"
                if [[ "$(db_q "SELECT vendor_id FROM sys_user WHERE id=${VPM_USER_ID}")" == "$VPM_VEN_A" ]]; then
                    pass "被拒的更新未改库（vendor_id 仍 VEN-A）"
                else
                    fail "被拒的更新写入了 vendor_id=$(db_q "SELECT vendor_id FROM sys_user WHERE id=${VPM_USER_ID}")" \
                        "校验必须在 UPDATE 之前"
                fi
            else
                warn "种子里没有 vpm1 用户，跳过「清空厂商」负向"
            fi

            # ---- ⑪ 交付域写口：用例/测试计划/迭代/任务全部 DENY
            if login_as "vpm1" "Passw0rd!"; then
                VPM_CASE_BEFORE="$(db_count test_case "tenant_id=${TID}")"
                api_call POST /api/deliver/testcases \
                    "{\"title\":\"neg-vpm-case-${RUN_TAG}\",\"domainId\":${DOMAIN1}}"
                deny "VENDOR_PM 建用例 → 403" 403 "" "test.action.case_edit 必须被 DENY 覆盖"
                same_count "越权建用例未落库" test_case "tenant_id=${TID}" "$VPM_CASE_BEFORE"
                VPM_PLAN_BEFORE="$(db_count test_plan "tenant_id=${TID}")"
                api_call POST /api/deliver/test-plans \
                    "{\"name\":\"neg-vpm-plan-${RUN_TAG}\",\"domainId\":${DOMAIN1}}"
                deny "VENDOR_PM 建测试计划 → 403" 403 "" "test.action.plan_create 对厂商角色必须 DENY"
                same_count "越权建测试计划未落库" test_plan "tenant_id=${TID}" "$VPM_PLAN_BEFORE"
                api_call POST /api/req/sprints "{\"name\":\"neg-vpm-sprint-${RUN_TAG}\",\"domainId\":${DOMAIN1}}"
                deny "VENDOR_PM 建迭代 → 403" 403 "" "sprint.action.create 必须被 DENY 覆盖"
                api_call POST /api/req/tasks \
                    "{\"title\":\"neg-vpm-task-${RUN_TAG}\",\"domainId\":${DOMAIN1}}"
                deny "VENDOR_PM 建任务 → 403" 403 "" "task.action.create 必须被 DENY 覆盖"

                # ---- ⑫ 唯一开着的页面：用例库只读（页面进得去、写按钮关着）
                api_call GET "/api/deliver/testcases?page=1&size=5"
                allow "VENDOR_PM 读用例库 → 200（vendorPmAllowed 窄例外的页面，仅只读）" 200 "" \
                    "用例库是 VENDOR_PM 唯一的 internalOnly 例外，页面必须能进"
            fi
            API_TOKEN="$ADMIN_TOKEN"
        fi
    else
        fail "vpm1 登录失败" "make platform-seed 必须创建 vpm1/Passw0rd!（VENDOR_PM + VEN-A）"
    fi
else
    warn "第 17 段夹具不完整（VEN-A=${VPM_VEN_A:-空} reqA=${VPM_REQ_A:-空} reqB=${VPM_REQ_B:-空}），跳过 VENDOR_PM 边界"
fi
API_TOKEN="$ADMIN_TOKEN"

# ---------------------------------------------------------------- 18
section "18) 网关只透传（r42）：同一中文 keyword 经 8080 与直连 8082 响应体逐字节一致"
# 口径：只验 8080 返回 200 不够 —— 网关丢参数、改词、或另做一次编码，都可能仍然 200。
# 逐字节 cmp 把「网关只透传、不改写查询语义」变成可执行断言；两条 body 的 .total 还须同源。
# keyword 从池中真实标题派生（禁止写死文案，r42 教训：「演示」实测 total=0 会假红）；
# 直连必须去掉 /api/req 前缀（网关 StripPrefix(1) → /requirements），且与 api_call 用同一组头。
HAN_KW="$(db_q "SELECT title FROM requirement WHERE tenant_id=${TID} ORDER BY id LIMIT 50" \
    2>/dev/null | jq -sRr 'split("\n") | map(explode | map(select(. >= 19968 and . <= 40959)) | implode)
        | map(select(length >= 2)) | ((.[0] // "")[0:2])' 2>/dev/null || true)"
QK="$(printf '%s' "$HAN_KW" | jq -sRr @uri 2>/dev/null || true)"
QS="page=1&size=5&keyword=${QK}"
if [[ -z "$HAN_KW" ]]; then
    fail "第 18 段取不到中文 keyword" "池中必须有中文标题的需求（先 make platform-seed / platform-demo-reset）"
    fail "第 18 段 8080/8082 响应体逐字节一致" "上一条失败时无法比较，先补齐中文标题夹具"
else
    HDR18=(-H 'Accept: application/json' -H "Authorization: Bearer ${API_TOKEN}" -H "X-Tenant-Id: ${API_TENANT}")
    api_call GET "/api/req/requirements?${QS}"
    GW18_CODE="$HTTP_CODE"; GW18_BODY="$HTTP_BODY"
    api_direct GET "$REQ_URL" "/requirements?${QS}" "" "${HDR18[@]}"
    DI18_CODE="$HTTP_CODE"; DI18_BODY="$HTTP_BODY"
    GW18_BYTES="$(printf '%s' "$GW18_BODY" | wc -c | tr -d '[:space:]')"
    DI18_BYTES="$(printf '%s' "$DI18_BODY" | wc -c | tr -d '[:space:]')"

    if [[ "$GW18_CODE" == "200" && "$DI18_CODE" == "200" ]] \
        && cmp -s <(printf '%s' "$GW18_BODY") <(printf '%s' "$DI18_BODY"); then
        pass "8080 与直连 8082 相同中文 keyword 响应体逐字节一致（${HAN_KW} · ${GW18_BYTES}B == ${DI18_BYTES}B）"
    else
        fail "8080 与直连 8082 响应体不一致（keyword='${HAN_KW}' 8080=${GW18_CODE}/${GW18_BYTES}B 8082=${DI18_CODE}/${DI18_BYTES}B）" \
            "网关只能 StripPrefix 后原样透传：别在网关侧改写/二次编码 query（AC-21 的反证）"
    fi

    GW18_TOTAL="$(printf '%s' "$GW18_BODY" | jq -r '.total // empty' 2>/dev/null || true)"
    DI18_TOTAL="$(printf '%s' "$DI18_BODY" | jq -r '.total // empty' 2>/dev/null || true)"
    if [[ "$GW18_TOTAL" =~ ^[0-9]+$ && "$GW18_TOTAL" == "$DI18_TOTAL" ]] && (( GW18_TOTAL >= 1 )); then
        pass "两份 body 的 total 同源（${GW18_TOTAL} == ${DI18_TOTAL}，keyword 命中池中标题）"
    else
        fail "两份 body 的 total 不同源（8080='${GW18_TOTAL}' 8082='${DI18_TOTAL}'，keyword='${HAN_KW}'）" \
            "total 必须由同一查询计划算出；0=0 不算通过（keyword 必须真命中池中标题）"
    fi
fi

printf '\n'
if (( PASS != TOTAL || TOTAL == 0 )); then
    err "platform-test-negative: ${FAIL} 项失败 / ${TOTAL} 项"
    printf '%s\n' "$FAILED_LINES" >&2
    exit 1
fi
ok "platform-test-negative: ${PASS}/${TOTAL} 条负向用例全部按预期被拒且不落库"
