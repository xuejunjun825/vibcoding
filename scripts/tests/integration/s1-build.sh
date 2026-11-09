#!/usr/bin/env bash
# s1-build.sh — AC-3 Jenkins 构建：202+QUEUED、活动构建唯一（二次 409 不新增）、
# 状态机不回退、双通道（轮询 + webhook）、401 不落库、重复/乱序/未知事件、2h 超时、
# 失败转缺陷幂等、console_url 同源、最近 5 条、后台重试上限（1/2/4/8/16s，6 次尝试）。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

require_stack
trap i_on_exit EXIT
i_stub_ready || { err "桩未就绪 ${STUB_URL}"; exit 1; }
i_stub_reset
JID="$(i_config JENKINS)" || exit 1
RD="$(i_rd "s1构建-${RUN_TAG}")" || exit 1
REL="$(i_release_with "s1版本-${RUN_TAG}" "$RD")" || exit 1
ORD="$(i_order_for "$REL" "$RD")"
[[ -n "$ORD" && "$ORD" != "null" ]] || { err "发布单创建失败"; exit 1; }
info "config=${JID} requirement=${RD} release=${REL} deployOrder=${ORD}"

section "s1 · 触发构建：202 + QUEUED（HTTP 线程不等 Jenkins）"
api_call POST "/api/req/deploy-orders/${ORD}/builds" '{"branch":"main"}'
expect "触发 202" 202
expect_jq "立即返回 QUEUED" '.status' 'QUEUED'
B1="$(jget "$HTTP_BODY" '.buildId')"
if [[ "$B1" =~ ^[0-9]+$ ]]; then pass "返回 buildId=${B1}"; else fail "buildId 非数字: '$B1'" "202 必须带 buildId/status/correlationId"; fi
expect_db "build_record 落一行" "SELECT COUNT(*) FROM build_record WHERE deploy_order_id=${ORD}" eq 1

section "s1 · 活动构建唯一：同发布单二次触发 409 BUILD_IN_PROGRESS，且不新增行"
BEFORE_N="$(db_count build_record "deploy_order_id=${ORD}")"
api_call POST "/api/req/deploy-orders/${ORD}/builds" '{"branch":"main"}'
deny "二次触发 409 BUILD_IN_PROGRESS" 409 BUILD_IN_PROGRESS
same_count "重复触发不新增 build_record" build_record "deploy_order_id=${ORD}" "$BEFORE_N"

section "s1 · 轮询通道：refresh 读取桩状态（QUEUED→RUNNING）"
B1_NUM="$(db_q "SELECT COALESCE(build_number, queue_id) FROM build_record WHERE id=${B1}")"
[[ -n "$B1_NUM" && "$B1_NUM" != "NULL" ]] || B1_NUM=1
i_stub_build demo-job "$B1_NUM" RUNNING
api_call POST "/api/req/builds/${B1}/refresh"
expect "refresh 200" 200
expect_jq "状态推进 RUNNING" '.status' 'RUNNING' "BuildService.refresh 应按 Jenkins building/result 推进状态机"

section "s1 · webhook 验签：错误签名 / 过期时间戳 → 401 且不写业务行"
i_jenkins_webhook_raw "$JID" "$(i_jenkins_body "$B1" SUCCESS)" "sha256=deadbeef" "$(date +%s)"
expect "错误签名 401" 401
expect_jq "错误码 WEBHOOK_SIGNATURE_INVALID" '.errorCode // .code' 'WEBHOOK_SIGNATURE_INVALID'
i_jenkins_webhook "$JID" "$B1" SUCCESS "" -400
expect "过期时间戳 401" 401
expect_jq "错误码 WEBHOOK_TIMESTAMP_INVALID" '.errorCode // .code' 'WEBHOOK_TIMESTAMP_INVALID'
expect_db_text "401 后构建状态仍 RUNNING" "SELECT status FROM build_record WHERE id=${B1}" RUNNING

section "s1 · webhook 通道：成功回调推进终态并保留同源 console_url"
i_jenkins_webhook "$JID" "$B1" SUCCESS "${STUB_URL}/job/demo-job/${B1_NUM}/console"
expect "成功回调 200" 200
api_call GET "/api/req/builds/${B1}"
expect_jq "构建 SUCCESS" '.status' 'SUCCESS'
expect_jq "console_url 同源保留" '.consoleUrl | type' 'string'

