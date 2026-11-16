#!/usr/bin/env bash
# s7-negative-nine.sh — AC-18 九例负向（拒绝且状态/授权不变）
#
# 负向是本需求的验收主体：权限系统「放行错了」很难发现，「拒绝错了」每天都在发生。
# 每例都同时断言 **HTTP 状态** 与 **DB 未变**，并尽可能确认 403 detail 指向的是
# 我们要测的那一序（页面 vs 动作），否则「恰好被另一个原因拦住」会被误判为通过。
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/lib.sh"
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

TOTAL=0
PASS=0
FAIL=0
FAILED_LINES=""

pass() {
    TOTAL=$((TOTAL + 1)); PASS=$((PASS + 1))
    printf '  \033[1;32mPASS\033[0m %s\n' "$1"
}
fail() {
    TOTAL=$((TOTAL + 1)); FAIL=$((FAIL + 1))
    printf '  \033[1;31mFAIL\033[0m %s\n' "$1"
    [[ -n "${2:-}" ]] && printf '       修复: %s\n' "$2"
    FAILED_LINES="${FAILED_LINES}
  - $1"
}
report_counts() {
    if [[ -n "${RBAC_COUNTS:-}" ]]; then
        printf 's7 %s %s\n' "$PASS" "$TOTAL" >>"$RBAC_COUNTS"
    fi
}

TMP_ROLE="S7ROLE_$RANDOM"
TMP_ROLE_ID=""
TMP_USER1="s7a$RANDOM"
TMP_USER2="s7b$RANDOM"
QA_ORIG=""
DEV_ORIG=""
cleanup() {
    admin_login >/dev/null 2>&1 || true
    if [[ -n "${QA_ORIG}" ]]; then put_grants "$(role_id_of QA)" "$QA_ORIG" >/dev/null 2>&1 || true; fi
    if [[ -n "${DEV_ORIG}" ]]; then put_grants "$(role_id_of DEV)" "$DEV_ORIG" >/dev/null 2>&1 || true; fi
    if [[ -n "${TMP_ROLE_ID}" ]]; then
        api_call PATCH "/api/req/admin/roles/${TMP_ROLE_ID}/status" '{"status":"ACTIVE"}' >/dev/null 2>&1 || true
        api_call DELETE "/api/req/admin/roles/${TMP_ROLE_ID}" >/dev/null 2>&1 || true
    fi
    for u in "$TMP_USER1" "$TMP_USER2"; do
        local uid; uid="$(user_id_of "$u")"
        [[ -n "${uid}" && "${uid}" != "null" ]] && api_call PATCH "/api/req/admin/users/${uid}/status" '{"status":"DISABLED"}' >/dev/null 2>&1
    done
}
trap cleanup EXIT

log "s7：AC-18 九例负向"

admin_login || die "admin 登录失败：先 make platform-up && make platform-seed"
ADMIN_ID="$(role_id_of PLATFORM_ADMIN)"
QA_ID="$(role_id_of QA)"
DEV_ID="$(role_id_of DEV)"
QA_ORIG="$(grants_of "$QA_ID")"
DEV_ORIG="$(grants_of "$DEV_ID")"

# ---------------------------------------------------------------- 1. 无 org:manage
AUDIT0="$(db_q "SELECT COUNT(*) FROM audit_log WHERE action LIKE 'PERMISSION_DENIED%'" | head -n1)"
login_as biz1 'Passw0rd!' >/dev/null
api_call GET /api/req/admin/roles >/dev/null
C1="${HTTP_CODE:-000}"
AUDIT1="$(db_q "SELECT COUNT(*) FROM audit_log WHERE action LIKE 'PERMISSION_DENIED%'" | head -n1)"
if [[ "${C1}" == "403" ]] && [[ "${AUDIT1:-0}" -gt "${AUDIT0:-0}" ]]; then
    pass "1. 无 org:manage 的用户 GET /admin/roles ⇒ 403 且写 PERMISSION_DENIED 审计"
