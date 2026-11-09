#!/usr/bin/env bash
# q5-waiver-replay.sh — AC-6 规则级豁免 + AC-7 快照回放。
#
# 覆盖：QG-04 不可豁免（400 RULE_NOT_WAIVABLE）、一次只能豁免一条规则、期限 1–30 天、
# 非 PMO 不能自批（403）、PMO 批准后 QG-03 变 WAIVED 且整门 PASS_WITH_WAIVER、
# 一条豁免不能放过其它失败规则（QG-04 仍 FAIL）、到期后惰性失效并重新 FAIL、
# 撤销后立即失效、快照 replay 与固化时逐字节一致、历史快照不受新执行影响。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

require_stack

section "q5 · 准备 fixture（94.7% 通过率 + 已签报告，QG-03 天然 FAIL）"
READ_OUT="$(q_build_release_case "豁免版本" 18 1)"
REL="$(awk '{print $1}' <<<"$READ_OUT")"
PLAN="$(awk '{print $2}' <<<"$READ_OUT")"
RPT="$(awk '{print $3}' <<<"$READ_OUT")"
DEP="$(awk '{print $4}' <<<"$READ_OUT")"
Q_REQ_ID="$(awk '{print $5}' <<<"$READ_OUT")"
[[ -n "$REL" && -n "$RPT" && -n "$Q_REQ_ID" ]] || { err "fixture 构造失败（release=${REL:-} report=${RPT:-} req=${Q_REQ_ID:-}）"; exit 1; }
info "releaseId=${REL} planId=${PLAN} reportId=${RPT} deployOrderId=${DEP}"

GATE_JSON="$(q_gate_snapshot "$REL" "$DEP")"
if jq -e '.rules[]? | select(.code=="QG-03" and .status=="FAIL")' <<<"$GATE_JSON" >/dev/null; then
    pass "基线：QG-03 FAIL（通过率 94.7%）"
else
    fail "基线 QG-03 未 FAIL，豁免断言失去前提（result=$(jq -r '.result' <<<"$GATE_JSON")）" "先确认报告已签发且通过率 <95"
fi
BASE_RESULT="$(jq -r '.result' <<<"$GATE_JSON")"

section "q5 · 申请校验：字段、单规则、期限、QG-04 红线"
api_call POST /api/deliver/quality-gates/waivers '{"releaseId":0}'
deny "缺字段 → 400 WAIVER_FIELDS_REQUIRED" 400 WAIVER_FIELDS_REQUIRED
api_call POST /api/deliver/quality-gates/waivers \
    "$(jq -cn --argjson r "$REL" '{releaseId:$r,ruleCodes:["QG-02","QG-03"],reason:"两条一起免",impactScope:"全部",expiryDays:7}')"
deny "一次多规则 → 400 WAIVER_RULE_SINGLE_ONLY" 400 WAIVER_RULE_SINGLE_ONLY
api_call POST /api/deliver/quality-gates/waivers \
    "$(jq -cn --argjson r "$REL" '{releaseId:$r,ruleCode:"QG-03",reason:"通过率略低",impactScope:"本版本",expiryDays:0}')"
deny "期限 0 天 → 400 WAIVER_EXPIRY_INVALID" 400 WAIVER_EXPIRY_INVALID
api_call POST /api/deliver/quality-gates/waivers \
    "$(jq -cn --argjson r "$REL" '{releaseId:$r,ruleCode:"QG-03",reason:"通过率略低",impactScope:"本版本",expiryDays:31}')"
deny "期限 31 天 → 400 WAIVER_EXPIRY_INVALID" 400 WAIVER_EXPIRY_INVALID
api_call POST /api/deliver/quality-gates/waivers \
    "$(jq -cn --argjson r "$REL" '{releaseId:$r,ruleCode:"QG-04",reason:"P1 明天修",impactScope:"本版本",expiryDays:3}')"
deny "QG-04 硬红线申请豁免 → 400 RULE_NOT_WAIVABLE" 400 RULE_NOT_WAIVABLE
same_count "被拒的豁免不落库" quality_gate_waiver "release_id=${REL}" "0"

section "q5 · 申请 / 非 PMO 自批 403 / PMO 批准生效"
api_call POST /api/deliver/quality-gates/waivers \
    "$(jq -cn --argjson r "$REL" '{releaseId:$r,ruleCode:"QG-03",reason:"通过率 94.7%，失败用例已修复待回归",impactScope:"本版本准入",expiryDays:7}')"
