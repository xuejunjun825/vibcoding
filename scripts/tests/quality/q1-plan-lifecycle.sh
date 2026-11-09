#!/usr/bin/env bash
# q1-plan-lifecycle.sh — AC-1 测试计划生命周期与变更留痕。
#
# 覆盖：七态正向迁移（DRAFT→PENDING_REVIEW→APPROVED→IN_PROGRESS→COMPLETED→CLOSED→ARCHIVED）、
# 驳回回退 DRAFT、非法动作 409 且 detail 带 allowedNext、已评审计划的变更必须填 reason、
# revision 单调递增、plan_change_log 一行一条变更、关闭/归档后的写操作被拒。
#
# 前置：deliver-service 可用（计划域在 deliver），一个可用的版本 fixture 由本脚本自造。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

require_stack

section "q1 · 准备 fixture（版本 + 3 条用例的 DRAFT 计划）"
REL="$(q_create_release "质量计划版本-${RUN_TAG}")" || exit 1
info "releaseId=${REL}"
CASES=($(q_case_ids 3))
if (( ${#CASES[@]} < 3 )); then
    fail "种子用例不足 3 条（当前 ${#CASES[@]}）" "make platform-seed"
    guard q1-plan-lifecycle
fi
PLAN="$(q_create_plan "$REL" "生命周期计划-${RUN_TAG}" "${CASES[@]}")" || exit 1
info "planId=${PLAN}"

api_call GET "/api/deliver/test-plans/${PLAN}/lifecycle"
expect "新建计划可读 200" 200
expect_jq "新计划状态 DRAFT" '.status' 'DRAFT'
expect_jq "新计划 revision=0" '.revision' '0'
expect_jq "DRAFT 唯一合法动作是 submit-review" '.allowedNext | join(",")' 'submit-review'

section "q1 · DRAFT → PENDING_REVIEW → DRAFT（驳回回退）"
api_call POST "/api/deliver/test-plans/${PLAN}/submit-review" '{}'
expect "提交评审 200" 200
expect_jq "状态 PENDING_REVIEW" '.status' 'PENDING_REVIEW'
expect_jq "待评审允许 approve/reject" '.allowedNext | join(",")' 'approve,reject'

LOGS_BEFORE="$(db_count plan_change_log "plan_id=${PLAN}")"
api_call POST "/api/deliver/test-plans/${PLAN}/reject" '{}'
deny "驳回不填意见 → 400 CHANGE_REASON_REQUIRED" 400 CHANGE_REASON_REQUIRED
same_count "驳回失败不留变更日志" plan_change_log "plan_id=${PLAN}" "$LOGS_BEFORE"

api_call POST "/api/deliver/test-plans/${PLAN}/reject" '{"comment":"用例范围需要补充"}'
expect "驳回（带意见）200" 200
expect_jq "驳回回到 DRAFT" '.status' 'DRAFT'
REJECT_NOTE="$(db_q "SELECT review_comment FROM test_plan WHERE id=${PLAN}")"
if [[ "$REJECT_NOTE" == "用例范围需要补充" ]]; then pass "驳回意见落库"; else fail "驳回意见未落库（实际: ${REJECT_NOTE:-<空>}）"; fi

section "q1 · 再次提交并通过评审"
api_call POST "/api/deliver/test-plans/${PLAN}/submit-review" '{}'
expect "再次提交评审 200" 200
api_call POST "/api/deliver/test-plans/${PLAN}/approve" '{"comment":"同意"}'
expect "批准 200" 200
expect_jq "状态 APPROVED" '.status' 'APPROVED'
expect_jq "APPROVED 只能 start" '.allowedNext | join(",")' 'start'

section "q1 · 非法迁移 409 + allowedNext（前端 stepper 同源）"
api_call POST "/api/deliver/test-plans/${PLAN}/archive" '{}'
deny "APPROVED 直接归档 → 409 ILLEGAL_PLAN_TRANSITION" 409 ILLEGAL_PLAN_TRANSITION
expect_jq "409 detail 回报 from" '.detail.from' 'APPROVED'
expect_jq "409 detail 回报 allowedNext" '.detail.allowedNext | join(",")' 'start'
api_call GET "/api/deliver/test-plans/${PLAN}/allowed-actions"
expect "allowed-actions 200" 200
expect_jq "allowed-actions 与 detail 同源" '.allowedNext | join(",")' 'start'

section "q1 · 已评审计划的变更：reason 必填 + revision 递增 + 留痕"
api_call PATCH "/api/deliver/test-plans/${PLAN}" '{"windowEnd":"2026-11-28"}'
deny "无 reason 的变更 → 400 CHANGE_REASON_REQUIRED" 400 CHANGE_REASON_REQUIRED
api_call PATCH "/api/deliver/test-plans/${PLAN}" '{"windowEnd":"2026-11-28","reason":"甲方要求延长一天"}'
expect "带 reason 的变更 200" 200
expect_jq "revision 递增到 1" '.revision' '1'
expect_jq "窗口已更新" '(.windowEnd | tostring) | test("^2026-11-28")' 'true'
api_call GET "/api/deliver/test-plans/${PLAN}/change-logs"
expect "变更日志 200" 200
# 列表接口统一返回 {items:[...]}（与其它分页/集合接口同形），不要假设裸数组
expect_jq "变更日志一行" '(.items // .) | length' '1'
expect_jq "变更原因留痕" '(.items // .)[0].reason' '甲方要求延长一天'
expect_jq "变更类型 UPDATE" '(.items // .)[0].changeType' 'UPDATE'
expect_db "DB revision=1" "SELECT revision FROM test_plan WHERE id=${PLAN}" eq 1

section "q1 · 开始执行 → 自动 R1 轮次"
api_call POST "/api/deliver/test-plans/${PLAN}/start" '{}'
expect "开始执行 200" 200
expect_jq "状态 IN_PROGRESS" '.status' 'IN_PROGRESS'
expect_jq "IN_PROGRESS 只能 complete" '.allowedNext | join(",")' 'complete'
ROUND="$(q_active_round "$PLAN")"
expect_db "自动开一个进行中轮次" "SELECT COUNT(*) FROM test_plan_round WHERE plan_id=${PLAN} AND active=1" eq 1
[[ -n "$ROUND" ]] || ROUND="$(db_q "SELECT id FROM test_plan_round WHERE plan_id=${PLAN} AND active=1")"
info "roundId=${ROUND}"

api_call POST "/api/deliver/test-plans/${PLAN}/complete" '{}'
deny "轮次未关闭时完成计划 → 409 ROUND_IN_PROGRESS" 409 ROUND_IN_PROGRESS

section "q1 · 执行全部用例 → 关闭轮次 → 完成 → 关闭 → 归档"
q_exec_all "$PLAN" PASS
expect_db "3 条用例全部 PASS" "SELECT COUNT(*) FROM test_plan_case WHERE plan_id=${PLAN} AND result='PASS'" eq 3
q_close_round "$PLAN" "$ROUND" PASS
expect "关闭轮次 200" 200

api_call POST "/api/deliver/test-plans/${PLAN}/complete" '{}'
expect "准出达标后可完成 200" 200
expect_jq "状态 COMPLETED" '.status' 'COMPLETED'
expect_jq "COMPLETED 只能 close" '.allowedNext | join(",")' 'close'
api_call POST "/api/deliver/test-plans/${PLAN}/close" '{"reason":"验收结束"}'
expect "关闭计划 200" 200
expect_jq "状态 CLOSED" '.status' 'CLOSED'
expect_jq "CLOSED 只能 archive" '.allowedNext | join(",")' 'archive'

api_call PATCH "/api/deliver/test-plans/${PLAN}" '{"windowEnd":"2026-11-29","reason":"晚了一步"}'
deny "CLOSED 计划不允许变更 → 409 ILLEGAL_PLAN_TRANSITION" 409 ILLEGAL_PLAN_TRANSITION
api_call POST "/api/deliver/test-plans/${PLAN}/submit-review" '{}'
deny "CLOSED 计划不允许回退评审 → 409" 409 ILLEGAL_PLAN_TRANSITION

api_call POST "/api/deliver/test-plans/${PLAN}/archive" '{}'
expect "归档 200" 200
expect_jq "状态 ARCHIVED" '.status' 'ARCHIVED'
expect_jq "ARCHIVED 无后续动作" '.allowedNext | length' '0'
api_call POST "/api/deliver/test-plans/${PLAN}/archive" '{}'
deny "重复归档 → 409" 409 ILLEGAL_PLAN_TRANSITION

section "q1 · 留痕总量（一次变更 = 一行，不允许静默多写）"
expect_db "plan_change_log 恰好 1 行" "SELECT COUNT(*) FROM plan_change_log WHERE plan_id=${PLAN}" eq 1
expect_db "revision 与变更行数一致" "SELECT revision FROM test_plan WHERE id=${PLAN}" eq 1
ARCHIVED_STATE="$(db_q "SELECT status FROM test_plan WHERE id=${PLAN}")"
if [[ "$ARCHIVED_STATE" == "ARCHIVED" ]]; then pass "DB 中计划状态 ARCHIVED"; else fail "DB 中计划状态为 ${ARCHIVED_STATE}（期望 ARCHIVED）"; fi

guard q1-plan-lifecycle
