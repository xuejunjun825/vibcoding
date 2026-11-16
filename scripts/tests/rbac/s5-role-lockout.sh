#!/usr/bin/env bash
# s5-role-lockout.sh — 角色 CRUD 与防锁死（TECHNICAL §7、AC-9/AC-10/AC-11）
#
# 防锁死一旦漏掉，验收会留下一套「锁不回来」的库：管理员把自己停了、把最后一个能授权的角色删了。
# 因此本段除了正向 CRUD，重点在四个 409 和「停用角色即时失权但关系行仍在」。
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
        printf 's5 %s %s\n' "$PASS" "$TOTAL" >>"$RBAC_COUNTS"
    fi
}

CODE="S5PROBE_$RANDOM"
ROLE_ID=""
TMP_USER="s5u$RANDOM"
user_created=0
cleanup() {
    admin_login >/dev/null 2>&1 || true
    if [[ -n "${ROLE_ID}" ]]; then
        api_call PATCH "/api/req/admin/roles/${ROLE_ID}/status" '{"status":"ACTIVE"}' >/dev/null 2>&1 || true
        api_call DELETE "/api/req/admin/roles/${ROLE_ID}" >/dev/null 2>&1 || true
    fi
    if (( user_created )); then
        local uid
        uid="$(user_id_of "$TMP_USER")"
        [[ -n "${uid}" && "${uid}" != "null" ]] && api_call PATCH "/api/req/admin/users/${uid}/status" '{"status":"DISABLED"}' >/dev/null 2>&1
    fi
}
trap cleanup EXIT

log "s5：角色 CRUD 与防锁死（自定义角色 ${CODE}）"

admin_login || die "admin 登录失败：先 make platform-up && make platform-seed"

# ---------------------------------------------------------------- 1. 创建
api_call POST /api/req/admin/roles "{\"code\":\"${CODE}\",\"name\":\"s5 探针角色\",\"description\":\"门禁脚本创建\"}" >/dev/null
CREATED="${HTTP_CODE:-000}"
ROLE_ID="$(printf '%s' "$HTTP_BODY" | jq -r '.id // empty' 2>/dev/null)"
if [[ ( "${CREATED}" == "200" || "${CREATED}" == "201" ) && -n "${ROLE_ID}" ]]; then
    pass "创建自定义角色（HTTP ${CREATED}，id=${ROLE_ID}，builtin=false）"
else
    fail "创建自定义角色失败：HTTP ${CREATED} ${HTTP_BODY:0:120}" \
        "POST /admin/roles 需要 T4 已上线；code 正则与 name 长度见 TECHNICAL §7"
    report_counts
    exit 1
fi

# ---------------------------------------------------------------- 2. code 唯一
api_call POST /api/req/admin/roles "{\"code\":\"${CODE}\",\"name\":\"重复码\"}" >/dev/null
check2="$(printf '%s' "${HTTP_CODE:-000}|$(printf '%s' "$HTTP_BODY" | jq -r '.code // empty')")"
if [[ "${check2}" == "409|ROLE_CODE_TAKEN" ]]; then
    pass "重复 code ⇒ 409 ROLE_CODE_TAKEN"
else
    fail "重复 code 未拒绝：HTTP ${check2}" \
        "code 是授权行的 subject 之外的角色标识，重复会让审计与登录态指向错乱"
fi

# ---------------------------------------------------------------- 3. 改名
api_call PUT "/api/req/admin/roles/${ROLE_ID}" "{\"name\":\"s5 改名后\",\"description\":\"已改名\"}" >/dev/null
NAME_AFTER="$(printf '%s' "$HTTP_BODY" | jq -r '.name // empty')"
if [[ "${HTTP_CODE:-000}" == "200" && "${NAME_AFTER}" == "s5 改名后" ]]; then
    pass "编辑角色名称生效（name=s5 改名后）"
else
    fail "编辑角色失败：HTTP ${HTTP_CODE:-000} name=${NAME_AFTER}" \
        "PUT /admin/roles/{id} 只改 name/description：内置角色的 code 也不可改"
fi

# ---------------------------------------------------------------- 4. code 不可变
api_call PUT "/api/req/admin/roles/${ROLE_ID}" "{\"code\":\"S5CHANGED_${RANDOM}\",\"name\":\"x\"}" >/dev/null
if [[ "${HTTP_CODE:-000}" == "400" ]]; then
    pass "提交新 code ⇒ 400（code 不可变）"
else
    fail "改 code 未被拒绝：HTTP ${HTTP_CODE:-000}" \
        "允许改 code 等于换身份：已有授权行与审计全指向旧码"
fi