else
    fail "1. 无权限访问管理面未被拒绝：HTTP ${C1}，审计 ${AUDIT0:-0}→${AUDIT1:-0}" \
        "页面码 admin.roles 的 MENU 档位必须挡住整个管理面，并留审计"
fi

# ---------------------------------------------------------------- 2. 非管理员写授权
ROWS0="$(db_q "SELECT COUNT(*) FROM sys_role_permission WHERE role_id = ${QA_ID}" | head -n1)"
login_as dev1 'Passw0rd!' >/dev/null
api_call PUT "/api/req/admin/roles/${QA_ID}/permissions" "{\"grants\":$(printf '%s' "$QA_ORIG" | jq -c 'map(.access="DENY")'),\"confirmHighRisk\":true}" >/dev/null
C2="${HTTP_CODE:-000}"
ROWS1="$(db_q "SELECT COUNT(*) FROM sys_role_permission WHERE role_id = ${QA_ID}" | head -n1)"
if [[ "${C2}" == "403" && "${ROWS0}" == "${ROWS1}" ]]; then
    pass "2. 非管理员 PUT /admin/roles/{id}/permissions ⇒ 403 且授权行数不变（${ROWS1}）"
else
    fail "2. 越权写授权未被拒绝：HTTP ${C2}，行数 ${ROWS0}→${ROWS1}" \
        "PUT 授权是最高危写操作（role.action.grant），必须同时受页面与动作保护"
fi

# ---------------------------------------------------------------- 3. 清空管理员
A0="$(db_q "SELECT COUNT(*) FROM sys_role_permission WHERE role_id = ${ADMIN_ID}" | head -n1)"
admin_login >/dev/null
ADMIN_ORIG="$(grants_of "$ADMIN_ID")"
LOCK_C="$(put_grants "$ADMIN_ID" "$(printf '%s' "$ADMIN_ORIG" | jq -c 'map(.access="DENY")')")"
A1="$(db_q "SELECT COUNT(*) FROM sys_role_permission WHERE role_id = ${ADMIN_ID}" | head -n1)"
if [[ "${LOCK_C}" == "409" && "${A0}" == "${A1}" ]]; then
    pass "3. 清空 PLATFORM_ADMIN ⇒ 409 且授权行数不变（${A1}）"
else
    fail "3. 防锁死未生效：HTTP ${LOCK_C}，行数 ${A0}→${A1}" \
        "409 必须在事务内回滚；留下半套 DENY 就再也登不进管理面"
fi

# ---------------------------------------------------------------- 4. 停用角色后失权
api_call POST /api/req/admin/roles "{\"code\":\"${TMP_ROLE}\",\"name\":\"s7 停用探针\"}" >/dev/null
TMP_ROLE_ID="$(printf '%s' "$HTTP_BODY" | jq -r '.id // empty')"
ALLOW_SET="$(set_cell "$QA_ORIG" MENU "$RBAC_PAGE" ALLOW)"
ALLOW_SET="$(set_cell "$ALLOW_SET" ACTION "$RBAC_ACTION" ALLOW)"
[[ -n "${TMP_ROLE_ID}" ]] && put_grants "$TMP_ROLE_ID" "$ALLOW_SET" >/dev/null
api_call POST /api/req/admin/users "{\"username\":\"${TMP_USER1}\",\"password\":\"Passw0rd!\",\"name\":\"s7 a\",\"roles\":[\"${TMP_ROLE}\"]}" >/dev/null
if [[ -n "${TMP_ROLE_ID}" ]]; then
    login_as "$TMP_USER1" 'Passw0rd!' >/dev/null
    P4A="$(probe_settle allow)"
    admin_login >/dev/null   # 停用是管理面动作：不切回管理员就只测到「探针用户 403」
    api_call PATCH "/api/req/admin/roles/${TMP_ROLE_ID}/status" '{"status":"DISABLED"}' >/dev/null
    login_as "$TMP_USER1" 'Passw0rd!' >/dev/null
    P4B="$(probe_settle 403)"
    SETS="$(api_call GET /api/req/auth/me/permissions >/dev/null; printf '%s' "$HTTP_BODY" | jq -r '[(.menus // []), (.actions // []), (.functionCodes // [])] | add | index("'"${RBAC_PAGE}"'") // "absent"')"
    if [[ "${P4A}" != "403" && "${P4B}" == "403" && "${SETS}" == "absent" ]]; then
        pass "4. 角色停用后其用户访问原授权接口 ⇒ 403，且三集合不含该码"
    else
        fail "4. 停用未即时失权：before=${P4A} after=${P4B} 三集合命中=${SETS}" \
            "AC-10：roles() 必须带 r.status='ACTIVE'，并让 me/permissions 同步剔除"
    fi
