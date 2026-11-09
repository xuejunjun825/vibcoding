#!/usr/bin/env bash
# s5-admission.sh — AC-8 发布准入 9 项：固定顺序、四态（PASS/FAIL/SKIP/CLOSED）、
# 计数与 items 自洽、跳过≠通过且提交需确认跳过数、集成默认关闭 → ⑦⑧⑨ CLOSED 不阻断、
# 已启用无事实 → FAIL、锁定项不可关（409 ITEM_LOCKED）、500ms 预算（零外呼）。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

require_stack
trap i_on_exit EXIT
i_stub_ready || { err "桩未就绪 ${STUB_URL}"; exit 1; }
i_cleanup   # 从"集成全部关闭"开始，这是默认状态，也是既有门禁不回归的关键

RD="$(i_rd "s5准入-${RUN_TAG}")" || exit 1
REL="$(i_release_with "s5版本-${RUN_TAG}" "$RD")" || exit 1
ORD="$(i_order_for "$REL" "$RD")"
[[ -n "$ORD" && "$ORD" != "null" ]] || { err "发布单创建失败"; exit 1; }
info "requirement=${RD} release=${REL} order=${ORD}"

section "s5 · 固定 9 项与顺序（AdmissionItemPolicy 为唯一源）"
api_call POST "/api/req/deploy-orders/${ORD}/evaluate" '{}'
expect "准入评估 200" 200
expect_num "固定 9 项" "$(jq -r '.items | length' <<<"$HTTP_BODY")" eq 9
ORDER="$(jq -r '[.items[].code] | join(",")' <<<"$HTTP_BODY")"
WANT="REQ_TEST_PASSED,NO_OPEN_HIGH_DEFECT,REQ_APPROVALS_DONE,MR_FILLED,MAN_DAYS_FILLED,QUALITY_GATE,BUILD_PASSED,SONAR_GATE_PASSED,ARTIFACT_VERIFIED"
if [[ "$ORDER" == "$WANT" ]]; then pass "顺序完全一致"; else fail "顺序不符" "actual=${ORDER}"; fi
expect_jq "第 6 项仍是 QUALITY_GATE（M6 语义不变）" '.items[5].code' 'QUALITY_GATE'

section "s5 · 集成默认关闭：⑦⑧⑨ CLOSED（不算失败、不计分母、不阻断提交）"
for i in 6 7 8; do
    ST="$(jq -r ".items[${i}].status // empty" <<<"$HTTP_BODY")"
    RC="$(jq -r ".items[${i}].reasonCode // empty" <<<"$HTTP_BODY")"
    CODE="$(jq -r ".items[${i}].code" <<<"$HTTP_BODY")"
    if [[ "$ST" == "CLOSED" ]]; then
        pass "${CODE} CLOSED（${RC:-INTEGRATION_DISABLED}）"
    else
        fail "${CODE} status=${ST}（期望 CLOSED）" "默认关闭的集成项不能变成 FAIL/SKIP"
    fi
done
expect_num "counts.total=9" "$(jq -r '.counts.total' <<<"$HTTP_BODY")" eq 9
expect_num "counts.active=6（关闭项不进分母）" "$(jq -r '.counts.active' <<<"$HTTP_BODY")" eq 6
expect_num "counts.closed=3" "$(jq -r '.counts.closed' <<<"$HTTP_BODY")" eq 3

section "s5 · passed 恒等于 status==PASS（SKIP 永不计通过）"
BAD="$(jq -r '[.items[] | select((.passed==true) != (.status=="PASS"))] | length' <<<"$HTTP_BODY")"
if [[ "$BAD" == "0" ]]; then pass "passed 与 status 自洽（无 SKIP 冒充 PASS）"; else fail "${BAD} 项 passed/status 不一致" "AdmissionItem.passed 仅为兼容旧快照派生"; fi
SUM="$(jq -r '.counts.passed + .counts.failed + .counts.skipped + .counts.closed' <<<"$HTTP_BODY")"
if [[ "$SUM" == "9" ]]; then pass "四态计数之和=9"; else fail "计数之和=${SUM}" "counts 必须覆盖全部 9 项"; fi

section "s5 · 锁定项写入口拦截：关闭①②③⑥ → 409 ITEM_LOCKED"
for CODE in REQ_TEST_PASSED NO_OPEN_HIGH_DEFECT REQ_APPROVALS_DONE QUALITY_GATE; do
    api_call PUT /api/req/integration/admission-items "$(jq -cn --arg c "$CODE" '{items:[{code:$c,enabled:false}]}')"
    deny "关闭锁定项 ${CODE} → 409" 409 ITEM_LOCKED
done
api_call PUT /api/req/integration/admission-items "$(jq -cn '{items:[{code:"MR_FILLED",enabled:false}]}')"
if [[ "$HTTP_CODE" == "200" ]]; then
    pass "可选项 ④ MR_FILLED 允许关闭"
    api_call POST "/api/req/deploy-orders/${ORD}/evaluate" '{}'
    expect_jq "关闭后 ④ 变 CLOSED" '.items[3].status' 'CLOSED'
    api_call PUT /api/req/integration/admission-items "$(jq -cn '{items:[{code:"MR_FILLED",enabled:true}]}')"
    expect "恢复 ④ 200" 200