# ---------------------------------------------------------------- 5. 授权自定义角色
QA_ORIG="$(grants_of "$(role_id_of QA)")"
NEW_GRANTS="$(set_cell "$QA_ORIG" MENU "$RBAC_PAGE" ALLOW)"
NEW_GRANTS="$(set_cell "$NEW_GRANTS" ACTION "$RBAC_ACTION" ALLOW)"
PUTCODE="$(put_grants "$ROLE_ID" "$NEW_GRANTS")"
if [[ "${PUTCODE}" == "200" ]]; then
    pass "自定义角色可写授权（168 行全量覆盖）"
else
    fail "写授权失败：HTTP ${PUTCODE}" \
        "PUT /admin/roles/{id}/permissions 必须是全量覆盖，且对自定义角色同样有效"
fi

# ---------------------------------------------------------------- 6. 停用即时失权
api_call POST /api/req/admin/users "{\"username\":\"${TMP_USER}\",\"password\":\"Passw0rd!\",\"name\":\"s5 probe\",\"roles\":[\"${CODE}\"]}" >/dev/null
if [[ "${HTTP_CODE:-000}" == "200" || "${HTTP_CODE:-000}" == "201" ]]; then
    user_created=1
    pass "用户可被分配自定义角色"
else
    fail "创建探针用户失败：HTTP ${HTTP_CODE:-000}" "POST /admin/users 的 roles 需接受自定义角色码"
fi

if (( user_created )); then
    login_as "$TMP_USER" 'Passw0rd!' >/dev/null
    BEFORE="$(probe_settle allow)"
else
    BEFORE="000"
fi

# 停用必须由管理员发起：上一步的 login_as 已把 API_TOKEN 换成探针用户，
# 不切回来测到的就是「探针用户 403」而不是「停用是否即时失权」。
admin_login >/dev/null
api_call PATCH "/api/req/admin/roles/${ROLE_ID}/status" '{"status":"DISABLED"}' >/dev/null
DISABLED_CODE="${HTTP_CODE:-000}"
if [[ "${DISABLED_CODE}" == "200" ]]; then
    pass "停用自定义角色（HTTP 200，status=DISABLED）"
else
    fail "停用角色失败：HTTP ${DISABLED_CODE}" "PATCH /admin/roles/{id}/status 接受 ACTIVE|DISABLED"
fi

if (( user_created )); then
    login_as "$TMP_USER" 'Passw0rd!' >/dev/null
    AFTER="$(probe_settle 403)"
    ROWS="$(db_q "SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id
                    WHERE r.code = '${CODE}'" | head -n1)"
    if [[ "${BEFORE}" != "403" && "${AFTER}" == "403" && "${ROWS:-0}" -ge 1 ]]; then
        pass "角色停用后下一次请求即 403，且 sys_user_role 关系行仍在（${ROWS} 行）"
    else
        fail "停用未即时失权：before=${BEFORE} after=${AFTER} 关系行=${ROWS:-0}" \
            "AC-10：停用走 UserScopeResolver 的 r.status='ACTIVE' 过滤，不能靠缓存 TTL 生效"
    fi
fi

# ---------------------------------------------------------------- 7. 有用户不可删
admin_login >/dev/null   # 6 的前置探针把 token 换成了探针用户，管理面动作一律先切回管理员
api_call DELETE "/api/req/admin/roles/${ROLE_ID}" >/dev/null
DEL_IN_USE="${HTTP_CODE:-000}"
DEL_BODY="$(printf '%s' "$HTTP_BODY" | jq -r '.code // empty')"
if [[ "${DEL_IN_USE}" == "409" && "${DEL_BODY}" == "ROLE_IN_USE" ]]; then
    pass "删除仍有用户的角色 ⇒ 409 ROLE_IN_USE（带人数）"
else
    fail "删除在用角色未被阻止：HTTP ${DEL_IN_USE} code=${DEL_BODY}" \
        "ROLE_IN_USE 必须带 users 计数，前端据此提示「先改派」"
fi

# ---------------------------------------------------------------- 8. 内置角色不可删/停
admin_login >/dev/null
ADMIN_ID="$(role_id_of PLATFORM_ADMIN)"
api_call DELETE "/api/req/admin/roles/${ADMIN_ID}" >/dev/null
B1="$(printf '%s' "${HTTP_CODE:-000}|$(printf '%s' "$HTTP_BODY" | jq -r '.code // empty')")"
api_call PATCH "/api/req/admin/roles/${ADMIN_ID}/status" '{"status":"DISABLED"}' >/dev/null
B2="$(printf '%s' "${HTTP_CODE:-000}|$(printf '%s' "$HTTP_BODY" | jq -r '.code // empty')")"
if [[ "${B1}" == "409|BUILTIN_ROLE_IMMUTABLE" && "${B2}" == "409|BUILTIN_ROLE_IMMUTABLE" ]]; then
    pass "内置角色不可删、不可停（409 BUILTIN_ROLE_IMMUTABLE ×2）"