expect "申请豁免 201" 201
WAIVER="$(jget "$HTTP_BODY" '.id')"
[[ -n "$WAIVER" ]] || { err "豁免申请未返回 id"; exit 1; }
expect_jq "初始状态 PENDING" '.status' 'PENDING'
info "waiverId=${WAIVER}"
api_call POST "/api/deliver/quality-gates/waivers/${WAIVER}/approve" '{"comment":"自己批自己"}'
deny "申请人非 PMO 自批 → 403 SCOPE_DENIED" 403 SCOPE_DENIED
q_login_as tl1 >/dev/null 2>&1
WAIVER_APPROVER_TOKEN="$API_TOKEN"; WAIVER_APPROVER_TENANT="$API_TENANT"
api_call POST "/api/deliver/quality-gates/waivers/${WAIVER}/approve" '{"comment":"PMO 同意，限期回归"}'
expect "PMO 批准 200" 200
expect_jq "豁免状态 ACTIVE" '.status' 'ACTIVE'
Q_ADMIN_LOGIN_OK=1

section "q5 · 生效后门禁 PASS_WITH_WAIVER（QG-03 WAIVED，其它规则不变）"
api_call POST "/api/deliver/quality-gates/${REL}/evaluate" "$(jq -cn --argjson d "$DEP" '{deployOrderId:$d,requirementIds:[]}')"
expect "重新求值 200" 200
if jq -e '.result == "PASS_WITH_WAIVER"' <<<"$HTTP_BODY" >/dev/null; then
    pass "整门结果 PASS_WITH_WAIVER"
else
    fail "整门结果 $(jq -r '.result' <<<"$HTTP_BODY")（期望 PASS_WITH_WAIVER）" "只有被豁免的规则可以 WAIVED，其它仍须 PASS"
fi
if jq -e '.rules[]? | select(.code=="QG-03" and (.status=="WAIVED" or .status=="PASS_WITH_WAIVER"))' <<<"$HTTP_BODY" >/dev/null; then
    pass "QG-03 标记为已豁免"
else
    fail "QG-03 未标记豁免（实际: $(jq -c '.rules[]? | select(.code=="QG-03")' <<<"$HTTP_BODY")）"
fi
expect_jq "豁免信息进入快照" '(.waivers // []) | length' '1'

section "q5 · 一条豁免不能放过整门（QG-04 FAIL 仍拦住）"
api_call POST /api/deliver/defects "$(jq -cn --argjson r "$REL" --argjson q "$Q_REQ_ID" \
    '{releaseId:$r,requirementId:$q,title:"豁免期间的 P1",description:"硬红线",severity:"P1",priority:"P1"}')"
DEF="$(jget "$HTTP_BODY" '.id')"
GATE_JSON="$(q_gate_snapshot "$REL" "$DEP")"
if jq -e '.result == "FAIL"' <<<"$GATE_JSON" >/dev/null; then
    pass "QG-04 FAIL 覆盖豁免，整门 FAIL"
else
    fail "有未关闭 P1 时整门结果 $(jq -r '.result' <<<"$GATE_JSON")（期望 FAIL）" "任一 FAIL 覆盖豁免"
fi
if jq -e '[.rules[]? | select(.status=="FAIL") | .code] | index("QG-04") != null' <<<"$GATE_JSON" >/dev/null; then
    pass "QG-04 是 FAIL 规则（rules 契约）"
else
    fail "QG-04 未出现在 FAIL 规则中（实际: $(jq -c '[.rules[]? | {code,status}]' <<<"$GATE_JSON")）"
fi
if jq -e '[.rules[]? | select(.code=="QG-03") | .status] | index("WAIVED") != null' <<<"$GATE_JSON" >/dev/null; then
    pass "QG-03 已豁免（WAIVED，不再拦门）"
else
    fail "QG-03 未标记 WAIVED（实际: $(jq -c '.rules[]? | select(.code=="QG-03")' <<<"$GATE_JSON")）"
fi
api_call POST "/api/req/deploy-orders/${DEP}/submit" '{}'
deny "硬红线未过时提交 → 409 QUALITY_GATE_FAILED" 409 QUALITY_GATE_FAILED
if jq -e '[(.detail.failedRules // [])[] | if type=="object" then .code else . end] | index("QG-04") != null' <<<"$HTTP_BODY" >/dev/null; then
    pass "409 detail.failedRules 把 QG-04 交给前端常驻展示"
else
    fail "409 detail 未突出 QG-04（实际: $(jq -c '.detail.failedRules' <<<"$HTTP_BODY")）"
fi
api_call POST "/api/deliver/defects/${DEF}/actions" '{"action":"CLOSE","comment":"已修复"}'

section "q5 · 到期失效（expiry_at 过期后惰性置 EXPIRED）"
db_q "UPDATE quality_gate_waiver SET expiry_at = DATE_SUB(NOW(3), INTERVAL 1 HOUR) WHERE id=${WAIVER}" >/dev/null
GATE_JSON="$(q_gate_snapshot "$REL" "$DEP")"
if jq -e '.rules[]? | select(.code=="QG-03" and .status=="FAIL")' <<<"$GATE_JSON" >/dev/null; then
    pass "豁免过期后 QG-03 重新 FAIL"
