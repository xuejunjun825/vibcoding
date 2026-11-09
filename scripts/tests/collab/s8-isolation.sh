#!/usr/bin/env bash
# s8-isolation.sh — AC-18/AC-19 权限与隔离：新路径前缀的权限码由后端拦截（不是前端藏按钮）、
# 角色边界（QA 无甘特/价值流/代码策略读）、跨租户不可见（Wiki/甘特/MR 评论一律 404），
# Wiki 可见性三态（VENDOR 空间对别的租户不可见）。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

require_stack
trap i_on_exit EXIT

T1_REQ="$(db_q "SELECT id FROM requirement WHERE tenant_id=${TENANT_ID} ORDER BY id LIMIT 1")"

section "s8 · 角色边界（QA：无甘特/价值流/代码策略读）"
q_login_as qa1 >/dev/null 2>&1 || info "qa1 登录失败：后续权限断言会因 401 而非 403 失败"
api_call GET "${C_REQ}/gantt/rows?view=RELEASE"
deny "QA 读甘特 → 403（GANTT_VIEW 未授权）" 403 FORBIDDEN
api_call GET "${C_DELIVER}/flow/stages"
deny "QA 读价值流 → 403（FLOW_VIEW 未授权）" 403 FORBIDDEN
api_call GET "${C_REQ}/code/policies?domainId=$(q_domain)"
deny "QA 读分支策略 → 403（CCODE_POLICY_VIEW 未授权）" 403 FORBIDDEN
api_call GET "${C_REQ}/admin/permissions"
deny "QA 读权限矩阵 → 403（管理页只是投影，后端必须拦）" 403 FORBIDDEN
api_call GET "${C_REQ}/wiki/spaces"
expect "QA 读 Wiki 200（WIKI_VIEW 已授权）" 200
api_call POST "${C_REQ}/wiki/spaces" "$(jq -cn --arg n "s8越权-${RUN_TAG}" '{name:$n}')"
deny "QA 建 Wiki 空间 → 403（WIKI_ADMIN 未授权）" 403 FORBIDDEN
api_call PATCH "${C_REQ}/gantt/rows/REQUIREMENT/${T1_REQ}" '{"planStart":"2026-11-10","planEnd":"2026-11-12","version":"0"}'
deny "QA 改期 → 403（GANTT_EDIT 未授权）" 403 FORBIDDEN

section "s8 · DEV 有 GANTT_EDIT（权限不是「一律 403」）"
q_login_as dev1 >/dev/null 2>&1 || true
api_call PATCH "${C_REQ}/gantt/rows/REQUIREMENT/${T1_REQ}" '{"planStart":"2026-11-10"}'
if [[ "$HTTP_CODE" == "403" ]]; then
    fail "DEV 改期被判 403" "DEV 在矩阵里有 GANTT_EDIT；403 说明路由表匹配到了更严的码"
else
    pass "DEV 改期未被权限拦截（HTTP ${HTTP_CODE}，进入参数/版本校验）"
fi
q_admin_login

section "s8 · 跨租户不可见"
T2_CODE="$(db_q "SELECT code FROM sys_tenant WHERE id=2 LIMIT 1")"
T1_SPACE="$(db_q "SELECT id FROM wiki_space WHERE tenant_id=${TENANT_ID} ORDER BY id LIMIT 1")"
T1_PAGE="$(db_q "SELECT id FROM wiki_page WHERE tenant_id=${TENANT_ID} ORDER BY id LIMIT 1")"
if [[ -n "$T2_CODE" ]] && platform_login "$T2_CODE" admin 'Admin@12345' >/dev/null 2>&1; then
    API_TOKEN="$(jget "$HTTP_BODY" '.token')"; API_TENANT="$(jget "$HTTP_BODY" '.tenantId')"
    pass "切到第二租户 ${T2_CODE}（tenantId=${API_TENANT}）"
    api_call GET "${C_REQ}/requirements/${T1_REQ}"
    deny "跨租户读需求 → 404" 404 ""
    api_call GET "${C_REQ}/wiki/pages/${T1_PAGE}"
    deny "跨租户读 Wiki 页面 → 404（隔离谓词不是只在列表上）" 404 ""
    api_call GET "${C_REQ}/wiki/spaces"
    expect "跨租户空间列表 200" 200
    expect_jq "看不到租户 1 的空间" "[(.items // .)[] | select(.id==${T1_SPACE:-0})] | length" 0
    api_call GET "${C_REQ}/gantt/rows?view=RELEASE&domainId=1"
    if [[ "$HTTP_CODE" == "200" ]]; then
        expect_jq "看不到租户 1 的版本行" "[.rows[] | select(.type==\"RELEASE\")] | length" 0
    else
        pass "跨租户读甘特被拒（HTTP ${HTTP_CODE}）"
    fi
    api_call GET "${C_REQ}/requirements/${T1_REQ}/integration/mr-comments?limit=5"
    deny "跨租户读 MR 评论 → 404（评论随需求可见性）" 404 ""
    q_admin_login
else
    info "第二租户 ${T2_CODE:-?} 登录不可用，跨租户段跳过（隔离断言由 platform-test-negative 覆盖）"
fi

section "s8 · Wiki 可见性 VENDOR（同租户内不同厂商不串门）"
api_call POST "${C_REQ}/wiki/spaces" "$(jq -cn --arg n "s8厂商空间-${RUN_TAG}" \
    --argjson d "$(q_domain)" '{domainId:$d,name:$n,visibility:"VENDOR",vendorId:1}')"
expect "建 VENDOR 空间 200" 200
VSPACE="$(jget "$HTTP_BODY" '.id')"
api_call GET "${C_REQ}/wiki/spaces"
expect_num "VENDOR 空间对本域可见（创建者能读回）" \
    "$(jget "$HTTP_BODY" "[(.items // .)[] | select(.id==${VSPACE:-0})] | length")" ge 1
api_call GET "${C_REQ}/wiki/spaces/${VSPACE}/tree"
expect "VENDOR 空间树可读 200" 200
if [[ -n "$VSPACE" ]]; then
    expect_db "空间隔离列落库（domain_id/vendor_id）" \
        "SELECT COUNT(*) FROM wiki_space WHERE id=${VSPACE} AND tenant_id=${TENANT_ID} AND domain_id IS NOT NULL AND vendor_id=1" \
        eq 1 "可见性谓词依赖 scope 列：不写就永远只能靠 INHERIT 兜底"
fi

section "s8 · 附件锚点同样受可见性约束"
api_call GET "${C_REQ}/attachments?bizType=WIKI_PAGE&bizId=${T1_PAGE}"
expect "读 Wiki 附件列表 200" 200
expect_jq "无附件时返回空数组（不是 500/404）" 'type' array
api_call GET "${C_REQ}/attachments?bizType=NOPE&bizId=1"
deny "未知 bizType → 400" 400 ""

guard s8-isolation
