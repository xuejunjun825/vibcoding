#!/usr/bin/env bash
# s4-policy.sh — AC-11 CCode 分支策略：登记/唯一性/四类矛盾校验、外呼只在「核验」、
# 三态判定（MATCH/DRIFT/UNVERIFIED）且不可达/关闭集成时必须落 UNVERIFIED（不假绿）。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

require_stack
trap i_on_exit EXIT
c_stub_reset

DOM="$(q_domain)"
PROJECT="demo-group/demo-project"
PAT='^release/.*$'
# 幂等：上一轮跑完留下的是 enabled=0 的停用行，唯一约束仍然命中 → 先清掉本脚本自己的夹具。
db_exec "DELETE c FROM branch_policy_check c JOIN branch_policy p ON p.id=c.policy_id
         WHERE p.tenant_id=${TENANT_ID} AND p.gitlab_project='${PROJECT}' AND p.branch_pattern='${PAT}'" >/dev/null 2>&1 || true
db_exec "DELETE FROM branch_policy WHERE tenant_id=${TENANT_ID}
         AND gitlab_project='${PROJECT}' AND branch_pattern='${PAT}'" >/dev/null 2>&1 || true
GID="$(i_config GITLAB)" || { fail "集成配置落盘失败" "s4 依赖 stub-gitlab 指向 ${STUB_URL}"; guard s4-policy; }
info "stub-gitlab config=${GID} url=${STUB_URL}"

POLICY_BODY="$(jq -cn --argjson d "$DOM" --arg p "$PROJECT" \
    '{domainId:$d,gitlabProject:$p,branchPattern:"^release/.*$",patternType:"REGEX",
      protected:true,mergeMethod:"MERGE",approvalsRequired:2,
      pushRoles:["Maintainer"],mergeRoles:["Maintainer"],enabled:true}')"

section "s4 · 登记与校验"
api_call POST "${C_REQ}/code/policies" "$POLICY_BODY"
expect "POST /code/policies 建规则 200" 200
POLICY="$(jget "$HTTP_BODY" '.id')"
if [[ -n "$POLICY" && "$POLICY" != "null" ]]; then
    pass "规则 id=${POLICY}"
else
    fail "建规则没返回 id（HTTP ${HTTP_CODE}）" "看 POST /code/policies 的响应体"
    guard s4-policy
fi
expect_jq "未核验时派生态是 UNVERIFIED（不是 MATCH）" '.checkResult' UNVERIFIED
api_call POST "${C_REQ}/code/policies" "$POLICY_BODY"
deny "同域同项目同模式重复登记 → 409 POLICY_DUPLICATE" 409 POLICY_DUPLICATE
api_call POST "${C_REQ}/code/policies" "$(jq -cn --argjson d "$DOM" --arg p "other/proj" \
    '{domainId:$d,gitlabProject:$p,branchPattern:"^feat/.*$",patternType:"REGEX",protected:false,
      mergeMethod:"MERGE",approvalsRequired:2,pushRoles:[],mergeRoles:[]}')"
deny "要求审批但声明不保护 → 400 POLICY_CONTRADICTION" 400 POLICY_CONTRADICTION
api_call POST "${C_REQ}/code/policies" "$(jq -cn --argjson d "$DOM" --arg p "other/proj2" \
    '{domainId:$d,gitlabProject:$p,branchPattern:"(",patternType:"REGEX",protected:true,
      mergeMethod:"MERGE",approvalsRequired:0,pushRoles:[],mergeRoles:[]}')"
deny "非法正则 → 400 POLICY_PATTERN_INVALID" 400 POLICY_PATTERN_INVALID
api_call POST "${C_REQ}/code/policies" "$(jq -cn --argjson d "$DOM" --arg p "other/proj3" \
    '{domainId:$d,gitlabProject:$p,branchPattern:"^x$",patternType:"REGEX",protected:true,
      mergeMethod:"MERGE",approvalsRequired:9,pushRoles:[],mergeRoles:[]}')"
deny "审批人数越界 → 400 POLICY_CONTRADICTION" 400 POLICY_CONTRADICTION
api_call PUT "${C_REQ}/code/policies/${POLICY}" "$(printf '%s' "$POLICY_BODY" | jq -c '.version = 999')"
deny "版本不匹配更新 → 409 POLICY_VERSION_CONFLICT" 409 POLICY_VERSION_CONFLICT
api_call POST "${C_REQ}/code/policies/99999999/check"
deny "核验不存在的规则 → 404 POLICY_NOT_FOUND" 404 POLICY_NOT_FOUND