section "s1 · 重复与乱序：重复只迁移一次，乱序不回退"
i_jenkins_webhook "$JID" "$B1" SUCCESS "${STUB_URL}/job/demo-job/${B1_NUM}/console"
expect "重复回调 200" 200
expect_jq "重复返回 DUPLICATE" '.ignored // .status // .result' 'DUPLICATE'
i_jenkins_webhook "$JID" "$B1" RUNNING "${STUB_URL}/job/demo-job/${B1_NUM}/console"
expect "乱序回调 200（不 5xx，避免 Jenkins 重投）" 200
expect_jq "乱序返回 OUT_OF_ORDER" '.ignored // .status // .result' 'OUT_OF_ORDER'
expect_db_text "乱序后仍 SUCCESS（状态机不回退）" "SELECT status FROM build_record WHERE id=${B1}" SUCCESS

section "s1 · 未知事件 200 IGNORED（不写业务行）"
PING_BODY='{"buildId":'"$B1"',"event":"stub-ping"}'
PING_TS="$(date +%s)"
PING_SIG="$(printf '%s' "${PING_TS}.${PING_BODY}" | openssl dgst -sha256 -hmac "$INTEG_SECRET" -hex | awk '{print $NF}')"
http_call POST "${GATEWAY_URL}/api/integration/webhooks/jenkins/${JID}" "$PING_BODY" \
    -H "X-Jenkins-Signature: sha256=${PING_SIG}" -H "X-Jenkins-Timestamp: ${PING_TS}" -H "X-Jenkins-Event: stub-ping"
expect "未知事件 200" 200
expect_jq "返回 IGNORED" '.ignored // .status // .result' 'IGNORED'

section "s1 · console_url 非同源 → 存 NULL（防钓鱼跳转）"
api_call POST "/api/req/deploy-orders/${ORD}/builds" '{"branch":"main"}'
B2="$(jget "$HTTP_BODY" '.buildId')"
if [[ "$B2" =~ ^[0-9]+$ ]]; then
    B2_NUM="$(db_q "SELECT COALESCE(build_number, queue_id) FROM build_record WHERE id=${B2}")"
    [[ -n "$B2_NUM" && "$B2_NUM" != "NULL" ]] || B2_NUM=2
    i_jenkins_webhook "$JID" "$B2" SUCCESS "https://evil.example.com/phish/console"
    expect "外源 console 回调 200" 200
    api_call GET "/api/req/builds/${B2}"
    expect_jq "异源 console_url 置空" '.consoleUrl // "NULL"' 'NULL' "ConsoleUrlGuard.sanitize：不同源存 NULL"
else
    fail "第二个构建触发失败 (HTTP ${HTTP_CODE})" "终态后应允许再次触发"
fi

section "s1 · 最近 5 条（按 id 倒序，limit 生效）"
api_call GET "/api/req/deploy-orders/${ORD}/builds?limit=5"
expect "构建列表 200" 200
expect_jq "返回 2 条历史" 'if type=="array" then length else (.items|length) end' '2'
expect_jq "最新在前" 'if type=="array" then .[0].id else .items[0].id end' "$B2"

section "s1 · 构建失败转缺陷：内部端点按 job#buildNumber 幂等"
DEF_BEFORE="$(db_count defect "tenant_id=${TENANT_ID} AND requirement_id=${RD}")"
ORD2="$(i_order_for "$REL" "$RD")"
api_call POST "/api/req/deploy-orders/${ORD2}/builds" '{"branch":"main"}'
B3="$(jget "$HTTP_BODY" '.buildId')"
B3_NUM="$(db_q "SELECT COALESCE(build_number, queue_id) FROM build_record WHERE id=${B3}")"
[[ -n "$B3_NUM" && "$B3_NUM" != "NULL" ]] || B3_NUM=3
i_jenkins_webhook "$JID" "$B3" FAILURE "${STUB_URL}/job/demo-job/${B3_NUM}/console"
expect "失败回调 200" 200
expect_db_text "构建置 FAILURE" "SELECT status FROM build_record WHERE id=${B3}" FAILURE
expect_db "失败转缺陷 +1" "SELECT COUNT(*) FROM defect WHERE tenant_id=${TENANT_ID} AND requirement_id=${RD}" eq $((DEF_BEFORE + 1))
i_jenkins_webhook "$JID" "$B3" FAILURE "${STUB_URL}/job/demo-job/${B3_NUM}/console"
expect "重复失败回调 200（幂等）" 200
expect_db "重复失败不重复建缺陷" "SELECT COUNT(*) FROM defect WHERE tenant_id=${TENANT_ID} AND requirement_id=${RD}" eq $((DEF_BEFORE + 1))

