#!/usr/bin/env bash
# q4-gate-admission.sh — AC-5 发布准入第 6 项质量门。
#
# 覆盖：准入固定 9 项（M6 的 6 项语义不变、QUALITY_GATE 仍第 6；M7 追加 BUILD/SONAR/ARTIFACT）、
# 集成默认关闭时后三项为 CLOSED 不阻断、无报告 → QG-01 FAIL 且提交 409
# QUALITY_GATE_FAILED（带 failedRules 与 snapshotId）、报告签发后转为 PASS、未关闭 P1 → QG-04 FAIL、
# 通过率 94.7% → QG-03 FAIL、报告过期 → QG-02 FAIL、绕过网关直连 req 同样拦截、
# 强制放行已退役恒 409 FORCE_RELEASE_RETIRED。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

require_stack

section "q4 · 准备 fixture（版本 + 需求 + 已执行计划）"
CASE_IDS=($(q_case_ids 3))
REQ_ID="$(db_q "SELECT requirement_id FROM test_case WHERE id=${CASE_IDS[0]}")"
[[ -n "$REQ_ID" && "$REQ_ID" != "NULL" ]] || REQ_ID="$(db_q "SELECT id FROM requirement WHERE tenant_id=${TENANT_ID} ORDER BY id LIMIT 1")"
(( ${#CASE_IDS[@]} >= 3 )) && [[ -n "$REQ_ID" ]] || { err "种子用例/需求不足，无法构造准入 fixture"; exit 1; }
REL="$(q_create_release "门禁版本-${RUN_TAG}")" || exit 1
api_call POST "/api/req/releases/${REL}/requirements" "{\"requirementIds\":[${REQ_ID}]}"
PLAN="$(q_create_plan "$REL" "门禁计划-${RUN_TAG}" "${CASE_IDS[@]}")" || exit 1
api_call POST "/api/deliver/test-plans/${PLAN}/submit-review" '{}'
api_call POST "/api/deliver/test-plans/${PLAN}/approve" '{"comment":"同意"}'
api_call POST "/api/deliver/test-plans/${PLAN}/start" '{}'
q_exec_all "$PLAN" PASS
ROUND="$(q_active_round "$PLAN")"
[[ -n "$ROUND" ]] || ROUND="$(db_q "SELECT id FROM test_plan_round WHERE plan_id=${PLAN} AND active=1")"
q_close_round "$PLAN" "$ROUND" PASS
api_call POST "/api/deliver/test-plans/${PLAN}/complete" '{}'
DEP="$(q_create_deploy_order "$REL" "$REQ_ID" PROD)" || exit 1
info "releaseId=${REL} requirementId=${REQ_ID} planId=${PLAN} deployOrderId=${DEP}"

section "q4 · 准入 9 项结构（前 6 项含质量门，M7 三项在后）"
api_call POST "/api/req/deploy-orders/${DEP}/evaluate" '{}'
expect "准入评估 200" 200
expect_num "固定 9 项" "$(jq -r '.items | length' <<<"$HTTP_BODY")" eq 9
expect_jq "第 6 项仍是 QUALITY_GATE" '.items[5].code' 'QUALITY_GATE'
expect_jq "前 6 项顺序（M6 语义不变）" '[.items[0:6][].code] | join(",")' 'REQ_TEST_PASSED,NO_OPEN_HIGH_DEFECT,REQ_APPROVALS_DONE,MR_FILLED,MAN_DAYS_FILLED,QUALITY_GATE'
expect_jq "M7 三项顺序" '[.items[6:9][].code] | join(",")' 'BUILD_PASSED,SONAR_GATE_PASSED,ARTIFACT_VERIFIED'

section "q4 · 无已会签报告 → QG-01 FAIL，提交被 409 拦截"
GATE_JSON="$(q_gate_snapshot "$REL" "$DEP")"
GATE_RESULT="$(jq -r '.result' <<<"$GATE_JSON")"
if [[ "$GATE_RESULT" == "FAIL" ]]; then pass "门禁求值 FAIL"; else fail "无报告时门禁应为 FAIL（实际 ${GATE_RESULT}）" "QG-01 覆盖 100% + 已会签 RELEASE 报告"; fi
if jq -e '.rules[]? | select(.code=="QG-01" and .status=="FAIL")' <<<"$GATE_JSON" >/dev/null; then pass "QG-01 FAIL（报告缺失/未签）"; else fail "QG-01 未 FAIL" "规则状态必须是 FAIL，页面靠它渲染修复指引"; fi
if jq -e '.snapshotId == 0' <<<"$GATE_JSON" >/dev/null; then pass "页面预评估不固化快照（snapshotId=0）"; else fail "预评估却固化了快照" "只有内部端点/提交时才固化"; fi
api_call POST "/api/req/deploy-orders/${DEP}/submit" '{}'
deny "门禁 FAIL 时提交 → 409 QUALITY_GATE_FAILED" 409 QUALITY_GATE_FAILED
expect_jq "409 带 failedRules" '.detail.failedRules | length > 0' 'true'
expect_jq "failedRules 含 QG-01" '(.detail.failedRules | tostring) | test("QG-01")' 'true'
expect_jq "409 带 snapshotId" '.detail.snapshotId | type' 'number'

section "q4 · 签发报告后门禁转 PASS"
RPT="$(q_generate_release_report "$REL")"
[[ -n "$RPT" ]] || { fail "报告生成失败，门禁无法转 PASS" "先看 T5 报告服务"; guard q4-gate-admission; }
q_sign_release_report "$RPT" || true
GATE_JSON="$(q_gate_snapshot "$REL" "$DEP")"
GATE_RESULT="$(jq -r '.result' <<<"$GATE_JSON")"
if [[ "$GATE_RESULT" == "PASS" || "$GATE_RESULT" == "PASS_WITH_WAIVER" ]]; then
    pass "报告会签后门禁 ${GATE_RESULT}"
else
    fail "报告会签后门禁仍 ${GATE_RESULT}" "QG-02 需要 SIGNED、未过期、digest 一致；QG-03 通过率 ≥95"
fi
api_call POST "/api/req/deploy-orders/${DEP}/submit" '{}'
if [[ "$HTTP_CODE" == "409" && "$HTTP_BODY" == *"QUALITY_GATE_FAILED"* ]]; then
    fail "门禁已 PASS 但提交仍报质量门失败" "提交时应固化快照并推进状态"
else
    pass "门禁 PASS 后提交不再被质量门拦截（HTTP ${HTTP_CODE}）"
fi

section "q4 · QG-04：未关闭 P1 红线"
api_call POST /api/deliver/defects "$(jq -cn --argjson r "$REL" --argjson q "$REQ_ID" \
    '{releaseId:$r,requirementId:$q,title:"门禁红线-P1",description:"阻塞发布",severity:"P1",priority:"P1"}')"
expect "创建 P1 缺陷 201" 201
DEF="$(jget "$HTTP_BODY" '.id')"
GATE_JSON="$(q_gate_snapshot "$REL" "$DEP")"
if jq -e '.rules[]? | select(.code=="QG-04" and .status=="FAIL")' <<<"$GATE_JSON" >/dev/null; then
    pass "QG-04 因未关闭 P1 而 FAIL"
else
    fail "未关闭 P1 未触发 QG-04 FAIL" "QG-04 是硬红线，实际: $(jq -c '.rules[]? | select(.code=="QG-04")' <<<"$GATE_JSON")"
fi
# q_gate_snapshot 在子 shell 里执行 api_call，HTTP_BODY 不会回流到父 shell；
# 必须对它的回显值断言，否则读到的是上一条响应（曾误报 QG-04）。
if printf '%s' "$GATE_JSON" | jq -e '.result == "FAIL"' >/dev/null; then
    pass "failedRules/未被豁免覆盖 → 整门 FAIL"
else
    fail "未被豁免覆盖的 QG-04 未让整门 FAIL" "实际 result=$(printf '%s' "$GATE_JSON" | jq -r '.result')"
fi
api_call GET "/api/deliver/quality-gates/${REL}"
expect "门禁配置详情 200" 200
if jq -e '.rules[]? | select(.code=="QG-04" and (.required==true))' <<<"$HTTP_BODY" >/dev/null; then
    pass "QG-04 标记为 required（不可豁免）"
else
    skip "QG-04 required 字段未暴露在配置视图（q5 用豁免接口再断言）"
fi
# 关闭该缺陷，避免污染后续断言
api_call POST "/api/deliver/defects/${DEF}/actions" '{"action":"CLOSE","comment":"已修复"}'

section "q4 · QG-03：通过率 94.7% < 95"
REL2="$(q_create_release "低通过率版本-${RUN_TAG}")" || exit 1
api_call POST "/api/req/releases/${REL2}/requirements" "{\"requirementIds\":[${REQ_ID}]}"
CASES19=($(q_case_ids 19))
PLAN2="$(q_create_plan "$REL2" "低通过率计划-${RUN_TAG}" "${CASES19[@]}")" || exit 1
api_call POST "/api/deliver/test-plans/${PLAN2}/submit-review" '{}'
api_call POST "/api/deliver/test-plans/${PLAN2}/approve" '{"comment":"同意"}'
api_call POST "/api/deliver/test-plans/${PLAN2}/start" '{}'
for i in $(seq 0 17); do q_exec_case "$PLAN2" "${CASES19[$i]}" PASS; done
q_exec_case "$PLAN2" "${CASES19[18]}" FAIL
R2="$(q_active_round "$PLAN2")"
[[ -n "$R2" ]] || R2="$(db_q "SELECT id FROM test_plan_round WHERE plan_id=${PLAN2} AND active=1")"
q_close_round "$PLAN2" "$R2" FAIL
api_call POST "/api/deliver/test-plans/${PLAN2}/complete" '{}'
RPT2="$(q_generate_release_report "$REL2")"
DEP2="$(q_create_deploy_order "$REL2" "$REQ_ID" PROD)" || exit 1
GATE_JSON="$(q_gate_snapshot "$REL2" "$DEP2")"
if jq -e '.rules[]? | select(.code=="QG-03" and .status=="FAIL")' <<<"$GATE_JSON" >/dev/null; then
    pass "通过率 94.7% 触发 QG-03 FAIL（阈值 95）"
else
    fail "通过率 94.7% 未触发 QG-03 FAIL" "STRICT 模式还要求 passed==totalPlanned"
fi
if jq -e '[.rules[]? | select(.code=="QG-03")][0] | (.actual|tostring) | test("94")' <<<"$GATE_JSON" >/dev/null; then
    pass "QG-03 actual 如实回报 94.xx"
else
    skip "QG-03 actual 字段结构不同，按 failedRules 断言即可"
fi

section "q4 · QG-02：报告过期（有效性 1 天 + 回拨签发时间）"
api_call PATCH "/api/req/releases/${REL}/quality-settings" '{"reportValidityDays":1}'
expect "设置报告有效期 1 天 200" 200
# 报告签发时间回拨 3 天：只改测试 fixture 的签发时间，模拟时间流逝（不改口径）
db_q "UPDATE test_report_version SET signed_at = DATE_SUB(NOW(3), INTERVAL 3 DAY) WHERE report_id=${RPT}" >/dev/null
GATE_JSON="$(q_gate_snapshot "$REL" "$DEP")"
if jq -e '.rules[]? | select(.code=="QG-02" and .status=="FAIL")' <<<"$GATE_JSON" >/dev/null; then
    pass "过期报告触发 QG-02 FAIL"
else
    fail "报告过期未触发 QG-02 FAIL" "有效期按 release.report_validity_days 计算，超期必须 FAIL"
fi
db_q "UPDATE test_report_version SET signed_at = NOW(3) WHERE report_id=${RPT}" >/dev/null
api_call PATCH "/api/req/releases/${REL}/quality-settings" '{"reportValidityDays":7}'

section "q4 · 绕过网关直连 req-service 同样拦截 + 强制放行退役"
api_direct POST "$REQ_URL" "/deploy-orders/${DEP2}/submit" '{}' \
    -H "Authorization: Bearer ${API_TOKEN}" -H "X-Tenant-Id: ${API_TENANT}"
deny "直连 8082 提交同样 409（服务端重算，不信任网关）" 409 QUALITY_GATE_FAILED
api_call POST "/api/req/deploy-orders/${DEP}/force-release" '{"reason":"紧急上线"}'
deny "强制放行已退役 → 409 FORCE_RELEASE_RETIRED" 409 FORCE_RELEASE_RETIRED
api_call POST "/api/req/deploy-orders/${DEP2}/force-release" '{"reason":"绕过质量门"}'
deny "强制放行无法绕过质量门 → 409 FORCE_RELEASE_RETIRED" 409 FORCE_RELEASE_RETIRED

section "q4 · 超时 fail-closed（GATE_UNAVAILABLE 结构可断言）"
if grep -q 'GATE_UNAVAILABLE' req-service/src/main/java/com/example/req/deploy/AdmissionEvaluator.java 2>/dev/null \
    || grep -rq 'GATE_UNAVAILABLE' req-service/src/main/java/com/example/req/deploy/ 2>/dev/null; then
    pass "AdmissionEvaluator 存在 GATE_UNAVAILABLE（fail-closed）分支"
else
    fail "准入没有 GATE_UNAVAILABLE（fail-closed）分支" "门禁不可用时不得视为通过"
fi

section "q4 · Q-01：空需求集 QG-01 覆盖子项 N/A（不伪造 100%，不掩盖报告 FAIL）"
REL_EMPTY="$(q_create_release "空需求版本-${RUN_TAG}")" || exit 1
EMPTY_GATE="$(q_gate_snapshot "$REL_EMPTY" 0)"
QG01_ACTUAL="$(jq -r '.rules[]? | select(.code=="QG-01") | .actual' <<<"$EMPTY_GATE" 2>/dev/null)"
if [[ "$QG01_ACTUAL" == *"N/A（无需求可覆盖）"* ]]; then
    pass "空需求集 QG-01 actual 说明覆盖 N/A（${QG01_ACTUAL}）"
else
    fail "空需求集 QG-01 未出 N/A（actual=${QG01_ACTUAL:-<空>}）" "totalRequirements=0 时覆盖子项应为 N/A，不能报 100%"
fi
if [[ -n "$QG01_ACTUAL" && "$QG01_ACTUAL" != *"100%"* ]]; then
    pass "空需求集 QG-01 actual 不再出现 100% 假绿"
else
    fail "空需求集 QG-01 仍报 100%（actual=${QG01_ACTUAL:-<空>}）" "无需求可覆盖时不得显示 100%"
fi
if jq -e '.rules[]? | select(.code=="QG-01" and .status=="FAIL")' <<<"$EMPTY_GATE" >/dev/null; then
    pass "覆盖 N/A 不掩盖报告缺失：QG-01 仍 FAIL"
else
    fail "空需求集 QG-01 未 FAIL（实际: $(jq -c '.rules[]? | select(.code=="QG-01")' <<<"$EMPTY_GATE")）" "覆盖子项 N/A 只是不计分母，报告子项仍须独立判定"
fi
# 证据落库：页面预评估不落库，必须走内部端点固化一次（req 提交时走的也是这条路径）。
# 先 PUT 一次默认规则：quality_gate_rule 只在 configure 时插行，未配置的版本求值只算不落证据。
api_call PUT "/api/deliver/quality-gates/${REL_EMPTY}" '{}'
expect "空需求版本规则初始化 200" 200
api_call POST /api/req/deploy-orders "$(jq -cn --argjson r "$REL_EMPTY" \
    '{releaseId:$r,env:"TEST",windowStart:"2026-12-10T10:00:00+08:00",
      windowEnd:"2026-12-10T12:00:00+08:00",artifactVersion:"qa",
      steps:[{name:"发布",order:1}],rollbackPlan:"回滚到上一版",
      impact:"只读 5 分钟",ownerId:1,requirementIds:[]}')"
expect "空需求版本建单放行 201" 201
EMPTY_DEP="$(jget "$HTTP_BODY" '.id')"
if [[ -n "$EMPTY_DEP" && "$EMPTY_DEP" != "null" ]]; then
    internal_call POST "$DELIVER_URL" "/internal/quality-gates/${REL_EMPTY}/evaluate" \
        "$(jq -cn --argjson d "$EMPTY_DEP" '{deployOrderId:$d,requirementIds:[]}')"
    expect "空需求版本内部求值固化 200" 200
    GATE_ROW="$(db_q "SELECT id FROM quality_gate WHERE tenant_id=${TENANT_ID} AND release_id=${REL_EMPTY} ORDER BY id DESC LIMIT 1")"
    EV_TOTAL="$(db_q "SELECT JSON_UNQUOTE(JSON_EXTRACT(evidence_json,'\$.totalRequirements')) FROM quality_gate_rule WHERE gate_id=${GATE_ROW} AND rule_code='QG-01'")"
    EV_APPLICABLE="$(db_q "SELECT JSON_UNQUOTE(JSON_EXTRACT(evidence_json,'\$.coverageApplicable')) FROM quality_gate_rule WHERE gate_id=${GATE_ROW} AND rule_code='QG-01'")"
    EV_PERCENT="$(db_q "SELECT JSON_UNQUOTE(JSON_EXTRACT(evidence_json,'\$.coveragePercent')) FROM quality_gate_rule WHERE gate_id=${GATE_ROW} AND rule_code='QG-01'")"
    if [[ "$EV_TOTAL" == "0" ]]; then
        pass "证据落库 totalRequirements=0"
    else
        fail "证据 totalRequirements=${EV_TOTAL:-<空>}（期望 0）" "gate_id=${GATE_ROW} 的 QG-01 evidence_json 未更新"
    fi
    if [[ "$EV_APPLICABLE" == "false" ]]; then
        pass "证据落库 coverageApplicable=false"
    else
        fail "证据 coverageApplicable=${EV_APPLICABLE:-<空>}（期望 false）" "空集必须显式标记为不适用"
    fi
    if [[ "$EV_PERCENT" == "null" ]]; then
        pass "证据落库 coveragePercent 为 JSON null（不写 100）"
    else
        fail "证据 coveragePercent=${EV_PERCENT:-<空>}（期望 JSON null）" "空集不得写入任何百分比数值"
    fi
else
    fail "空需求版本建单未返回 id，证据落库断言无法执行" "检查 POST /deploy-orders 的响应体"
fi

guard q4-gate-admission
