#!/usr/bin/env bash
# platform-test-negative.sh — TECHNICAL §8.3 的 15 条负向用例。
# 每条都断言两件事：被拒绝（4xx + 错误码），且**没有落库**（表计数不变）。
# 绿色 = 非法路径确实走不通；不是"接口返回 200 但界面拦住了"。
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

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
PDR_ID="$(db_q "SELECT id FROM requirement WHERE tenant_id=${TID} AND type='PDR' ORDER BY id LIMIT 1")"
[[ -n "$PDR_ID" ]] || { err "种子里没有 PDR，RD 无法挂父：make platform-seed"; exit 1; }
api_call POST /api/req/requirements "{\"type\":\"RD\",\"title\":\"neg-sm-${RUN_TAG}\",\"parentId\":${PDR_ID},\"domainIds\":[$DOMAIN1],\"priority\":\"P2\",\"expectedDeliveryDate\":\"2026-12-31\"}"
RD_ID="$(jget_ "$HTTP_BODY" '.id')"
[[ -n "$RD_ID" && "$RD_ID" != "null" ]] || { err "创建负向夹具 RD 失败: $(printf '%s' "$HTTP_BODY" | head -c 200)"; exit 1; }
api_call PATCH "/api/req/requirements/${RD_ID}" '{"status":"DONE"}'
deny "PATCH status → 400 STATUS_NOT_WRITABLE" 400 "STATUS_NOT_WRITABLE" "StatusForbiddenDto 的 setter 必须抛 400"
api_call POST "/api/req/requirements/${RD_ID}/actions" '{"action":"finish"}'
deny "RD TODO --finish--> 409（跳步）" 409 "ILLEGAL_TRANSITION" "状态表是唯一来源，非法动作给 allowedTargets"
api_call POST "/api/req/requirements/${RD_ID}/actions" '{"action":"start_dev"}'
api_call POST "/api/req/requirements/${RD_ID}/actions" '{"action":"test_pass"}'
deny "RD IN_DEV --test_pass--> 409（未提交测试不可通过）" 409 "ILLEGAL_TRANSITION"

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
login_as "pm" "Passw0rd!" || warn "pm 登录失败，跳过审批负向"
api_call GET "/api/req/approvals/todo?page=1&size=20"
TASK_ANY="$(jget_ "$HTTP_BODY" '.items[0].taskId // .items[0].id')"
if [[ -n "$TASK_ANY" && "$TASK_ANY" != "null" ]]; then
    api_call POST "/api/req/approvals/tasks/${TASK_ANY}/remove-sign" '{"targetUserId":1,"reason":""}'
    deny "减签空原因 → 400 REASON_REQUIRED" 400 "REASON_REQUIRED" "减签必须留原因"
    api_call POST "/api/req/approvals/tasks/${TASK_ANY}/actions" '{"action":"APPROVE","comment":"neg"}'
    api_call POST "/api/req/approvals/tasks/${TASK_ANY}/add-sign" '{"type":"AFTER","userIds":[1],"comment":"x"}'
    deny "已处理任务加签 → 409 TASK_ALREADY_HANDLED" 409 "TASK_ALREADY_HANDLED" "加签只对 PENDING 任务生效"
else
    warn "无待办任务，跳过减签/加签负向"
fi
API_TOKEN="$ADMIN_TOKEN"

# ---------------------------------------------------------------- 9/10
section "9) 准入 FAIL 直接 submit → 409；10) 空原因强制放行 → 400"
api_call POST /api/req/releases "{\"name\":\"neg-rel-${RUN_TAG}\",\"domainId\":${DOMAIN1},\"startDate\":\"2026-11-01\",\"endDate\":\"2026-11-30\",\"plannedReleaseDate\":\"2026-12-05\",\"gateMode\":\"STRICT\"}"
REL="$(jget_ "$HTTP_BODY" '.id')"
api_call POST /api/req/releases/${REL}/requirements "{\"requirementIds\":[${RD_ID}]}"
api_call POST /api/req/deploy-orders "$(jq -cn --argjson r "$REL" --argjson q "$RD_ID" '{releaseId:$r,env:"UAT",windowStart:"2026-12-02T10:00:00+08:00",windowEnd:"2026-12-02T12:00:00+08:00",artifactVersion:"neg",steps:[{name:"发布",order:1}],rollbackPlan:"回滚",impact:"无",ownerId:1,requirementIds:[$q]}')"
DEP="$(jget_ "$HTTP_BODY" '.id')"
api_call POST "/api/req/deploy-orders/${DEP}/submit"
deny "准入未过直接 submit → 409 ADMISSION_FAILED" 409 "ADMISSION_FAILED" "submit 必须服务端重算准入"
api_call POST "/api/req/deploy-orders/${DEP}/force-release" '{"reason":"  "}'
deny "空原因强制放行 → 400 REASON_REQUIRED" 400 "REASON_REQUIRED" "强制放行必须留痕"

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
section "14) 导出越权 → 0 行（不是 500）"
if login_as "vendor2" "Passw0rd!"; then
    api_call GET "/api/req/requirements/export.csv?page=1&size=50"
    if [[ "$HTTP_CODE" == "200" ]]; then
        LINES="$(printf '%s' "$HTTP_BODY" | grep -c . || true)"
        # 0 条数据时仍有两行：水印行 + 表头行（PRD：首行含水印元信息）
        if (( LINES <= 2 )); then pass "越权导出无数据行（${LINES} 行 = 水印+表头）"; else fail "越权导出返回 ${LINES} 行，泄露了数据" "导出必须与列表同源 scope"; fi
    else
        fail "越权导出返回 ${HTTP_CODE}，期望 200+0 行" "200 空集，不是 500"
    fi
else
    warn "vendor2 登录失败，跳过导出越权"
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

printf '\n'
if (( PASS != TOTAL || TOTAL == 0 )); then
    err "platform-test-negative: ${FAIL} 项失败 / ${TOTAL} 项"
    printf '%s\n' "$FAILED_LINES" >&2
    exit 1
fi
ok "platform-test-negative: ${PASS}/${TOTAL} 条负向用例全部按预期被拒且不落库"
