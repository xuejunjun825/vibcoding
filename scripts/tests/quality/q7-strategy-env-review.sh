#!/usr/bin/env bash
# q7-strategy-env-review.sh — AC-9 策略/分配/环境 + AC-10 Go/No-Go 评审。
#
# 覆盖：策略版本化（ACTIVE 不可改、新版本 DRAFT、激活后旧版 RETIRED、RETIRED 不可再激活）、
# 执行分配合计必须等于计划用例数（ASSIGNMENT_MISMATCH）、未提交评审不可分配、
# 环境同域重名与被未结束计划引用均 409 ENV_IN_USE、评审 GO/NO_GO/CONDITIONAL、
# CONDITIONAL 缺条件 400、NO-GO 后提交需 PMO 二次确认（NO_GO_CONFIRM_REQUIRED）。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

require_stack

section "q7 · 测试策略版本化（AC-9.1）"
REL="$(q_create_release "策略版本-${RUN_TAG}")" || exit 1
STRATEGY_BODY="$(jq -cn '{scope:{modules:["订单","库存"]},risk:{level:"中",items:[{key:"支付",weight:3}]},envIds:[],roundPlan:{rounds:2},resourceNote:"2 人 3 天",ownerId:1}')"
api_call POST "/api/deliver/test-strategies?releaseId=${REL}" "$STRATEGY_BODY"
expect "创建策略 201" 201
S1="$(jget "$HTTP_BODY" '.id')"
[[ -n "$S1" ]] || { err "策略创建失败: $(printf '%s' "$HTTP_BODY" | head -c 200)"; exit 1; }
expect_jq "首版为 v1" '.versionNo' '1'
expect_jq "首版直接 ACTIVE" '.status' 'ACTIVE'
api_call PUT "/api/deliver/test-strategies/${S1}" "$STRATEGY_BODY"
deny "修改生效中的策略 → 409 ILLEGAL_PLAN_TRANSITION" 409 ILLEGAL_PLAN_TRANSITION
api_call POST "/api/deliver/test-strategies?releaseId=${REL}" "$STRATEGY_BODY"
expect "创建第二版 201" 201
S2="$(jget "$HTTP_BODY" '.id')"
expect_jq "第二版为 v2" '.versionNo' '2'
expect_jq "第二版是 DRAFT" '.status' 'DRAFT'
api_call PUT "/api/deliver/test-strategies/${S2}" "$(jq -cn '{scope:{modules:["订单"]},risk:{level:"低"},envIds:[],roundPlan:{rounds:1},resourceNote:"1 人 2 天",ownerId:1}')"
expect "DRAFT 可修改 200" 200
api_call POST "/api/deliver/test-strategies/${S2}/activate"
expect "激活 v2 200" 200
expect_jq "v2 变 ACTIVE" '.status' 'ACTIVE'
expect_jq "v1 变 RETIRED" '.retiredVersion // empty' ''
V1_STATUS="$(db_q "SELECT status FROM test_strategy WHERE id=${S1}")"
if [[ "$V1_STATUS" == "RETIRED" ]]; then pass "旧版本落库 RETIRED"; else fail "旧版本状态 ${V1_STATUS}（期望 RETIRED）" "同 Release 只能一版 ACTIVE"; fi
api_call POST "/api/deliver/test-strategies/${S1}/activate"
deny "RETIRED 不可再激活 → 409" 409 ILLEGAL_PLAN_TRANSITION
api_call PUT "/api/deliver/test-strategies/${S1}" "$STRATEGY_BODY"
deny "RETIRED 不可修改 → 409" 409 ILLEGAL_PLAN_TRANSITION
assert_single_active="$(db_q "SELECT COUNT(*) FROM test_strategy WHERE release_id=${REL} AND status='ACTIVE'")"
if [[ "$assert_single_active" == "1" ]]; then pass "同 Release 恰好 1 版 ACTIVE"; else fail "ACTIVE 版本数 ${assert_single_active}" "唯一键 uk_strategy 之外的业务约束也须保证"; fi