else
    fail "4. 无法创建停用探针角色" "POST /admin/roles 失败：T4 未上线"
fi

# ---------------------------------------------------------------- 5. DENY 覆盖 ALLOW
admin_login >/dev/null
put_grants "$QA_ID" "$ALLOW_SET" >/dev/null
put_grants "$DEV_ID" "$(set_cell "$DEV_ORIG" MENU "$RBAC_PAGE" DENY)" >/dev/null
api_call POST /api/req/admin/users "{\"username\":\"${TMP_USER2}\",\"password\":\"Passw0rd!\",\"name\":\"s7 b\",\"roles\":[\"QA\",\"DEV\"]}" >/dev/null
login_as "$TMP_USER2" 'Passw0rd!' >/dev/null
C5="$(probe_settle 403)"
if [[ "${C5}" == "403" ]]; then
    pass "5. DENY 覆盖 ALLOW：QA(ALLOW)+DEV(DENY) ⇒ 403"
else
    fail "5. DENY 被 ALLOW 覆盖：HTTP ${C5}" \
        "AC-6：多角色并集必须「任一 DENY 即 DENY」，取 max 会把拒绝稀释成放行"
fi

# ---------------------------------------------------------------- 6. 跨租户/不存在角色
admin_login >/dev/null
api_call GET "/api/req/admin/roles/999999999/permissions" >/dev/null
C6="${HTTP_CODE:-000}"
LEAK="$(printf '%s' "$HTTP_BODY" | grep -cE 'PLATFORM_ADMIN|角色名|roleName' || true)"
if [[ "${C6}" == "404" && "${LEAK}" == "0" ]]; then
    pass "6. 其他租户/不存在的 role_id ⇒ 404 且响应体不泄露角色名与权限"
else
    fail "6. 越界 role_id 未被隔离：HTTP ${C6}，疑似泄露行数 ${LEAK}" \
        "查询必须带 tenant_id 条件：403 与 404 的差别就是「这个 id 存不存在」这个信息"
fi

# ---------------------------------------------------------------- 7/8. defects 双探针
# 先以管理员取角色与授权树：biz1 没有 org:manage，GET /admin/roles 对它是 403，
# role_id_of 会在 403 的错误体上跑 jq（"Cannot index number with string code"）。
admin_login >/dev/null
# 用 BUSINESS 自己的授权格做「有动作无页面」：defect.list 页面 DENY，动作 close 仍 ALLOW
BIZ_ID="$(role_id_of BUSINESS)"
BIZ_ORIG="$(grants_of "$BIZ_ID")"
put_grants "$BIZ_ID" "$(set_cell "$(set_cell "$BIZ_ORIG" MENU defect.list DENY)" ACTION defect.action.close ALLOW)" >/dev/null
login_as biz1 'Passw0rd!' >/dev/null
# 这条探针打的是 defects 动作端点（不是默认的 test-plans），临时把探针口径指过去，等撤权传播
# 到位（≤TTL）再抓 body——否则抓到的是上一步放行态的 400，detail 里自然没有 page。
SAVE_PATH="${RBAC_PROBE_PATH}"; SAVE_BODY="${RBAC_PROBE_BODY}"
RBAC_PROBE_PATH="/api/req/defects/999999/actions"; RBAC_PROBE_BODY='{"action":"close"}'
probe_settle 403 >/dev/null
RBAC_PROBE_PATH="${SAVE_PATH}"; RBAC_PROBE_BODY="${SAVE_BODY}"
api_call POST "/api/req/defects/999999/actions" '{"action":"close"}' >/dev/null
C7="${HTTP_CODE:-000}"
DET7="$(printf '%s' "$HTTP_BODY" | jq -r '.detail.page // empty' 2>/dev/null)"
if [[ "${C7}" == "403" && "${DET7}" == "defect.list" ]]; then
    pass "7. 只授动作、页面 DENY ⇒ 403（detail.page=defect.list，页面前置）"