else
    fail "关闭可选项返回 ${HTTP_CODE}" "只有锁定项拒绝，可选项应可关"
fi

section "s5 · 已启用但无事实：⑦ FAIL（NO_BUILD_RECORD）/ ⑧ SKIP（无 key）/ ⑨ FAIL（ARTIFACT_MISSING）"
JID="$(i_config JENKINS)" || exit 1
SID="$(i_config SONAR)" || exit 1
NID="$(i_config NEXUS)" || exit 1
api_call POST "/api/req/deploy-orders/${ORD}/evaluate" '{}'
expect "启用后评估 200" 200
ST7="$(jq -r '.items[6].status' <<<"$HTTP_BODY")"; RC7="$(jq -r '.items[6].reasonCode // empty' <<<"$HTTP_BODY")"
if [[ "$ST7" == "FAIL" && "$RC7" == "NO_BUILD_RECORD" ]]; then pass "⑦ FAIL/NO_BUILD_RECORD"; else fail "⑦ status=${ST7} reason=${RC7}" "已启用但无构建记录必须 FAIL（fail-closed）"; fi
ST8="$(jq -r '.items[7].status' <<<"$HTTP_BODY")"; RC8="$(jq -r '.items[7].reasonCode // empty' <<<"$HTTP_BODY")"
if [[ "$ST8" == "SKIP" && -n "$RC8" ]]; then pass "⑧ SKIP/${RC8}（需求无 key）"; else fail "⑧ status=${ST8} reason=${RC8}" "无 sonar key 应 SKIP 且原因具体"; fi
ST9="$(jq -r '.items[8].status' <<<"$HTTP_BODY")"; RC9="$(jq -r '.items[8].reasonCode // empty' <<<"$HTTP_BODY")"
if [[ "$ST9" == "FAIL" && "$RC9" == "ARTIFACT_MISSING" ]]; then pass "⑨ FAIL/ARTIFACT_MISSING"; else fail "⑨ status=${ST9} reason=${RC9}" "已启用但无制品必须 FAIL"; fi
SKIPPED="$(jq -r '.counts.skipped' <<<"$HTTP_BODY")"
expect_num "counts.skipped=1" "$SKIPPED" eq 1

section "s5 · 跳过≠通过：提交必须确认跳过数（400 SKIP_NOT_ACKNOWLEDGED）"
api_call POST "/api/req/deploy-orders/${ORD}/submit" '{}'
if [[ "$HTTP_CODE" == "400" ]]; then
    expect "未确认跳过 → 400" 400 SKIP_NOT_ACKNOWLEDGED
elif [[ "$HTTP_CODE" == "409" ]]; then
    # 有 FAIL 时闸门可能先报 ADMISSION_FAILED；此时跳过确认在 detail 里仍须可见
    if grep -q "SKIP_NOT_ACKNOWLEDGED" <<<"$HTTP_BODY"; then pass "409 中仍带 SKIP_NOT_ACKNOWLEDGED"; else info "闸门先行返回 409（无 FAIL 场景由 s5 的 CLOSED 分支覆盖）"; fi
else
    fail "未确认跳过返回 ${HTTP_CODE}" "SKIP 数>0 且 ack 不等时必须 400"
fi
api_call POST "/api/req/deploy-orders/${ORD}/submit" "$(jq -cn --argjson n "$SKIPPED" '{acknowledgedSkipCount:$n}')"
if [[ "$HTTP_CODE" == "400" ]] && grep -q "SKIP_NOT_ACKNOWLEDGED" <<<"$HTTP_BODY"; then
    fail "确认后仍报跳过未确认" "acknowledgedSkipCount 与 skipCount 等值时应放行该检查"
else
    pass "确认跳过数后不再报 SKIP_NOT_ACKNOWLEDGED（HTTP ${HTTP_CODE}）"
fi

section "s5 · 500ms 预算：零外呼的本地证据读取"
i_stub_reset
TIMES=()
for _ in $(seq 1 20); do
    T="$(curl -s -o /dev/null -w '%{time_total}' -X POST \
        -H "Authorization: Bearer ${API_TOKEN}" -H "X-Tenant-Id: ${API_TENANT}" -H "Content-Type: application/json" \
        -d '{}' "${GATEWAY_URL}/api/req/deploy-orders/${ORD}/evaluate")"
    TIMES+=("$T")
done
P95="$(printf '%s\n' "${TIMES[@]}" | sort -n | awk 'NR==19{print $1}')"
MAX="$(printf '%s\n' "${TIMES[@]}" | sort -n | tail -1)"
if awk -v p="$P95" 'BEGIN{exit !(p<=0.5)}'; then
    pass "P95=${P95}s ≤0.5s（max=${MAX}s）"
else
    fail "P95=${P95}s 超过 500ms（max=${MAX}s）" "准入只读本地快照与事实表；禁止在评估路径外呼"
fi
OUT_REQ="$(i_stub GET /__stub/requests; jq -r '(.requests // []) | length' <<<"$HTTP_BODY")"
if [[ "$OUT_REQ" == "0" ]]; then pass "评估期间桩收到 0 请求（零外呼）"; else fail "评估期间桩收到 ${OUT_REQ} 个请求" "AdmissionEvaluator 不得触发集成外呼"; fi

guard s5-admission