section "q7 · 执行分配：未提交评审不可分配、合计必须等于用例数（AC-9.2）"
CASES=($(q_case_ids 4))
PLAN="$(q_create_plan "$REL" "分配计划-${RUN_TAG}" "${CASES[@]}")" || exit 1
api_call PUT "/api/deliver/test-plans/${PLAN}/assignments" \
    "$(jq -cn --argjson e1 "$(q_user_id qa1)" --argjson e2 "$(q_user_id qa2)" '{rows:[{executorId:$e1,plannedCount:2,dueDate:"2026-11-25"},{executorId:$e2,plannedCount:2,dueDate:"2026-11-25"}]}')"
deny "DRAFT 计划直接分配 → 409 ILLEGAL_PLAN_TRANSITION" 409 ILLEGAL_PLAN_TRANSITION
api_call POST "/api/deliver/test-plans/${PLAN}/submit-review" '{}'
api_call POST "/api/deliver/test-plans/${PLAN}/approve" '{"comment":"同意"}'
api_call PUT "/api/deliver/test-plans/${PLAN}/assignments" \
    "$(jq -cn --argjson e1 "$(q_user_id qa1)" '{rows:[{executorId:$e1,plannedCount:3,dueDate:"2026-11-25"}]}')"
deny "分配合计 3 ≠ 用例数 4 → 400 ASSIGNMENT_MISMATCH" 400 ASSIGNMENT_MISMATCH
api_call PUT "/api/deliver/test-plans/${PLAN}/assignments" \
    "$(jq -cn --argjson e1 "$(q_user_id qa1)" --argjson e2 "$(q_user_id qa2)" '{rows:[{executorId:$e1,plannedCount:2,dueDate:"2026-11-25"},{executorId:$e2,plannedCount:2,dueDate:"2026-11-26"}]}')"
expect "分配合计等于用例数 → 200" 200
api_call GET "/api/deliver/test-plans/${PLAN}/assignments"
expect "分配列表 200" 200
expect_num "2 条分配" "$(jq -r 'if type=="array" then length else (.rows|length) end' <<<"$HTTP_BODY")" eq 2
expect_db "分配合计=计划用例数" "SELECT COALESCE(SUM(planned_count),0) FROM plan_assignment WHERE plan_id=${PLAN}" eq 4

section "q7 · 测试环境：重名与占用（AC-9.3）"
ENV_NAME="QA环境-${RUN_TAG}"
api_call POST /api/deliver/test-envs "$(jq -cn --arg n "$ENV_NAME" '{name:$n,envType:"TEST",baseUrl:"http://qa.local",ownerId:1,status:"AVAILABLE"}')"
expect "创建环境 201" 201
ENV_ID="$(jget "$HTTP_BODY" '.id')"
api_call PUT "/api/deliver/test-envs/${ENV_ID}" "$(jq -cn --arg n "$ENV_NAME" '{name:$n,envType:"TEST",baseUrl:"http://qa.local",ownerId:1,status:"MAINTENANCE"}')"
expect "控制台维护环境 200（PRD 状态字典）" 200
expect_jq "维护状态回读 MAINTENANCE" '.status' 'MAINTENANCE'
api_call POST /api/deliver/test-envs "$(jq -cn --arg n "生产环境-${RUN_TAG}" '{name:$n,envType:"PROD",baseUrl:"http://prod.local",ownerId:1,status:"AVAILABLE"}')"
expect "PROD 环境可创建 201（PRD 类型字典）" 201
ENV_PROD="$(jget "$HTTP_BODY" '.id')"
api_call DELETE "/api/deliver/test-envs/${ENV_PROD}"
expect "删除未占用 PROD 环境 204" 204
api_call POST /api/deliver/test-envs "$(jq -cn --arg n "$ENV_NAME" '{name:$n,envType:"TEST",baseUrl:"http://qa2.local",ownerId:1,status:"AVAILABLE"}')"
deny "同域重名 → 409 ENV_IN_USE" 409 ENV_IN_USE
api_call PATCH "/api/deliver/test-plans/${PLAN}" "$(jq -cn --argjson e "$ENV_ID" '{envId:$e,reason:"绑定测试环境"}')"
expect "计划绑定环境 200" 200
api_call DELETE "/api/deliver/test-envs/${ENV_ID}"
deny "环境被未结束计划引用 → 409 ENV_IN_USE" 409 ENV_IN_USE
api_call POST /api/deliver/test-envs "$(jq -cn --arg n "空闲环境-${RUN_TAG}" '{name:$n,envType:"TEST",baseUrl:"http://idle.local",ownerId:1,status:"AVAILABLE"}')"
ENV_IDLE="$(jget "$HTTP_BODY" '.id')"
api_call DELETE "/api/deliver/test-envs/${ENV_IDLE}"
expect "删除空闲环境 204" 204