else
    fail "豁免过期后 QG-03 仍被视为通过" "到期必须失效，且不改历史快照"
fi
EXPIRED_STATUS="$(db_q "SELECT status FROM quality_gate_waiver WHERE id=${WAIVER}")"
if [[ "$EXPIRED_STATUS" == "EXPIRED" ]]; then
    pass "过期豁免落库为 EXPIRED"
else
    skip "过期状态为 ${EXPIRED_STATUS:-<空>}（惰性更新可能发生在下次查询，记录偏差）"
fi

section "q5 · 撤销（ACTIVE→REVOKED）"
q_admin_login
api_call POST /api/deliver/quality-gates/waivers \
    "$(jq -cn --argjson r "$REL" '{releaseId:$r,ruleCode:"QG-03",reason:"再申请一次",impactScope:"本版本",expiryDays:5}')"
expect "再次申请 201" 201
WAIVER2="$(jget "$HTTP_BODY" '.id')"
API_TOKEN="$WAIVER_APPROVER_TOKEN"; API_TENANT="$WAIVER_APPROVER_TENANT"
api_call POST "/api/deliver/quality-gates/waivers/${WAIVER2}/approve" '{"comment":"同意"}'
expect "PMO 批准第二条 200" 200
q_admin_login
api_call POST "/api/deliver/quality-gates/waivers/${WAIVER2}/revoke" '{"reason":"失败用例重新出现"}'
expect "撤销 200" 200
expect_jq "撤销后 REVOKED" '.status' 'REVOKED'
GATE_JSON="$(q_gate_snapshot "$REL" "$DEP")"
if jq -e '.rules[]? | select(.code=="QG-03" and .status=="FAIL")' <<<"$GATE_JSON" >/dev/null; then
    pass "撤销后 QG-03 立即 FAIL"
else
    fail "撤销后 QG-03 仍通过" "撤销必须立即生效"
fi

section "q5 · AC-7 快照固化与回放"
internal_call POST "$DELIVER_URL" "/internal/quality-gates/${REL}/evaluate" \
    "$(jq -cn --argjson d "$DEP" '{deployOrderId:$d,requirementIds:[]}')"
expect "内部端点固化快照 200" 200
SNAP="$(jget "$HTTP_BODY" '.snapshotId')"
SNAP_RESULT="$(jget "$HTTP_BODY" '.result')"
SNAP_HASH="$(jget "$HTTP_BODY" '.hash')"
if [[ -n "$SNAP" && "$SNAP" != "0" && "$SNAP" != "null" ]]; then
    pass "固化产生快照 id=${SNAP}"
else
    fail "内部端点未固化快照（snapshotId=${SNAP:-none}）" "提交/内部评估必须落库，页面回放依赖它"
fi
SNAP_DB_HASH="$(db_q "SELECT hash FROM quality_gate_snapshot WHERE id=${SNAP}")"
if [[ "$SNAP_DB_HASH" == "$SNAP_HASH" ]]; then pass "返回 hash 与库中一致"; else fail "hash 不一致（返回 ${SNAP_HASH:-} / 库 ${SNAP_DB_HASH:-}）" "hash 必须是 snapshot_json 的 SHA-256"; fi

api_call POST "/api/deliver/quality-gates/snapshots/${SNAP}/replay" '{}'
expect "回放 200" 200
expect_jq "回放结果与固化一致" '.result' "$SNAP_RESULT"
if jq -e 'has("diff") and (.diff|has("changedRules"))' <<<"$HTTP_BODY" >/dev/null; then
    pass "回放带 diff.changedRules"
else
    skip "回放不带 diff.changedRules（记录偏差，不影响 AC-7 一致性）"
fi
expect_db "回放不写库（快照仍 1 条）" "SELECT COUNT(*) FROM quality_gate_snapshot WHERE id=${SNAP}" eq 1

section "q5 · 历史快照不受新执行影响"
BEFORE_HASH="$(db_q "SELECT SHA2(snapshot_json,256) FROM quality_gate_snapshot WHERE id=${SNAP}")"
api_call POST "/api/deliver/test-plans/${PLAN}/rounds" '{"scopeMode":"PREVIOUS_FAILED"}' >/dev/null 2>&1
api_call GET "/api/deliver/test-plans/${PLAN}"
if [[ "$HTTP_CODE" == "200" ]]; then
    ROUND="$(q_active_round "$PLAN")"
    if [[ -n "$ROUND" ]]; then
        q_exec_case "$PLAN" "$(q_plan_cases "$PLAN" | awk '{print $1}')" PASS
    fi
