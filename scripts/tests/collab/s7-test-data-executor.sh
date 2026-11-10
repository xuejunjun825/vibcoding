#!/usr/bin/env bash
# s7-test-data-executor.sh — AC-17 CTest 测试数据说明 + 执行器台账：
# 说明可写可锁（COMPLETED 不可改、>2000 字拒绝、revision 并发冲突）、附件挂 TEST_PLAN、
# 执行器健康派生（DISABLED/NEVER/ONLINE/STALE/OFFLINE）与上报联动（停用执行器上报 422 且不落执行记录）。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

require_stack
trap i_on_exit EXIT

section "s7 · 执行器台账与健康派生"
api_call GET "${C_DELIVER}/test-executors"
expect "GET /test-executors 200" 200
expect_num "台账至少有 2 台（种子，跨轮只增不减）" "$(jget "$HTTP_BODY" '.total')" ge 2
expect_jq "每台都带派生健康态" '[.items[] | has("health")] | all' true
NEVER_ID="$(jget "$HTTP_BODY" '[.items[] | select(.health=="NEVER")][0].id')"
DISABLED_ID="$(jget "$HTTP_BODY" '[.items[] | select(.health=="DISABLED")][0].id')"
if [[ -n "$NEVER_ID" && "$NEVER_ID" != "null" && -n "$DISABLED_ID" && "$DISABLED_ID" != "null" ]]; then
    pass "种子里同时有 NEVER（从未上报）与 DISABLED（停用）"
else
    fail "健康派生缺 NEVER/DISABLED（never=${NEVER_ID:-?} disabled=${DISABLED_ID:-?}）" \
        "健康是派生值：ExecutorHealth.of(enabled,lastReportAt,now) 不能在页面里再判一次"
fi
EXEC_GOOD="$(jget "$HTTP_BODY" "[.items[] | select(.id==${NEVER_ID:-0})][0].code")"
# 冻结时钟不方便从 HTTP 注入时，直接用 DB 改 last_report_at 验三档边界（阈值仍是单处常量）。
db_exec "UPDATE test_executor SET last_report_at=NOW(3) - INTERVAL 1 HOUR WHERE id=${NEVER_ID}" >/dev/null 2>&1 || true
api_call GET "${C_DELIVER}/test-executors"
expect_jq "1 小时前上报 → ONLINE" "[.items[] | select(.id==${NEVER_ID})][0].health" ONLINE
db_exec "UPDATE test_executor SET last_report_at=NOW(3) - INTERVAL 30 HOUR WHERE id=${NEVER_ID}" >/dev/null 2>&1 || true
api_call GET "${C_DELIVER}/test-executors"
expect_jq "30 小时前上报 → STALE（>24h）" "[.items[] | select(.id==${NEVER_ID})][0].health" STALE
db_exec "UPDATE test_executor SET last_report_at=NOW(3) - INTERVAL 100 HOUR WHERE id=${NEVER_ID}" >/dev/null 2>&1 || true
api_call GET "${C_DELIVER}/test-executors"
expect_jq "100 小时前上报 → OFFLINE（>72h）" "[.items[] | select(.id==${NEVER_ID})][0].health" OFFLINE
api_call GET "${C_DELIVER}/test-executors/${NEVER_ID}/reports?days=7"
expect "GET /{id}/reports 200" 200
expect_jq "上报按天分桶（数组）" '((.buckets? // .items? // .) | type)' array

section "s7 · 新建/改名/唯一"
api_call POST "${C_DELIVER}/test-executors" "$(jq -cn --arg c "s7-${RUN_TAG}" \
    '{code:$c,name:"s7 执行器",baseUrl:"http://127.0.0.1:19092",enabled:true}')"
if [[ "$HTTP_CODE" == "200" || "$HTTP_CODE" == "201" ]]; then pass "POST 新建执行器（HTTP ${HTTP_CODE}）"; else fail "POST 新建执行器 HTTP ${HTTP_CODE}"; fi
NEW_EXEC="$(jget "$HTTP_BODY" '.id')"
expect_jq "新建即 NEVER（还没上报）" '.health' NEVER
api_call POST "${C_DELIVER}/test-executors" "$(jq -cn --arg c "s7-${RUN_TAG}" '{code:$c,name:"重复码"}')"
deny "code 重复 → 409" 409 ""
api_call PUT "${C_DELIVER}/test-executors/${NEW_EXEC}" "$(jq -cn '{name:"s7 改名执行器",enabled:true}')"
expect "PUT 改名 200" 200
expect_jq "改名生效" '.name' "s7 改名执行器"