section "q7 · Go/No-Go 评审（AC-10）"
api_call POST "/api/req/releases/${REL}/reviews" "$(jq -cn '{meetingAt:"2026-12-01T10:00:00+08:00",participants:[1],decision:"CONDITIONAL"}')"
deny "CONDITIONAL 缺条件 → 400 WAIVER_FIELDS_REQUIRED(conditions)" 400 "conditions"
api_call POST "/api/req/releases/${REL}/reviews" "$(jq -cn '{meetingAt:"2026-12-01T10:00:00+08:00",participants:[1],decision:"GO",risks:"无",openDefects:0}')"
expect "记录 GO 评审 201" 201
REVIEW_GO="$(jget "$HTTP_BODY" '.id')"
expect_jq "GO 评审状态 RECORDED" '.status' 'RECORDED'
expect_jq "GO 评审 decision=GO" '.decision' 'GO'
api_call POST "/api/req/releases/${REL}/reviews" "$(jq -cn '{meetingAt:"2026-12-02T10:00:00+08:00",participants:[1],decision:"NO_GO",risks:"P1 未清",openDefects:1}')"
expect "记录 NO-GO 评审 201" 201
REVIEW_NOGO="$(jget "$HTTP_BODY" '.id')"
Q_REQ_ID="$(db_q "SELECT requirement_id FROM test_case WHERE id=${CASES[0]}")"
[[ -n "$Q_REQ_ID" && "$Q_REQ_ID" != "NULL" ]] || Q_REQ_ID="$(db_q "SELECT id FROM requirement WHERE tenant_id=${TENANT_ID} ORDER BY id LIMIT 1")"
DEP="$(q_create_deploy_order "$REL" "${Q_REQ_ID}" PROD)" || exit 1
api_call POST "/api/req/deploy-orders/${DEP}/submit" '{}'
deny "最新评审 NO-GO 时提交 → 409 NO_GO_CONFIRM_REQUIRED" 409 NO_GO_CONFIRM_REQUIRED
api_call POST "/api/req/deploy-orders/${DEP}/submit" '{"confirmNoGo":true}'
if [[ "$HTTP_CODE" == "409" && "$HTTP_BODY" == *"QUALITY_GATE_FAILED"* ]]; then
    pass "二次确认后放行质量门判定（409 QUALITY_GATE_FAILED 属预期：本版本无报告）"
elif [[ "$HTTP_CODE" == "409" && "$HTTP_BODY" == *"NO_GO_CONFIRM_REQUIRED"* ]]; then
    fail "confirmNoGo=true 仍要求确认" "非 PMO 二次确认应被接受"
else
    fail "二次确认后应答 409 QUALITY_GATE_FAILED（HTTP ${HTTP_CODE}）" "NO-GO 确认不是放行开关，仍须过第 6 项质量门；本版本无报告必 409"
fi
q_login_as tl1 >/dev/null 2>&1
api_call POST "/api/req/releases/${REL}/reviews/${REVIEW_NOGO}/confirm" '{"note":"知悉风险，同意继续"}'
expect "PMO 确认 NO-GO 评审 200" 200
expect_jq "确认后 CONFIRMED" '.status' 'CONFIRMED'
q_admin_login
api_call GET "/api/req/releases/${REL}/reviews"
expect "评审列表 200" 200
expect_num "2 条评审" "$(jq -r 'if type=="array" then length else (.records|length // .total) end' <<<"$HTTP_BODY")" ge 2

section "q7 · 质量设置越界"
api_call PATCH "/api/req/releases/${REL}/quality-settings" '{"observationHours":0}'
deny "观察窗 0 小时 → 400" 400 WAIVER_EXPIRY_INVALID
api_call PATCH "/api/req/releases/${REL}/quality-settings" '{"observationHours":168,"reportValidityDays":14}'
expect "合法质量设置 200" 200
expect_db "观察窗落库 168" "SELECT observation_hours FROM \`release\` WHERE id=${REL}" eq 168

guard q7-strategy-env-review
