#!/usr/bin/env bash
# s6-user-org.sh — 人员与组织授权（TECHNICAL §7、AC-13/AC-14）
#
# 两类「差集」写入 + 两条新端点（组织删除 / 人员迁移）。它们共同的要求是
# **要么全写要么不写**：旧代码遇到未知角色静默跳过，会留下半成品用户，
# 而这种用户在现场表现为「有时能进、有时进不去」。
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/lib.sh"
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
# 造名走 _lib.sh 的 rbac_run_tag（全数字 tag，落进 demo-reset 的停用正则），清理走 rbac_purge_users。
# 一次运行只用一把 tag：用户与组织必须同 tag（别调两次 rbac_run_tag，那样两次的随机尾巴不同）。
# 本段只造用户不造角色，所以不需要 rbac_purge_roles；组织没有段名前缀可复用，仍按 ORG_ID 删。
RBAC_RUN_TAG="$(rbac_run_tag)"

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
        printf 's6 %s %s\n' "$PASS" "$TOTAL" >>"$RBAC_COUNTS"
    fi
}

TMP_USER="s6u${RBAC_RUN_TAG}"
ORG_NAME="s6组织${RBAC_RUN_TAG}"
ORG_ID=""
USER_ID=""
cleanup() {
    # 顺序是硬约束：先删用户再删组织。组织删除对「里面还有人」是 409，反过来的话失败路径会留下
    # 一个 s6组织<tag> 的孤儿组织——而组织没有段名前缀这一层可复用的身份，只能靠 ORG_ID 这一句删。
    rbac_purge_users s6 >/dev/null \
        || { err "s6 cleanup: 删临时用户失败（DB 不可读或写被拒）：先 make platform-up"; exit 1; }
    if [[ -n "${ORG_ID}" ]]; then
        admin_login >/dev/null 2>&1 || true
        api_call DELETE "/api/req/admin/orgs/${ORG_ID}" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

log "s6：人员角色/域差集 + 组织删除 + 人员迁移"

admin_login || die "admin 登录失败：先 make platform-up && make platform-seed"

# 起跑自愈：上一轮被 kill -9（EXIT trap 不执行）或 QA 用同一个 RBAC_RUN_TAG 连跑两次时，
# 同名的 s6u<tag> 还在库里——不清掉的话建号 409、USER_ID 空，报错点跑到「POST /admin/users 需要 T4」。
rbac_purge_users s6 >/dev/null \
    || { err "s6: 起跑清理临时用户失败：先 make platform-up"; report_counts; exit 1; }

ROOT_ORG="$(api_call GET /api/req/admin/orgs >/dev/null; printf '%s' "$HTTP_BODY" | jq -r '.[0].id // empty')"
[[ -n "${ROOT_ORG}" ]] || ROOT_ORG="$(printf '%s' "$HTTP_BODY" | jq -r '.items[0].id // empty')"
[[ -n "${ROOT_ORG}" ]] || die "GET /admin/orgs 没有返回任何组织：先 make platform-seed"

# ---------------------------------------------------------------- 1. 新建组织与用户
api_call POST /api/req/admin/orgs "{\"name\":\"${ORG_NAME}\",\"parentId\":${ROOT_ORG}}" >/dev/null
ORG_ID="$(printf '%s' "$HTTP_BODY" | jq -r '.id // empty')"
if [[ ( "${HTTP_CODE:-000}" == "200" || "${HTTP_CODE:-000}" == "201" ) && -n "${ORG_ID}" ]]; then
    pass "新建组织（HTTP ${HTTP_CODE}，id=${ORG_ID}）"
else
    fail "新建组织失败：HTTP ${HTTP_CODE:-000} ${HTTP_BODY:0:120}" \
        "POST /admin/orgs 需要 org.action.create 声明（s9 同时覆盖）"
    report_counts
    exit 1
fi

api_call POST /api/req/admin/users "{\"username\":\"${TMP_USER}\",\"password\":\"Passw0rd!\",\"name\":\"s6 probe\",\"orgId\":${ORG_ID},\"roles\":[\"QA\"]}" >/dev/null
USER_ID="$(printf '%s' "$HTTP_BODY" | jq -r '.id // empty')"
if [[ -n "${USER_ID}" ]]; then
    pass "新建用户并落组织（id=${USER_ID}，orgId=${ORG_ID}）"
else
    fail "新建用户失败：HTTP ${HTTP_CODE:-000} ${HTTP_BODY:0:120}" "POST /admin/users 需要 T4 已上线"
    report_counts
    exit 1
fi

# ---------------------------------------------------------------- 2. 角色差集
api_call PUT /api/req/admin/users "{\"id\":${USER_ID},\"name\":\"s6 probe\",\"roles\":[\"QA\",\"PMO\"]}" >/dev/null
ROLE_ROWS="$(db_q "SELECT COUNT(*) FROM sys_user_role WHERE user_id = ${USER_ID}" | head -n1)"
ROLE_SET="$(db_q "SELECT GROUP_CONCAT(r.code ORDER BY r.code) FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id WHERE ur.user_id = ${USER_ID}" | head -n1)"
if [[ "${ROLE_ROWS}" == "2" && "${ROLE_SET}" == "PMO,QA" ]]; then
    pass "用户角色按差集替换（QA+PMO，库内 2 行且无残留）"
else
    fail "角色差集不干净：rows=${ROLE_ROWS:-0} set=${ROLE_SET:-<空>}" \
        "PUT /admin/users 的 roles 是**替换**语义：先算差集再删/增，不能只增不删"
fi

# ---------------------------------------------------------------- 3. 未知角色 → 400 且不落半成品
BEFORE_SIG="$(db_q "SELECT CONCAT(name, '|', COUNT(ur.role_id)) FROM sys_user u
      LEFT JOIN sys_user_role ur ON ur.user_id = u.id WHERE u.id = ${USER_ID} GROUP BY u.id, u.name" | head -n1)"
api_call PUT /api/req/admin/users "{\"id\":${USER_ID},\"name\":\"s6 半成品\",\"roles\":[\"QA\",\"NO_SUCH_ROLE\"]}" >/dev/null
BAD_CODE="${HTTP_CODE:-000}"
BAD_ERR="$(printf '%s' "$HTTP_BODY" | jq -r '.code // empty')"
AFTER_SIG="$(db_q "SELECT CONCAT(name, '|', COUNT(ur.role_id)) FROM sys_user u
      LEFT JOIN sys_user_role ur ON ur.user_id = u.id WHERE u.id = ${USER_ID} GROUP BY u.id, u.name" | head -n1)"
if [[ "${BAD_CODE}" == "400" && "${BEFORE_SIG}" == "${AFTER_SIG}" ]]; then
    pass "未知角色 ⇒ 400 且用户行/关系行完全未变（${AFTER_SIG}）"
else
    fail "未知角色未整体拒绝：HTTP ${BAD_CODE} code=${BAD_ERR} 之前=${BEFORE_SIG} 之后=${AFTER_SIG}" \
        "AC-13：先全量校验再写；静默跳过未知角色会写出「少了几个角色」的用户"
fi

# ---------------------------------------------------------------- 4. 组织删除防误删
api_call DELETE "/api/req/admin/orgs/${ORG_ID}" >/dev/null
DEL_PEOPLE="${HTTP_CODE:-000}|$(printf '%s' "$HTTP_BODY" | jq -r '.code // empty')|$(printf '%s' "$HTTP_BODY" | jq -r '(.detail.users // .users // 0)')"
if [[ "${DEL_PEOPLE%%|*}" == "409" && "$(printf '%s' "${DEL_PEOPLE}" | cut -d'|' -f2)" == "ORG_HAS_USERS" ]]; then
    pass "删除仍有人员的组织 ⇒ 409 ORG_HAS_USERS（带人数 ${DEL_PEOPLE##*|}）"
else
    fail "删除在用组织未被阻止：${DEL_PEOPLE}" \
        "AC-14：409 必须带人数，前端据此给「去迁移」入口；删组织不能连带删人"
fi

# ---------------------------------------------------------------- 5. 人员迁移
api_call POST "/api/req/admin/orgs/${ORG_ID}/move-users" "{\"userIds\":[${USER_ID}],\"targetOrgId\":${ROOT_ORG}}" >/dev/null
MOVED="$(printf '%s' "$HTTP_BODY" | jq -r '.moved // 0')"
NEW_ORG="$(db_q "SELECT org_id FROM sys_user WHERE id = ${USER_ID}" | head -n1)"
if [[ "${HTTP_CODE:-000}" == "200" && "${MOVED}" == "1" && "${NEW_ORG}" == "${ROOT_ORG}" ]]; then
    pass "人员迁移生效（moved=1，落库 org_id=${NEW_ORG}）"
else
    fail "迁移失败：HTTP ${HTTP_CODE:-000} moved=${MOVED} org=${NEW_ORG}" \
        "POST /admin/orgs/{id}/move-users 必须在一个事务里改完所有人员，失败不留半迁移"
fi

# ---------------------------------------------------------------- 6. 迁移审计
AUDIT="$(db_q "SELECT COUNT(*) FROM audit_log WHERE action LIKE 'ORG_MOVE_USERS%'
      AND (detail_json LIKE '%${USER_ID}%' OR biz_id = ${ORG_ID})" | head -n1)"
if [[ "${AUDIT:-0}" -ge 1 ]]; then
    pass "迁移写审计（ORG_MOVE_USERS，命中 ${AUDIT} 条，含人员/目标组织）"
else
    fail "迁移无审计记录（ORG_MOVE_USERS）" \
        "AC-19：人员迁移是敏感动作，审计要能回答「谁把哪几个人从哪挪到哪」"
fi

# ---------------------------------------------------------------- 7. 迁移后可删
api_call DELETE "/api/req/admin/orgs/${ORG_ID}" >/dev/null
if [[ "${HTTP_CODE:-000}" == "200" && "$(db_q "SELECT COUNT(*) FROM sys_org WHERE id = ${ORG_ID}" | head -n1)" == "0" ]]; then
    pass "人员迁走后组织可删（HTTP 200，行已消失）"
    ORG_ID=""
else
    fail "空组织仍删不掉：HTTP ${HTTP_CODE:-000}" "409 的下一步动作必须真的能走通，否则用户卡死"
fi

# ---------------------------------------------------------------- 8. 组织树仍有根
ROOTS="$(db_q "SELECT COUNT(*) FROM sys_org WHERE parent_id IS NULL OR parent_id = 0" | head -n1)"
if [[ "${ROOTS:-0}" -ge 1 ]]; then
    pass "组织树仍有根节点（${ROOTS} 个），未把树删空"
else
    fail "组织树没有根节点了" "删组织必须留根；树空了之后人员无处归属，登录后所有列表都是空的"
fi

# ---------------------------------------------------------------- 汇总
if (( TOTAL == 0 )); then
    err "s6: 一条断言都没执行：脚本实现有 bug"
    exit 1
fi
if (( PASS != TOTAL )); then
    err "s6-user-org: ${FAIL} 项失败 / ${TOTAL} 项"
    printf '%s\n' "$FAILED_LINES" >&2
    report_counts
    exit 1
fi
report_counts
ok "s6-user-org: ${PASS}/${TOTAL} 项通过（角色/域差集 + 组织 409 + 迁移审计）"