else
    fail "内置角色保护缺失：delete=${B1} disable=${B2}" \
        "内置角色是种子推导的基准（1848 行/角色 168 行），删了之后 demo-reset 也补不回来"
fi

# ---------------------------------------------------------------- 9. 防锁死：清空管理员
ADMIN_ALLOW_BEFORE="$(db_q "SELECT COUNT(*) FROM sys_role_permission p JOIN sys_role r ON r.id = p.role_id
      WHERE r.code = 'PLATFORM_ADMIN' AND p.access <> 'DENY'" | head -n1)"
ADMIN_ORIG="$(grants_of "$ADMIN_ID")"
EMPTY="$(printf '%s' "$ADMIN_ORIG" | jq -c 'map(.access = "DENY")')"
LOCK_RAW="$(put_grants_code "$ADMIN_ID" "$EMPTY")"
LOCK_CODE="${LOCK_RAW%%|*}"
LOCK_ERR="${LOCK_RAW#*|}"
ADMIN_ALLOW_AFTER="$(db_q "SELECT COUNT(*) FROM sys_role_permission p JOIN sys_role r ON r.id = p.role_id
      WHERE r.code = 'PLATFORM_ADMIN' AND p.access <> 'DENY'" | head -n1)"
if [[ "${LOCK_CODE}" == "409" && "${LOCK_ERR}" == "ADMIN_LOCKOUT_RISK" &&
      "${ADMIN_ALLOW_BEFORE}" == "${ADMIN_ALLOW_AFTER}" ]]; then
    pass "清空 PLATFORM_ADMIN 授权 ⇒ 409 ADMIN_LOCKOUT_RISK 且库不变（${ADMIN_ALLOW_AFTER} 个非 DENY）"
else
    fail "防锁死失效：HTTP ${LOCK_CODE} code=${LOCK_ERR} 非DENY ${ADMIN_ALLOW_BEFORE}→${ADMIN_ALLOW_AFTER}" \
        "AC-11：冻结管理员两码；409 必须在事务内回滚，不留半套授权"
fi

# ---------------------------------------------------------------- 10. 删除干净角色
api_call PATCH "/api/req/admin/roles/${ROLE_ID}/status" '{"status":"ACTIVE"}' >/dev/null
if (( user_created )); then
    TMP_UID="$(user_id_of "$TMP_USER")"
    [[ -n "${TMP_UID}" && "${TMP_UID}" != "null" ]] && api_call PATCH "/api/req/admin/users/${TMP_UID}/status" '{"status":"DISABLED"}' >/dev/null
    db_q "DELETE FROM sys_user_role WHERE role_id = ${ROLE_ID}" >/dev/null
fi
DEL_CODE="$(api_call DELETE "/api/req/admin/roles/${ROLE_ID}" >/dev/null; printf '%s' "${HTTP_CODE:-000}")"
LEFT_ROWS="$(db_q "SELECT COUNT(*) FROM sys_role_permission WHERE role_id = ${ROLE_ID}" | head -n1)"
LEFT_ROLE="$(db_q "SELECT COUNT(*) FROM sys_role WHERE id = ${ROLE_ID}" | head -n1)"
if [[ ( "${DEL_CODE}" == "200" || "${DEL_CODE}" == "204" ) && "${LEFT_ROWS}" == "0" && "${LEFT_ROLE}" == "0" ]]; then
    pass "删除自定义角色 ⇒ 角色与其 168 行授权一起清掉"
    ROLE_ID=""
else
    fail "删除角色不彻底：HTTP ${DEL_CODE} 角色残留=${LEFT_ROLE} 授权残留=${LEFT_ROWS}" \
        "删角色必须级联删 sys_role_permission，否则 uk_rp 会把新角色挡在外面"
fi

# ---------------------------------------------------------------- 汇总
if (( TOTAL == 0 )); then
    err "s5: 一条断言都没执行：脚本实现有 bug"
    exit 1
fi
if (( PASS != TOTAL )); then
    err "s5-role-lockout: ${FAIL} 项失败 / ${TOTAL} 项"
    printf '%s\n' "$FAILED_LINES" >&2
    report_counts
    exit 1
fi
report_counts
ok "s5-role-lockout: ${PASS}/${TOTAL} 项通过（CRUD + 四个 409 + 停用即时失权）"
