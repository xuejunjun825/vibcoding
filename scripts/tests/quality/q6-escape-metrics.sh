#!/usr/bin/env bash
# q6-escape-metrics.sh — AC-8 生产缺陷逃逸回流 + 5 项质量指标。
#
# 覆盖：生产缺陷必须挂版本（缺版本 400）、逃逸判定在创建时一次落库（之后改观察窗不改历史）、
# 逃逸窗口 = 成功发布 ended_at + observation_hours、5 项指标同源（看板与版本质量口径一致）、
# 零分母返回 null 而不是 0/报错、报告签发后数据变化 → QG-02 digest 不一致并提示重新出具。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

require_stack

section "q6 · 准备 fixture（一个成功发布的版本）"
REL="$(q_create_release "逃逸版本-${RUN_TAG}")" || exit 1
REQ_ID="$(db_q "SELECT id FROM requirement WHERE tenant_id=${TENANT_ID} ORDER BY id LIMIT 1")"
DEP="$(q_create_deploy_order "$REL" "$REQ_ID" PROD)" || exit 1
# 直接把发布单标记为执行成功：本脚本只验证「逃逸窗口」口径，不重复验证审批流（platform-verify 已覆盖）
db_q "UPDATE deploy_order SET status='SUCCESS', execution_result='SUCCESS', ended_at=NOW(3) WHERE id=${DEP}" >/dev/null
WINDOW_END="$(db_q "SELECT DATE_FORMAT(DATE_ADD(ended_at, INTERVAL (SELECT observation_hours FROM \`release\` WHERE id=${REL}) HOUR), '%Y-%m-%d %H:%i:%s') FROM deploy_order WHERE id=${DEP}")"
info "releaseId=${REL} deployOrderId=${DEP} windowEnd=${WINDOW_END}"

section "q6 · 生产缺陷必须关联版本（否则无法判定是否逃逸）"
api_call POST /api/deliver/defects "$(jq -cn --argjson q "$REQ_ID" \
    '{requirementId:$q,title:"无版本生产缺陷",description:"线上白屏",severity:"P1",priority:"P1",source:"PROD",foundPhase:"PROD"}')"
deny "PROD 缺陷不带版本 → 400" 400 "RELEASE_REQUIRED"
api_call POST /api/deliver/defects "$(jq -cn --argjson q "$REQ_ID" --argjson r "$REL" \
    '{requirementId:$q,releaseId:$r,title:"版本内生产缺陷",description:"线上白屏",severity:"P2",priority:"P2",source:"PROD",foundPhase:"PROD"}')"
expect "PROD 缺陷带版本 201" 201
DEF="$(jget "$HTTP_BODY" '.id')"
expect_jq "创建即返回 isEscape=true（窗口内）" '.isEscape' 'true'
expect_db "逃逸标记落库" "SELECT is_escape FROM defect WHERE id=${DEF}" eq 1
DEF_WINDOW="$(db_q "SELECT DATE_FORMAT(escape_window_end,'%Y-%m-%d %H:%i:%s') FROM defect WHERE id=${DEF}")"
if [[ "$DEF_WINDOW" == "$WINDOW_END" ]]; then
    pass "逃逸窗口 = 发布成功时间 + observation_hours（${DEF_WINDOW}）"
else
    fail "逃逸窗口 ${DEF_WINDOW:-<空>} 与期望 ${WINDOW_END} 不一致" "窗口起点取 deploy_order.execution_result='SUCCESS' 的 ended_at"
fi

section "q6 · 改窗口不改历史（AC-8.3）"
db_q "UPDATE \`release\` SET observation_hours = 1 WHERE id=${REL}" >/dev/null
api_call GET "/api/deliver/defects/${DEF}"
expect "缺陷详情 200" 200
expect_jq "历史逃逸标记不变" '.isEscape' 'true'
DEF_WINDOW2="$(db_q "SELECT DATE_FORMAT(escape_window_end,'%Y-%m-%d %H:%i:%s') FROM defect WHERE id=${DEF}")"
if [[ "$DEF_WINDOW2" == "$DEF_WINDOW" ]]; then
    pass "escape_window_end 不被回算"
else
    fail "改观察窗后 escape_window_end 从 ${DEF_WINDOW} 变成 ${DEF_WINDOW2}" "逃逸判定只允许在 INSERT 时一次落库"
fi
db_q "UPDATE \`release\` SET observation_hours = 72 WHERE id=${REL}" >/dev/null

section "q6 · 窗口外的生产缺陷不算逃逸"
REL_OLD="$(q_create_release "历史版本-${RUN_TAG}")" || exit 1
DEP_OLD="$(q_create_deploy_order "$REL_OLD" "$REQ_ID" PROD)" || exit 1
db_q "UPDATE deploy_order SET status='SUCCESS', execution_result='SUCCESS', ended_at=DATE_SUB(NOW(3), INTERVAL 10 DAY) WHERE id=${DEP_OLD}" >/dev/null
api_call POST /api/deliver/defects "$(jq -cn --argjson q "$REQ_ID" --argjson r "$REL_OLD" \
    '{requirementId:$q,releaseId:$r,title:"上线十天后才发现",description:"不是逃逸",severity:"P3",priority:"P3",source:"PROD",foundPhase:"PROD"}')"
expect "窗口外缺陷创建 201" 201
expect_jq "窗口外 isEscape=false" '.isEscape' 'false'
expect_db "窗口外 is_escape=0" "SELECT is_escape FROM defect WHERE release_id=${REL_OLD} AND title='上线十天后才发现'" eq 0

section "q6 · 5 项指标同源（口径唯一）"
api_call GET "/api/deliver/metrics/quality?releaseId=${REL}"
expect "质量指标 200" 200
expect_num "返回 5 项指标" "$(jq -r '.metrics | length' <<<"$HTTP_BODY")" eq 5
for code in FPY REGRESSION_PASS_RATE DEFECT_ESCAPE_RATE POST_RELEASE_DEFECTS_72H ROLLBACK_RATE; do
    grep -qF "\"${code}\"" <<<"$HTTP_BODY" && pass "指标含 ${code}" || fail "指标缺 ${code}"
done
METRIC_FPY="$(jget "$HTTP_BODY" '.fpy // (.metrics[] | select(.code=="FPY") | .value)')"
api_call GET "/api/deliver/releases/${REL}/quality"
expect "版本质量 200" 200
RELQ_FPY="$(jget "$HTTP_BODY" '.counts.fpy // .fpy')"
if [[ -z "$METRIC_FPY" || "$METRIC_FPY" == "null" ]]; then
    if [[ -z "$RELQ_FPY" || "$RELQ_FPY" == "null" ]]; then
        pass "两侧一致地返回 null（零分母）"
    else
        fail "指标接口 null 但版本质量返回 ${RELQ_FPY}" "同一口径必须同时 null"
    fi
elif [[ "$METRIC_FPY" == "$RELQ_FPY" ]]; then
    pass "看板与版本质量的 FPY 一致（${METRIC_FPY}）"
else
    fail "FPY 口径分叉（metrics=${METRIC_FPY} / releaseQuality=${RELQ_FPY}）" "只允许经 QualityMetricService + MetricFormulas 输出"
fi
expect_db "逃逸数按同一 WHERE 统计" "SELECT COUNT(*) FROM defect WHERE release_id=${REL} AND is_escape=1" eq 1

section "q6 · 零分母返回 null（不是 0，也不是 500）"
REL_EMPTY="$(q_create_release "空版本-${RUN_TAG}")" || exit 1
api_call GET "/api/deliver/metrics/quality?releaseId=${REL_EMPTY}"
expect "空版本指标 200" 200
ROLLBACK_NULL="$(jq -r '[(.metrics // [])[] | select(.code=="ROLLBACK_RATE")][0].value' <<<"$HTTP_BODY")"
if [[ -z "$ROLLBACK_NULL" || "$ROLLBACK_NULL" == "null" ]]; then
    pass "无发布单时 ROLLBACK_RATE 为 null"
else
    fail "无发布单时 ROLLBACK_RATE 返回 ${ROLLBACK_NULL}" "分母为 0 必须是 null，展示层显示「—」"
fi

section "q6 · digest 变化 → QG-02 FAIL 并提示重新出具"
READ_OUT="$(q_build_release_case "摘要版本" 3 0)"
REL2="$(awk '{print $1}' <<<"$READ_OUT")"
PLAN2="$(awk '{print $2}' <<<"$READ_OUT")"
RPT2="$(awk '{print $3}' <<<"$READ_OUT")"
DEP2="$(awk '{print $4}' <<<"$READ_OUT")"
GATE_JSON="$(q_gate_snapshot "$REL2" "$DEP2")"
if jq -e '.result == "PASS" or .result == "PASS_WITH_WAIVER"' <<<"$GATE_JSON" >/dev/null; then
    pass "基线门禁通过（报告新鲜）"
else
    fail "基线门禁 $(jq -r '.result' <<<"$GATE_JSON")，无法验证 digest 失效" "先确认报告已签、通过率 100%"
fi
DIGEST_BEFORE="$(db_q "SELECT result_digest FROM test_report_version WHERE report_id=${RPT2} ORDER BY version_no DESC LIMIT 1")"
# 改一条已执行用例的结果：模拟「报告出具后数据变了」。
# 计划已 COMPLETED，再走 execute 必然 409 ILLEGAL_PLAN_TRANSITION；fixture 直接改结果，
# 正好覆盖「报告冻结后底层数据被人为/回归修正」这一真实场景（digest 只看 plan_case.result）。
CASE0="$(q_plan_cases "$PLAN2" | awk '{print $1}')"
db_q "UPDATE test_plan_case SET result='FAIL' WHERE plan_id=${PLAN2} AND case_id=${CASE0}" >/dev/null
CASE0_RESULT="$(db_q "SELECT result FROM test_plan_case WHERE plan_id=${PLAN2} AND case_id=${CASE0}")"
[[ "$CASE0_RESULT" == "FAIL" ]] || { err "fixture 改结果失败（case=${CASE0} result=${CASE0_RESULT:-<空>}）"; exit 1; }
GATE_JSON="$(q_gate_snapshot "$REL2" "$DEP2")"
if jq -e '.rules[]? | select(.code=="QG-02" and .status=="FAIL")' <<<"$GATE_JSON" >/dev/null; then
    pass "数据变化后 QG-02 FAIL（digest 不一致）"
else
    fail "数据变化后 QG-02 仍 PASS" "QG-02 必须比对当前 digest 与报告 digest"
fi
if jq -e '(.rules[]? | select(.code=="QG-02") | .fixHint) | test("重新出具")' <<<"$GATE_JSON" >/dev/null; then
    pass "QG-02 修复指引提示重新出具报告"
else
    skip "QG-02 fixHint 文案不同（不阻塞，记录偏差）"
fi
api_call POST "/api/req/deploy-orders/${DEP2}/submit" '{}'
deny "digest 失效后提交 → 409 QUALITY_GATE_FAILED" 409 QUALITY_GATE_FAILED

guard q6-escape-metrics
