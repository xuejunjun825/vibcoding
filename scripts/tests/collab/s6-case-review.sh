#!/usr/bin/env bash
# s6-case-review.sh — AC-17 CTest 用例评审：提交即钉住 case_version、只有评审人可裁决、
# 用例升版同事务失效旧评审（旧评审不得继续有效）、待办列表可分页按状态查。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

require_stack
trap i_on_exit EXIT

# D-02：不复用种子用例。历史评审会让第二次运行撞 409 CASE_REVIEW_DUPLICATE、
# 断言随库状态漂移（18 → 11）。每次运行自建一个带 RUN_TAG 的用例（版本从 1 起），
# 结束时 DELETE 软删自清，保证本脚本可连跑且断言数恒定。
MODULE="$(db_q "SELECT id FROM test_module WHERE tenant_id=${TENANT_ID} ORDER BY id LIMIT 1")"
[[ -n "$MODULE" && "$MODULE" != "NULL" ]] || { fail "取不到用例模块夹具" "缺 seed：先 make platform-seed"; guard s6-case-review; }
api_call POST "${C_DELIVER}/testcases" "$(jq -cn --argjson m "$MODULE" --arg t "s6评审-${RUN_TAG}-$$" \
    '{moduleId:$m,title:$t,type:"FUNCTIONAL",priority:"P2",steps:[]}')"
CASE="$(jget "$HTTP_BODY" '.id')"
if [[ -z "$CASE" || "$CASE" == "null" ]]; then
    fail "自建评审用例失败（HTTP ${HTTP_CODE}）" "POST ${C_DELIVER}/testcases 不可用：先跑 T6"
    guard s6-case-review
fi
ME="$(q_user_id admin)"
OTHER="$(q_user_id lisi)"
[[ -n "$OTHER" && "$OTHER" != "NULL" ]] || OTHER="$(q_user_id wangwu)"
CUR_VER="$(db_q "SELECT current_version FROM test_case WHERE id=${CASE} AND tenant_id=${TENANT_ID}")"
info "case=${CASE} current_version=${CUR_VER} me=${ME} other=${OTHER}"

section "s6 · 提交评审：钉版本"
api_call GET "${C_DELIVER}/testcases/${CASE}/reviews"
expect "GET /testcases/{id}/reviews 200" 200
expect_jq "评审列表是数组" 'type' array
api_call POST "${C_DELIVER}/testcases/${CASE}/reviews" \
    "$(jq -cn --argjson r "${ME:-1}" '{reviewerId:$r,comment:"s6 提交评审"}')"
if [[ "$HTTP_CODE" == "200" || "$HTTP_CODE" == "201" ]]; then
    pass "POST 提交评审成功（HTTP ${HTTP_CODE}）"
else
    fail "POST 提交评审 HTTP ${HTTP_CODE}" "T6 的 /testcases/{id}/reviews 未实现时这里会 404：先跑 T6"
fi
REVIEW="$(jget "$HTTP_BODY" '.id // .reviewId')"
expect_jq "评审钉住当前用例版本" '.caseVersion' "$CUR_VER"
expect_db "库里 case_version = ${CUR_VER}" \
    "SELECT COUNT(*) FROM test_case_review WHERE tenant_id=${TENANT_ID} AND case_id=${CASE} AND case_version=${CUR_VER}" \
    ge 1 "评审必须绑版本：绑「用例 id」的评审在升版后会悄悄继续有效"
status_of() { db_q "SELECT status FROM test_case_review WHERE id=$1"; }
api_call POST "${C_DELIVER}/testcases/${CASE}/reviews" \
    "$(jq -cn --argjson r "${ME:-1}" '{reviewerId:$r,comment:"同版本重复提交"}')"
deny "同用例同版本重复提交 → 409 CASE_REVIEW_DUPLICATE" 409 CASE_REVIEW_DUPLICATE