section "s7 · 测试数据说明"
REL="$(q_create_release "s7版本-${RUN_TAG}")" || exit 1
CASE="$(q_case_ids 1 | awk '{print $1}')"
PLAN="$(q_create_plan "$REL" "s7计划-${RUN_TAG}" "${CASE:-}")" || exit 1
api_call GET "${C_DELIVER}/test-plans/${PLAN}/data-note"
expect "GET /test-plans/{id}/data-note 200" 200
expect_jq "初始 revision=0 且未锁定" '[.revision, .locked] | join(",")' 0,false
expect_jq "带 attachments 数组" '(.attachments | type)' array
api_call PUT "${C_DELIVER}/test-plans/${PLAN}/data-note" \
    '{"dataNote":"账号 test01/Passw0rd!，数据集 ds-2026Q4，先清缓存再跑","revision":0}'
expect "PUT 说明（revision=0）200" 200
expect_jq "revision 前进到 1" '.revision' 1
api_call PUT "${C_DELIVER}/test-plans/${PLAN}/data-note" '{"dataNote":"抢写","revision":0}'
deny "旧 revision 保存 → 409 DATA_NOTE_CONFLICT" 409 DATA_NOTE_CONFLICT
LONG="$(printf 'x%.0s' $(seq 1 2001))"
api_call PUT "${C_DELIVER}/test-plans/${PLAN}/data-note" "$(jq -cn --arg t "$LONG" '{dataNote:$t,revision:1}')"
deny ">2000 字 → 400 DATA_NOTE_TOO_LONG" 400 DATA_NOTE_TOO_LONG
TMPFILE="$(mktemp)"; printf 'test data fixture %s\n' "$RUN_TAG" >"$TMPFILE"; RESPFILE="$(mktemp)"
HTTP_CODE="$(curl -s -o "$RESPFILE" -w '%{http_code}' -X POST "${GATEWAY_URL}${C_REQ}/attachments?bizType=TEST_PLAN&bizId=${PLAN}" \
    -H "Authorization: Bearer ${API_TOKEN}" -H "X-Tenant-Id: ${API_TENANT}" -F "file=@${TMPFILE};filename=data-${RUN_TAG}.txt" 2>/dev/null)" || true
HTTP_BODY="$(cat "$RESPFILE" 2>/dev/null || true)"; rm -f "$TMPFILE" "$RESPFILE"
expect "TEST_PLAN 附件可上传（attachments 在 req-service，返回 201）" 201
api_call GET "${C_DELIVER}/test-plans/${PLAN}/data-note"
expect_jq "说明里能读到 1 个附件" '.attachments | length' 1
db_exec "UPDATE test_plan SET status='COMPLETED', updated_at=NOW(3) WHERE id=${PLAN} AND tenant_id=${TENANT_ID}" >/dev/null 2>&1 || true
api_call PUT "${C_DELIVER}/test-plans/${PLAN}/data-note" '{"dataNote":"完成后还想改","revision":1}'
deny "计划 COMPLETED → 409 PLAN_DATA_LOCKED" 409 PLAN_DATA_LOCKED
expect_db "锁定后说明没被改掉" \
    "SELECT COUNT(*) FROM test_plan WHERE id=${PLAN} AND data_note NOT LIKE '%完成后还想改%'" eq 1 \
    "锁定必须在服务层生效，不能只在前端置灰"

section "s7 · 上报联动：停用执行器被拒"
api_call POST "${C_DELIVER}/admin/api-clients" "$(jq -cn --arg n "s7-ci-${RUN_TAG}" \
    '{name:$n,scopes:["test-execution"],ipWhitelist:["*"],rateLimitQps:1000}')"