else
    fail "7. 有动作无页面仍放行：HTTP ${C7} detail.page=${DET7:-<空>}" \
        "页面码是准入的第一序：动作授权不能让被关掉的页面重新可达"
fi

admin_login >/dev/null
put_grants "$BIZ_ID" "$(set_cell "$(set_cell "$BIZ_ORIG" MENU defect.list ALLOW)" ACTION defect.action.close DENY)" >/dev/null
login_as biz1 'Passw0rd!' >/dev/null
SAVE_PATH="${RBAC_PROBE_PATH}"; SAVE_BODY="${RBAC_PROBE_BODY}"
RBAC_PROBE_PATH="/api/req/defects/999999/actions"; RBAC_PROBE_BODY='{"action":"close"}'
probe_settle 403 >/dev/null
RBAC_PROBE_PATH="${SAVE_PATH}"; RBAC_PROBE_BODY="${SAVE_BODY}"
api_call POST "/api/req/defects/999999/actions" '{"action":"close"}' >/dev/null
C8="${HTTP_CODE:-000}"
DET8="$(printf '%s' "$HTTP_BODY" | jq -r '.detail.action // empty' 2>/dev/null)"
if [[ "${C8}" == "403" && "${DET8}" == "defect.action.close" ]]; then
    pass "8. 页面 ALLOW、动作 DENY ⇒ 403（detail.action=defect.action.close）"
else
    fail "8. 有页面无动作仍放行：HTTP ${C8} detail.action=${DET8:-<空>}" \
        "按钮不渲染只是 UI；后端必须按动作码再拒一次，否则直调 API 就绕过"
fi
admin_login >/dev/null
put_grants "$BIZ_ID" "$BIZ_ORIG" >/dev/null

# ---------------------------------------------------------------- 9. 高危动作
login_as dev1 'Passw0rd!' >/dev/null
api_call POST "/api/req/deploy-orders/999999/force-release" '{}' >/dev/null
C9="${HTTP_CODE:-000}"
DET9="$(printf '%s' "$HTTP_BODY" | jq -r '.detail.action // empty' 2>/dev/null)"
if [[ "${C9}" == "403" && "${DET9}" == "deploy.action.force_release" ]]; then
    pass "9. 未授高危动作 ⇒ 403（detail.action=deploy.action.force_release）"
else
    fail "9. 高危动作未被拒绝：HTTP ${C9} detail.action=${DET9:-<空>}" \
        "强发是 HIGH_RISK：默认只给管理员，且必须走二次确认"
fi

# ---------------------------------------------------------------- 汇总
if (( TOTAL == 0 )); then
    err "s7: 一条断言都没执行：脚本实现有 bug"
    exit 1
fi
if (( PASS != TOTAL )); then
    err "s7-negative-nine: ${FAIL} 项失败 / ${TOTAL} 项"
    printf '%s\n' "$FAILED_LINES" >&2
    report_counts
    exit 1
fi
report_counts
ok "s7-negative-nine: ${PASS}/${TOTAL} 项通过（AC-18 九例）"
