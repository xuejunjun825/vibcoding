#!/usr/bin/env bash
# q2-round-exit.sh — AC-2 多轮次执行 + AC-3 结构化准出。
#
# 覆盖：同计划只能有一个进行中轮次（并发也只有一个赢）、未执行不允许关闭轮次、
# 结论只能下调（PASS 优于数据建议 FAIL 时 409）、历史轮次结果不随新轮次执行而改变、
# PREVIOUS_FAILED 作用域只含上一轮失败用例、准出未达 409 且列出 unmet（key/actual/expected/fixHint）。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

require_stack

section "q2 · 准备 fixture（5 条用例的 IN_PROGRESS 计划）"
REL="$(q_create_release "多轮次版本-${RUN_TAG}")" || exit 1
CASES=($(q_case_ids 5))
(( ${#CASES[@]} == 5 )) || { fail "种子用例不足 5 条（当前 ${#CASES[@]}）" "make platform-seed"; guard q2-round-exit; }
PLAN="$(q_create_plan "$REL" "多轮次计划-${RUN_TAG}" "${CASES[@]}")" || exit 1
info "releaseId=${REL} planId=${PLAN}"
api_call POST "/api/deliver/test-plans/${PLAN}/submit-review" '{}'
api_call POST "/api/deliver/test-plans/${PLAN}/approve" '{"comment":"同意"}'
api_call POST "/api/deliver/test-plans/${PLAN}/start" '{}'
expect "计划开始执行 200" 200
R1="$(q_active_round "$PLAN")"
[[ -n "$R1" ]] || R1="$(db_q "SELECT id FROM test_plan_round WHERE plan_id=${PLAN} AND active=1")"
expect_db "自动开 R1（active=1）" "SELECT COUNT(*) FROM test_plan_round WHERE plan_id=${PLAN} AND active=1" eq 1
info "R1=${R1}"

section "q2 · 进行中轮次唯一 + 未执行不允许关闭"
ROUNDS_BEFORE="$(db_count test_plan_round "plan_id=${PLAN}")"
api_call POST "/api/deliver/test-plans/${PLAN}/rounds" '{"scopeMode":"CURRENT"}'
deny "已有进行中轮次时再开 → 409 ROUND_IN_PROGRESS" 409 ROUND_IN_PROGRESS
same_count "被拒的开轮次不落库" test_plan_round "plan_id=${PLAN}" "${ROUNDS_BEFORE}"

api_call POST "/api/deliver/test-plans/${PLAN}/rounds/${R1}/close" '{"conclusion":"PASS"}'
deny "有用例未执行时关闭轮次 → 409 ROUND_HAS_UNEXECUTED" 409 ROUND_HAS_UNEXECUTED
expect_jq "409 detail 列出未执行用例" '.detail.unexecutedCaseIds | length' '5'

section "q2 · 执行 R1：1 PASS / 4 FAIL → 结论只能下调"
q_exec_case "$PLAN" "${CASES[0]}" PASS
for i in 1 2 3 4; do q_exec_case "$PLAN" "${CASES[$i]}" FAIL; done
expect_db "R1 内 4 条 FAIL 已落库" "SELECT COUNT(*) FROM test_execution WHERE plan_id=${PLAN} AND round_id=${R1} AND result='FAIL'" eq 4
api_call POST "/api/deliver/test-plans/${PLAN}/rounds/${R1}/close" '{"conclusion":"PASS"}'
deny "数据建议 FAIL 时不能报 PASS → 409 CONCLUSION_UPGRADE_FORBIDDEN" 409 CONCLUSION_UPGRADE_FORBIDDEN
api_call POST "/api/deliver/test-plans/${PLAN}/rounds/${R1}/close" '{"conclusion":"FAIL","note":"4 条失败"}'
expect "按数据结论关闭 R1 200" 200
expect_jq "R1 结论 FAIL" '.conclusion // .round.conclusion' 'FAIL'
expect_db "R1 已关闭且释放 active" "SELECT COUNT(*) FROM test_plan_round WHERE id=${R1} AND status='CLOSED' AND active IS NULL" eq 1

section "q2 · 并发开轮次：唯一键兜底，只允许一个赢"
TMPD="$(mktemp -d -t q2-rounds.XXXXXX)"
for n in 1 2; do
    (
        code="$(curl -sS -o "$TMPD/body$n.json" -w '%{http_code}' --max-time 25 \
            -X POST "${GATEWAY_URL}/api/deliver/test-plans/${PLAN}/rounds" \
            -H 'Content-Type: application/json' -H "Authorization: Bearer ${API_TOKEN}" \
            -H "X-Tenant-Id: ${API_TENANT}" -d '{"scopeMode":"PREVIOUS_FAILED"}')"
        printf '%s' "$code" >"$TMPD/code$n"
    ) &
done
wait
CODE1="$(cat "$TMPD/code1" 2>/dev/null)"; CODE2="$(cat "$TMPD/code2" 2>/dev/null)"
WINNERS=0; CONFLICTS=0
for c in "$CODE1" "$CODE2"; do
    [[ "$c" == "200" || "$c" == "201" ]] && WINNERS=$((WINNERS + 1))
    [[ "$c" == "409" ]] && CONFLICTS=$((CONFLICTS + 1))
done
if (( WINNERS == 1 && CONFLICTS == 1 )); then
    pass "并发开轮次恰好一个成功（200/201）、一个 409（${CODE1}/${CODE2}）"
else
    fail "并发开轮次结果异常（${CODE1}/${CODE2}，期望一个 200/201 一个 409）" "同计划仅一轮进行中必须由 UNIQUE(plan_id,active) 兜底，冲突转 409 而不是 500"
fi
rm -rf "$TMPD"
ROUNDS_NOW="$(db_count test_plan_round "plan_id=${PLAN}")"
expect_num "并发后只多出一个轮次" "$(( ROUNDS_NOW - ROUNDS_BEFORE ))" eq 1
R2="$(db_q "SELECT id FROM test_plan_round WHERE plan_id=${PLAN} AND round_no=2")"
[[ -n "$R2" ]] || { fail "R2 未创建，后续断言无法继续" "查看 deliver-service 日志"; guard q2-round-exit; }
info "R2=${R2}"

section "q2 · PREVIOUS_FAILED 作用域 = 上一轮失败用例"
api_call GET "/api/deliver/test-plans/${PLAN}/rounds/${R2}"
expect "R2 详情 200" 200
expect_jq "R2 只含上一轮失败的 4 条" '.cases | length' '4'
expect_jq "R2 不含上一轮已 PASS 的用例" "[.cases[].caseId] | index(${CASES[0]}) | not" 'true'
expect_jq "R2 作用域全部来自 R1 失败集" "[.cases[].caseId] | sort | join(\",\")" "$(printf '%s\n' "${CASES[1]}" "${CASES[2]}" "${CASES[3]}" "${CASES[4]}" | sort -n | paste -sd, -)"

section "q2 · R2 重跑通过：历史轮次结果不变，计划最新结果更新"
for i in 1 2 3 4; do q_exec_case "$PLAN" "${CASES[$i]}" PASS; done
q_close_round "$PLAN" "$R2" PASS
expect "关闭 R2 200" 200
api_call GET "/api/deliver/test-plans/${PLAN}/rounds/${R1}"
expect "回看 R1 200" 200
expect_jq "R1 详情仍显示 4 条 FAIL（历史不变）" '[.cases[] | select(.result=="FAIL")] | length' '4'
expect_db "R1 汇总快照未被 R2 覆盖" "SELECT exec_failed FROM test_plan_round WHERE id=${R1}" eq 4
expect_db "plan_case 累计结果已更新为全 PASS" "SELECT COUNT(*) FROM test_plan_case WHERE plan_id=${PLAN} AND result='PASS'" eq 5

section "q2 · 准出达标 → 计划可完成"
api_call POST "/api/deliver/test-plans/${PLAN}/complete" '{}'
expect "两轮关闭且通过率 100% → 完成 200" 200
expect_jq "计划 COMPLETED" '.status' 'COMPLETED'
expect_db "完成时间已落库（closed_at 为空是 close 的语义）" "SELECT COUNT(*) FROM test_plan WHERE id=${PLAN} AND status='COMPLETED'" eq 1

section "q2 · AC-3 准出未达 → 409 且列出 unmet"
PLAN2="$(q_create_plan "$REL" "准出拦截计划-${RUN_TAG}" "${CASES[0]}" "${CASES[1]}")" || exit 1
api_call POST "/api/deliver/test-plans/${PLAN2}/submit-review" '{}'
api_call POST "/api/deliver/test-plans/${PLAN2}/approve" '{"comment":"同意"}'
api_call PATCH "/api/deliver/test-plans/${PLAN2}" \
    '{"exitCriteria":{"items":[{"key":"passRate","op":">=","value":100},{"key":"notRun","op":"==","value":0}]},"reason":"本版本要求零失败"}'
expect "自定义准出条件 200" 200
expect_jq "准出条件已保存" '.criteria.exitCriteriaJson' '{"items":[{"key":"passRate","op":">=","value":100},{"key":"notRun","op":"==","value":0}]}'
api_call POST "/api/deliver/test-plans/${PLAN2}/start" '{}'
R3="$(q_active_round "$PLAN2")"
[[ -n "$R3" ]] || R3="$(db_q "SELECT id FROM test_plan_round WHERE plan_id=${PLAN2} AND active=1")"
q_exec_case "$PLAN2" "${CASES[0]}" PASS
q_exec_case "$PLAN2" "${CASES[1]}" FAIL
q_close_round "$PLAN2" "$R3" FAIL
api_call POST "/api/deliver/test-plans/${PLAN2}/complete" '{}'
deny "准出未达 → 409 PLAN_EXIT_CRITERIA_UNMET" 409 PLAN_EXIT_CRITERIA_UNMET
expect_jq "unmet 列出 1 项" '.detail.unmet | length' '1'
expect_jq "unmet 项是 passRate" '.detail.unmet[0].key' 'passRate'
expect_jq "unmet 带实际值 50" '(.detail.unmet[0].actual | tostring) | test("^50")' 'true'
expect_jq "unmet 带期望值" '(.detail.unmet[0].expected | tostring) | test("100")' 'true'
expect_jq "unmet 带修复建议" '.detail.unmet[0].fixHint | length > 0' 'true'
PLAN2_STATE="$(db_q "SELECT status FROM test_plan WHERE id=${PLAN2}")"
if [[ "$PLAN2_STATE" == "IN_PROGRESS" ]]; then pass "计划仍停在 IN_PROGRESS"; else fail "计划状态为 ${PLAN2_STATE}（准出失败后不得推进）"; fi
expect_db "未达准出时没有写入 COMPLETED" "SELECT COUNT(*) FROM test_plan WHERE id=${PLAN2} AND status='IN_PROGRESS'" eq 1

section "q2 · 补齐后可以完成（同一条件不再是拦截）"
# 执行必须落在「进行中的轮次」上：R3 已关闭，先开 R4（PREVIOUS_FAILED）再修失败用例。
api_call POST "/api/deliver/test-plans/${PLAN2}/rounds" '{"scopeMode":"PREVIOUS_FAILED","name":"R4 修复轮"}'
if [[ "$HTTP_CODE" == "200" || "$HTTP_CODE" == "201" ]]; then pass "开修复轮次（HTTP ${HTTP_CODE}）"; else fail "开修复轮次失败（HTTP ${HTTP_CODE}: $(printf '%s' "$HTTP_BODY" | head -c 160)）" "轮次关闭后必须能针对失败用例再开一轮"; fi
R4="$(jget "$HTTP_BODY" '.id')"
q_exec_case "$PLAN2" "${CASES[1]}" PASS
api_call POST "/api/deliver/test-plans/${PLAN2}/rounds/${R4:-0}/close" '{"conclusion":"PASS"}'
expect "关闭 R4 200" 200
api_call POST "/api/deliver/test-plans/${PLAN2}/complete" '{}'
expect "失败用例修复后完成 200" 200
expect_jq "PLAN2 COMPLETED" '.status' 'COMPLETED'

guard q2-round-exit