expect "创建开放 API 客户端 201" 201
APP_KEY="$(jget "$HTTP_BODY" '.appKey')"
APP_SECRET="$(jget "$HTTP_BODY" '.secret // .clientSecret // .secretOnce')"
if [[ -n "$APP_KEY" && "$APP_KEY" != "null" && -n "$CASE" ]]; then
    OAPI_PATH="/openapi/v1/test-executions"
    # 上报时间必须取当下：ONLINE 窗口是 24h（ExecutorHealth.ONLINE_HOURS），写死的时间戳
    # 会在第二天同一时刻把「上报后转 ONLINE」翻成 STALE —— 门禁随墙钟变红，与代码无关。
    REPORT_AT="$(date +%Y-%m-%dT%H:%M:%S+08:00)"
    sign_for() {
        local digest ts="$1" body="$2"
        digest="$(printf '%s' "$body" | openssl dgst -sha256 -hex | awk '{print $NF}')"
        printf '%s' "POST
${OAPI_PATH}
${APP_KEY}
${ts}
${digest}" | openssl dgst -sha256 -hmac "${APP_SECRET}" -hex | awk '{print $NF}'
    }
    oapi_post() { # ts sig body → code, 响应体落 $OAPI_BODY
        local out; out="$(mktemp)"
        local code; code="$(curl -sS --max-time 10 -o "$out" -w '%{http_code}' -X POST "${GATEWAY_URL}${OAPI_PATH}" \
            -H "X-App-Key: ${APP_KEY}" -H "X-Timestamp: $1" -H "X-Signature: $2" \
            -H 'Content-Type: application/json' -d "$3" 2>/dev/null)"
        OAPI_BODY="$(cat "$out" 2>/dev/null || true)"; rm -f "$out"
        printf '%s' "$code"
    }
    BODY_OK="$(jq -cn --argjson c "$CASE" --argjson p "$PLAN" --arg r "s7run-${RUN_TAG}" --arg e "$EXEC_GOOD" --arg t "$REPORT_AT" \
        '{run_id:$r,case_id:$c,result:"PASS",duration_ms:900,executed_at:$t,plan_id:$p,executorCode:$e}')"
    BEFORE_EXEC="$(db_count test_execution "case_id=${CASE}")"
    code="$(oapi_post "$(date +%s)" "deadbeef" "$BODY_OK")"
    [[ "$code" == "401" ]] && pass "错签名 401（验签先于业务）" || fail "错签名返回 ${code}" "签名校验必须在解析 executorCode 之前"
    BODY_OFF="$(jq -cn --argjson c "$CASE" --argjson p "$PLAN" --arg r "s7off-${RUN_TAG}" --arg e "LEGACY-OFF" --arg t "$REPORT_AT" \
        '{run_id:$r,case_id:$c,result:"PASS",duration_ms:900,executed_at:$t,plan_id:$p,executorCode:$e}')"
    TS="$(date +%s)"
    code="$(oapi_post "$TS" "$(sign_for "$TS" "$BODY_OFF")" "$BODY_OFF")"
    [[ "$code" == "422" ]] && pass "停用执行器上报 → 422" || fail "停用执行器上报返回 ${code}，期望 422" "EXECUTOR_DISABLED 必须在落 test_execution 前拒绝"
    expect_num "被拒的上报不落执行记录" "$(db_count test_execution "case_id=${CASE}")" eq "$BEFORE_EXEC" "拒绝要早于插入，不能先写后删"
    TS="$(date +%s)"
    code="$(oapi_post "$TS" "$(sign_for "$TS" "$BODY_OK")" "$BODY_OK")"
    [[ "$code" == "200" ]] && pass "启用执行器上报 → 200（executorCode 不破坏既有 CI 契约）" \
        || fail "合法上报返回 ${code}：$(printf '%s' "$OAPI_BODY" | head -c 160)" "executorCode 新字段缺失或签名口径不符"
    api_call GET "${C_DELIVER}/test-executors"
    expect_jq "上报后该执行器转 ONLINE 且 reportCount≥1" \
        "[.items[] | select(.code==\"${EXEC_GOOD}\")][0] | (.health==\"ONLINE\") and (.reportCount>=1)" true
else
    info "没有可签名的客户端或缺口令夹具，跳过上报联动段"
fi

guard s7-test-data-executor