section "s4 · 核验 = 唯一外呼点，且三态可判"
BEFORE="$(c_stub_count all)"
api_call GET "${C_REQ}/code/policies?domainId=${DOM}"
expect "GET /code/policies 200" 200
expect_jq "列表里能看到本规则" "[.[] | select(.id==$POLICY)] | length" 1
c_zero_outbound "列表不外卖（核验才有外呼）" all "$BEFORE"

api_call POST "${C_REQ}/code/policies/${POLICY}/check"
expect "核验（stub 与规则一致）200" 200
expect_jq "结果为 MATCH" '.result' MATCH
expect_jq "五个比对项齐全" '.items | length' 5
expect_jq "五项全 ok" '[.items[] | select(.ok==false)] | length' 0
expect_db "核验落了一条 MATCH 记录" \
    "SELECT COUNT(*) FROM branch_policy_check WHERE tenant_id=${TENANT_ID} AND policy_id=${POLICY} AND result='MATCH'" \
    ge 1 "核验态必须由记录派生：只改 policy 列会在并发核验时互相覆盖"

section "s4 · GitLab 漂移 → DRIFT（按项给出差异）"
c_stub_gl_project '{"merge_method":"merge","approvals_before_merge":1,"only_allow_merge_if_pipeline_succeeds":true}'
api_call POST "${C_REQ}/code/policies/${POLICY}/check"
expect "核验 200（漂移不是错误，是结论）" 200
expect_jq "结果为 DRIFT" '.result' DRIFT
if [[ "$(jget "$HTTP_BODY" '[.items[] | select(.item=="审批人数" and .ok==false)] | length')" == "1" ]]; then
    pass "审批人数项被判为不一致（1≠2）"
else
    fail "漂移没定位到审批人数" "比对项必须带 item/expected/actual/ok，前端才能逐项显示"
fi
api_call GET "${C_REQ}/code/policies/${POLICY}/checks?limit=5"
expect "GET /checks 200" 200
expect_jq "核验历史按时间倒序，最新是 DRIFT" '.[0].result' DRIFT

section "s4 · 断链/停用不得假绿"
c_stub_gl_project '{"merge_method":"merge","approvals_before_merge":2,"only_allow_merge_if_pipeline_succeeds":true}'
c_stub_mode gitlab.project 500
api_call POST "${C_REQ}/code/policies/${POLICY}/check"
deny "GitLab 报错 → 409（不静默返回上次结果）" 409 ""
expect_db "失败也落 UNVERIFIED 记录（时间线可查）" \
    "SELECT COUNT(*) FROM branch_policy_check WHERE tenant_id=${TENANT_ID} AND policy_id=${POLICY} AND result='UNVERIFIED'" \
    ge 1 "不可达时必须落 UNVERIFIED，否则页面会残留上一次的绿色"
api_call GET "${C_REQ}/code/policies?domainId=${DOM}"
expect_jq "规则派生态回落到 UNVERIFIED" "[.[] | select(.id==$POLICY)][0].checkResult" UNVERIFIED
c_stub_mode gitlab.project ok
i_config GITLAB false >/dev/null
api_call POST "${C_REQ}/code/policies/${POLICY}/check"
deny "集成关闭 → 409 INTEGRATION_DISABLED" 409 INTEGRATION_DISABLED
expect_db "关闭集成也落 UNVERIFIED +(原因 INTEGRATION_DISABLED)" \
    "SELECT COUNT(*) FROM branch_policy_check WHERE tenant_id=${TENANT_ID} AND policy_id=${POLICY}
      AND result='UNVERIFIED' AND reason='INTEGRATION_DISABLED'" ge 1 \
    "原因要落库：页面要能区分「没接」与「接不通」"
i_config GITLAB true >/dev/null
api_call POST "${C_REQ}/code/policies/${POLICY}/check"
expect "恢复后重新核验 200" 200
expect_jq "恢复后回到 MATCH" '.result' MATCH

section "s4 · 清理"
api_call DELETE "${C_REQ}/code/policies/${POLICY}"
expect "DELETE 规则 200" 200
expect_db "删除是停用（enabled=0），核验历史保留" \
    "SELECT COUNT(*) FROM branch_policy WHERE id=${POLICY} AND enabled=0" eq 1 \
    "登记表要能追溯历史，不能物理删掉"
api_call GET "${C_REQ}/code/policies?domainId=${DOM}&enabled=true"
expect_jq "启用列表里已无本规则" "[.[] | select(.id==$POLICY)] | length" 0

guard s4-policy
