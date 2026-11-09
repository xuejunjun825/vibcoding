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

# q1_order_body <releaseId> <requirementId> — 与 q_create_deploy_order 同形，
# 负向断言要复用同一份请求体，否则「被拒」可能只是因为字段写错。
q1_order_body() {
    jq -cn --argjson r "$1" --argjson q "$2" \
        '{releaseId:$r,env:"PROD",windowStart:"2026-12-10T10:00:00+08:00",
          windowEnd:"2026-12-10T12:00:00+08:00",artifactVersion:"qa",
          steps:[{name:"发布",order:1}],rollbackPlan:"回滚到上一版",
          impact:"只读 5 分钟",ownerId:1,requirementIds:[$q]}'
}

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

section "q1 · R1：发布单引用未批准计划 → 409 PLAN_NOT_APPROVED（不落单、不写审计）"
# 覆盖关系走「计划用例 → 用例所属需求」，所以 fixture 必须挑一条带了 requirement_id 的用例：
# 用例没挂需求时计划覆盖集恒为空，闸门根本不会触发（这正是本条链路最容易假绿的地方）。
read -r PLAN_CASE REQ0 <<<"$(db_q "SELECT c.id, c.requirement_id FROM test_case c
    JOIN requirement r ON r.id = c.requirement_id AND r.tenant_id = c.tenant_id
    WHERE c.tenant_id=${TENANT_ID} AND c.requirement_id IS NOT NULL
    ORDER BY c.id DESC LIMIT 1")"
if [[ -z "${PLAN_CASE:-}" || -z "${REQ0:-}" ]]; then
    fail "缺少「用例已挂需求」的 fixture，无法验证发布单计划闸门" "make platform-seed 后重跑"
else
    DRAFT_PLAN="$(q_create_plan "$REL" "未批准计划-${RUN_TAG}" "$PLAN_CASE")" || exit 1
    info "draftPlanId=${DRAFT_PLAN} requirementId=${REQ0} caseId=${PLAN_CASE}"
    ORDERS_BEFORE="$(db_count deploy_order)"
    ORDER_REQS_BEFORE="$(db_count deploy_order_requirement)"
    AUDIT_BEFORE="$(db_count audit_log "action='CREATE' AND biz_type='deploy_order'")"

    api_call POST /api/req/deploy-orders "$(q1_order_body "$REL" "$REQ0")"
    deny "DRAFT 计划覆盖需求 → 409 PLAN_NOT_APPROVED" 409 PLAN_NOT_APPROVED
    expect_jq "409 detail.planIds 命中未批准计划" ".detail.planIds | index(${DRAFT_PLAN}) != null" 'true'
    same_count "被拒建单不新增发布单" deploy_order "" "$ORDERS_BEFORE"
    same_count "被拒建单不写需求关联" deploy_order_requirement "" "$ORDER_REQS_BEFORE"
    same_count "被拒建单不写 CREATE 审计" audit_log "action='CREATE' AND biz_type='deploy_order'" "$AUDIT_BEFORE"

    api_call POST "/api/deliver/test-plans/${DRAFT_PLAN}/submit-review" '{}'
    expect "待批计划提交评审 200" 200
    api_call POST /api/req/deploy-orders "$(q1_order_body "$REL" "$REQ0")"
    deny "PENDING_REVIEW 计划覆盖需求 → 409 PLAN_NOT_APPROVED" 409 PLAN_NOT_APPROVED
    expect_jq "待批计划同样出现在 detail.planIds" ".detail.planIds | index(${DRAFT_PLAN}) != null" 'true'

    api_call POST "/api/deliver/test-plans/${DRAFT_PLAN}/approve" '{"comment":"同意"}'
    expect "计划批准 200" 200
    api_call POST /api/req/deploy-orders "$(q1_order_body "$REL" "$REQ0")"
    expect "计划批准后建单放行 201" 201
    APPROVED_ORDER="$(jget "$HTTP_BODY" '.id')"
    if [[ -n "$APPROVED_ORDER" && "$APPROVED_ORDER" != "null" ]]; then
        api_call POST "/api/req/deploy-orders/${APPROVED_ORDER}/evaluate" '{}'
        expect "新单准入评估 200" 200
        expect_jq "六项快照不被误标为历史单据" '.legacy' 'false'
    else
        fail "批准后建单未返回 id，无法继续验证准入快照" "检查 POST /deploy-orders 的响应体"
    fi
fi

section "q1 · R1/AC-3：无覆盖计划的版本照常放行"
api_call POST /api/req/requirements "$(jq -cn --arg n "无计划需求-${RUN_TAG}" --argjson d "$(q_domain)" \
    '{type:"BUS",title:$n,description:"AC-3：版本内没有任何测试计划",domainIds:[$d],priority:"P2"}')"
expect "造一条未挂版本的需求 201" 201
NEW_REQ="$(jget "$HTTP_BODY" '.id')"
if [[ -n "$NEW_REQ" && "$NEW_REQ" != "null" ]]; then
    REL_NOPLAN="$(q_create_release "无计划版本-${RUN_TAG}")" || exit 1
    api_call POST "/api/req/releases/${REL_NOPLAN}/requirements" "{\"requirementIds\":[${NEW_REQ}]}"
    expect "无计划版本挂需求 200" 200
    api_call POST /api/req/deploy-orders "$(q1_order_body "$REL_NOPLAN" "$NEW_REQ")"
    expect "无覆盖计划 → 建单放行 201" 201
else
    fail "造需求未返回 id，AC-3 放行断言无法执行" "检查 POST /requirements 的响应体"
fi

guard q1-plan-lifecycle