section "s6 · 只有评审人能裁决"
if [[ -n "$OTHER" && "$OTHER" != "NULL" && "$OTHER" != "$ME" ]]; then
    q_login_as "$(db_q "SELECT username FROM sys_user WHERE id=${OTHER} LIMIT 1")" >/dev/null 2>&1 || true
    api_call POST "${C_DELIVER}/testcase-reviews/${REVIEW}/decide" '{"conclusion":"PASS","comment":"越权"}'
    deny "非评审人裁决 → 403" 403 ""
    q_admin_login
else
    info "没有第二个种子账号，跳过越权裁决用例（403 由权限码 CASE_REVIEW_DECIDE 兜底）"
fi
api_call POST "${C_DELIVER}/testcase-reviews/${REVIEW}/decide" '{"conclusion":"PASS","comment":"s6 通过"}'
expect "评审人裁决 200" 200
expect_db "评审状态已落定（非待裁决）" \
    "SELECT COUNT(*) FROM test_case_review WHERE id=${REVIEW} AND status='DONE'" eq 1 \
    "裁决必须落库（SUBMITTED→DONE），否则待办列表永远清不掉"
api_call GET "${C_DELIVER}/testcase-reviews?status=SUBMITTED&page=1&size=20"
expect "GET /testcase-reviews（待办）200" 200
expect_jq "分页结构" '(.items | type)' array

section "s6 · 用例升版 → 旧评审失效"
api_call PUT "${C_DELIVER}/testcases/${CASE}" \
    "$(jq -cn --argjson m "$(db_q "SELECT module_id FROM test_case WHERE id=${CASE}")" \
        --arg t "s6改版-${RUN_TAG}" '{moduleId:$m,title:$t,type:"FUNCTIONAL",priority:"P2",steps:[]}')"
expect "PUT 用例（升版）200" 200
NEW_VER="$(db_q "SELECT current_version FROM test_case WHERE id=${CASE}")"
if [[ "$NEW_VER" != "$CUR_VER" ]]; then
    pass "用例版本已升到 ${NEW_VER}"
else
    fail "升版没生效（仍 ${CUR_VER}）" "PUT /testcases/{id} 必须产新版本，否则失效钩子无从触发"
fi
if [[ "$(status_of "$REVIEW")" == "INVALIDATED" ]]; then
    pass "旧评审随升版失效（status=INVALIDATED）"
else
    fail "旧评审状态=$(status_of "$REVIEW")（期望 INVALIDATED）" \
        "失效必须与用例升版同事务：否则「旧评审仍有效」正是 PRD 的反面目标"
fi
expect_db "失效留原因" \
    "SELECT COUNT(*) FROM test_case_review WHERE id=${REVIEW} AND (invalid_reason IS NOT NULL AND invalid_reason='NEW_VERSION')" \
    eq 1 "只说失效不给原因，评审人无法判断要不要重审"

section "s6 · 新版本可再提交，旧版本评审并列可见"
api_call POST "${C_DELIVER}/testcases/${CASE}/reviews" \
    "$(jq -cn --argjson r "${ME:-1}" '{reviewerId:$r,comment:"s6 新版本复审"}')"
expect "新版本再提交 200" 200
expect_jq "新评审钉住新版本" '.caseVersion' "$NEW_VER"
api_call GET "${C_DELIVER}/testcases/${CASE}/reviews"
expect_jq "两条评审（旧失效 + 新待裁）" 'length' 2
api_call POST "${C_DELIVER}/testcases/99999999/reviews" "$(jq -cn --argjson r "${ME:-1}" '{reviewerId:$r}')"
deny "不存在的用例提交评审 → 404" 404 ""

section "s6 · 自清"
api_call DELETE "${C_DELIVER}/testcases/${CASE}"
if [[ "$HTTP_CODE" == "200" || "$HTTP_CODE" == "204" ]]; then
    info "自建用例 ${CASE} 已软删（下次运行从空评审历史开始）"
else
    fail "用例自清失败（DELETE 返回 ${HTTP_CODE}）" "s6 必须自建自清，否则第二次运行会撞重复评审"
fi

guard s6-case-review