section "s1 · 2h 超时：timeout_at 到期由轮询任务置 FAILURE"
ORD3="$(i_order_for "$REL" "$RD")"
api_call POST "/api/req/deploy-orders/${ORD3}/builds" '{"branch":"main"}'
B4="$(jget "$HTTP_BODY" '.buildId')"
db_exec "UPDATE build_record SET timeout_at = NOW(3) - INTERVAL 2 MINUTE WHERE id=${B4}" >/dev/null
FAILED=0
for _ in $(seq 1 25); do
    api_call GET "/api/req/builds/${B4}"
    if [[ "$(jget "$HTTP_BODY" '.status')" == "FAILURE" ]]; then FAILED=1; break; fi
    sleep 1
done
if (( FAILED == 1 )); then pass "超时构建被扫描为 FAILURE"; else fail "超时构建仍 $(jget "$HTTP_BODY" '.status')" "BuildPollingJob 每 10s 扫 timeout_at < now 的活动构建"; fi
expect_jq "timedOut=true" '.timedOut' 'true' "BuildView.timedOut 由 timeout_at/状态派生"

section "s1 · 后台提交重试上限：5xx 连续失败 → 6 次尝试后 RETRY_EXHAUSTED"
ORD4="$(i_order_for "$REL" "$RD")"
i_stub_mode jenkins.submit 500
# 桩计数是全脚本累加的（前面几条构建也走过 jenkins.submit），必须取本次构建前的基线，
# 否则循环在第一条就 break：既数不出 6 次尝试，也会在重试还没跑完时就把 mode 改回 ok，
# 让真正耗尽的路径永远走不到 RETRY_EXHAUSTED。
BASE_TRIES="$(i_stub_count jenkins.submit)"
[[ "$BASE_TRIES" =~ ^[0-9]+$ ]] || BASE_TRIES=0
api_call POST "/api/req/deploy-orders/${ORD4}/builds" '{"branch":"main"}'
expect "提交失败也先返回 202（异步提交）" 202
B5="$(jget "$HTTP_BODY" '.buildId')"
DELTA=0
for _ in $(seq 1 45); do
    TRIES="$(i_stub_count jenkins.submit)"
    [[ "$TRIES" =~ ^[0-9]+$ ]] || TRIES=$BASE_TRIES
    DELTA=$(( TRIES - BASE_TRIES ))
    (( DELTA >= 6 )) && break
    sleep 1
done
expect_num "最多 6 次尝试（1/2/4/8/16s 退避）" "${DELTA:-0}" eq 6
# 第 6 次失败后写日志与断言之间有一个异步窗口：轮询到行出现（最多 10s），不靠 sleep 蒙。
EXH=0
for _ in $(seq 1 10); do
    EXH="$(db_q "SELECT COUNT(*) FROM integration_sync_log WHERE tenant_id=${TENANT_ID} AND \`system\`='JENKINS' AND result='RETRY_EXHAUSTED' AND created_at > NOW() - INTERVAL 3 MINUTE")"
    [[ "$EXH" =~ ^[0-9]+$ ]] || EXH=0
    (( EXH >= 1 )) && break
    sleep 1
done
expect_db "重试耗尽写 RETRY_EXHAUSTED 日志" \
    "SELECT COUNT(*) FROM integration_sync_log WHERE tenant_id=${TENANT_ID} AND \`system\`='JENKINS' AND result='RETRY_EXHAUSTED' AND created_at > NOW() - INTERVAL 3 MINUTE" ge 1
i_stub_mode jenkins.submit ok
api_call POST "/api/req/builds/${B5}/refresh"
expect "失败构建 refresh 不 5xx" 200

guard s1-build