fi
AFTER_HASH="$(db_q "SELECT SHA2(snapshot_json,256) FROM quality_gate_snapshot WHERE id=${SNAP}")"
if [[ "$BEFORE_HASH" == "$AFTER_HASH" ]]; then
    pass "新执行后历史快照逐字节不变"
else
    fail "历史快照被改写" "快照必须不可变，回放只读 snapshot_json"
fi
api_call POST "/api/deliver/quality-gates/snapshots/${SNAP}/replay" '{}'
expect "新数据后回放仍返回旧结果" 200
expect_jq "回放结果仍是固化时的结论" '.result' "$SNAP_RESULT"

section "q5 · R2：NULL 快照 → 历史单据（legacy=true，不伪造质量门）"
# 先固化一份 6 项准入快照，确认正常路径不被 legacy 误标，再回退成历史两态。
api_call POST "/api/req/deploy-orders/${DEP}/evaluate" '{}'
expect "准入评估固化六项快照 200" 200
expect_jq "六项快照 legacy=false（不误伤新单）" '.legacy' 'false'
db_q "UPDATE deploy_order SET admission_result_json = NULL WHERE id=${DEP}" >/dev/null
api_call GET "/api/req/deploy-orders/${DEP}"
expect "NULL 快照详情 200" 200
expect_jq "NULL 快照判为历史单据（legacy=true）" '.admission.legacy' 'true'
expect_jq "NULL 快照不伪造质量门证据（gate=null）" '.admission.gate' 'null'
expect_jq "NULL 快照 items 为空" '.admission.items | length' '0'

section "q5 · R2：五项历史快照 → legacy=true（列表内条件不可达的根因回归）"
api_call POST "/api/req/deploy-orders/${DEP}/evaluate" '{}'
expect "重新固化六项快照 200" 200
ADM_JSON="$(db_q "SELECT admission_result_json FROM deploy_order WHERE id=${DEP}")"
# 历史快照的真实形状：没有第 6 项，也没有 gate 证据段。
FIVE_JSON="$(jq -c 'del(.items[] | select(.code=="QUALITY_GATE")) | del(.gate)' <<<"$ADM_JSON" 2>/dev/null)"
if [[ -n "$FIVE_JSON" && "$FIVE_JSON" != "null" ]] \
    && [[ "$(jq -r '.items | length' <<<"$FIVE_JSON" 2>/dev/null)" == "5" ]]; then
    HEX_JSON="$(printf '%s' "$FIVE_JSON" | xxd -p | tr -d '\n')"
    # admission_result_json 是 JSON 列：UNHEX 出来是 binary，直接赋值会被拒；转成 utf8mb4 再写。
    db_q "UPDATE deploy_order SET admission_result_json = CONVERT(UNHEX('${HEX_JSON}') USING utf8mb4) WHERE id=${DEP}" >/dev/null
    api_call GET "/api/req/deploy-orders/${DEP}"
    expect "五项快照详情 200" 200
    expect_jq "五项快照判为历史单据（legacy=true）" '.admission.legacy' 'true'
    expect_jq "五项快照 gate=null" '.admission.gate' 'null'
    expect_jq "五项快照 items 仍为 5 项" '.admission.items | length' '5'
    expect_jq "五项快照不含 QUALITY_GATE 项" '[.admission.items[].code] | index("QUALITY_GATE") == null' 'true'
else
    fail "无法构造五项历史快照（存储 JSON 缺少 QUALITY_GATE 项）" "先确认 POST /deploy-orders/{id}/evaluate 落库了 6 项"
fi

section "q5 · R2：前端横幅静态断言（提示必须挂在列表外，历史快照没有 QUALITY_GATE 项）"
PANEL_FILE="${ROOT}/web/src/components/quality/QualityGatePanel.vue"
if grep -q "legacyNotice" "$PANEL_FILE" 2>/dev/null; then
    pass "面板存在列表外横幅开关 legacyNotice"
else
    fail "面板缺少 legacyNotice（历史提示仍可能挂在列表项条件上）" "改成 legacy && !hasQualityGateItem 的独立横幅"
fi
if grep -q "质量门未启用（历史单据）" "$PANEL_FILE" 2>/dev/null; then
    pass "面板横幅文案为「质量门未启用（历史单据）」"
else
    fail "面板缺少可达的历史单据文案" "文案必须与 PRD/设计逐字一致"
fi
if grep -q "item.code === 'QUALITY_GATE' && legacy" "$PANEL_FILE" 2>/dev/null; then
    fail "旧不可达条件 item.code === 'QUALITY_GATE' && legacy 仍在" "历史快照没有该项，该条件永不成立"
else
    pass "旧的列表项内不可达条件已删除"
fi

guard q5-waiver-replay
