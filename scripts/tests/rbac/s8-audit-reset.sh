#!/usr/bin/env bash
# s8-audit-reset.sh — 审计可追溯 + 演示重置幂等（TECHNICAL §3.4、AC-19/AC-4）
#
# 审计与重置是运营可信度的底线：出了事要能回答「谁在什么时候把什么改成了什么」，
# 演砸了要能一键回到预设且**回到同一个预设**（两次 reset 的计数必须逐项相等）。
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
        printf 's8 %s %s\n' "$PASS" "$TOTAL" >>"$RBAC_COUNTS"
    fi
}

RBAC_RUN_TAG="$(rbac_run_tag)"   # 全数字 tag：同 tag 重跑可被 RBAC_RUN_TAG 钉住（QA 撞名复现）
TMP_ROLE="S8ROLE_${RBAC_RUN_TAG}"
ROLE_ID=""
TMP_USER="s8u${RBAC_RUN_TAG}"
ADMIN_ID=""     # 在 cleanup 之前先声明：set -u 下 trap 可能在赋值前就触发
ADMIN_ORIG=""
ADMIN_MUTATED=0 # 只要本段把 admin 的占位格子改脏了就是 1；reset 成功或 cleanup 还原后归 0
# r36 之前本段完全没有 cleanup：它自己会调两次 /internal/demo-reset，reset 把自定义角色删掉、
# 把临时号**停用**而不是删除，于是每跑一轮就多一个 DISABLED 的 s8u<tag>（实测 85 条）。
# 补上 trap 之后整段跑完库面干净——这一点正是 gate-order 里 rbac 必须排在序列末尾的原因。
cleanup() {
    admin_login >/dev/null 2>&1 \
        || { err "s8 cleanup: admin 登录失败：先 make platform-up && make platform-seed"; exit 1; }
    # 「还原授权」这半截留在段内（清理原语只管删主体）：本段把 PLATFORM_ADMIN 的 audit:view 改成
    # DENY 再靠 /internal/demo-reset 还原；若中途退出，reset 没跑到，这一格就留在 DENY 上。
    # 只在本段确实改脏过（ADMIN_MUTATED=1）时补回起跑快照——否则会反过来把「起跑前就脏」的状态
    # 锁死在库里，把一次意外变成永久漂移。没差异时不写库（服务端 no-diff 不记审计）。
    if (( ADMIN_MUTATED )) && [[ -n "${ADMIN_ID}" && -n "${ADMIN_ORIG}" ]] \
        && [[ "$(printf '%s' "${ADMIN_ORIG}" | jq 'if type=="array" then length else 0 end' 2>/dev/null)" == "180" ]]; then
        CUR="$(grants_of "${ADMIN_ID}")"
        if [[ "${CUR}" != "${ADMIN_ORIG}" ]]; then
            RESTORE_CODE="$(put_grants "${ADMIN_ID}" "${ADMIN_ORIG}")"
            [[ "${RESTORE_CODE}" == "200" ]] \
                || { err "s8 cleanup: 还原 PLATFORM_ADMIN 授权失败（HTTP ${RESTORE_CODE}，audit:view 可能留在 DENY）"; exit 1; }
        fi
    fi
    rbac_purge_users s8 >/dev/null \
        || { err "s8 cleanup: 删临时用户失败（DB 不可读或写被拒）：先 make platform-up"; exit 1; }
    rbac_purge_roles S8 >/dev/null \
        || { err "s8 cleanup: 删临时角色失败（409=还有用户挂着 S8*，说明用户没删干净）"; exit 1; }
}
trap cleanup EXIT
trap 'exit 130' INT TERM

log "s8：审计可追溯 + reset 幂等"

admin_login || die "admin 登录失败：先 make platform-up && make platform-seed"

# 起跑自愈：上一轮被 kill -9（EXIT trap 不执行）或 QA 固定 RBAC_RUN_TAG 连跑两次时，
# 同名 S8ROLE_* 会让下面建角色直接 409，随后 ROLE_ID 为空、整套审计断言红在无关的地方。
rbac_purge_users s8 >/dev/null \
    || { err "s8: 起跑清理临时用户失败：先 make platform-up"; report_counts; exit 1; }
rbac_purge_roles S8 >/dev/null \
    || { err "s8: 起跑清理临时角色失败：库里有用户挂着 S8* 或服务不可达"; report_counts; exit 1; }
ADMIN_ID="$(role_id_of PLATFORM_ADMIN)"
ADMIN_ORIG="$(grants_of "$ADMIN_ID")"

# ---------------------------------------------------------------- 1. 角色审计
R0="$(audit_count ROLE_)"
api_call POST /api/req/admin/roles "{\"code\":\"${TMP_ROLE}\",\"name\":\"s8 审计探针\",\"description\":\"before\"}" >/dev/null
CREATE_CODE="${HTTP_CODE:-000}"
ROLE_ID="$(printf '%s' "$HTTP_BODY" | jq -r '.id // empty')"
# 建主体失败必须当场红：否则 ROLE_ID 为空，后面每条审计断言的失败原因都指向「审计缺失」，
# 与真因（409 撞名 / 401 未登录 / 服务没起）隔着好几步 —— s7 的 403 USER_DISABLED 就是这么绕出来的。
case "${CREATE_CODE}" in
    200|201) ;;
    *)  err "s8: 建临时角色 ${TMP_ROLE} 失败：HTTP ${CREATE_CODE} ${HTTP_BODY:0:120}（先 make platform-up，并确认没有同名残留）"
        report_counts; exit 1 ;;
esac
api_call PUT "/api/req/admin/roles/${ROLE_ID}" '{"name":"s8 审计探针 v2","description":"after"}' >/dev/null
R1="$(audit_count ROLE_)"
DETAIL="$(db_q "SELECT COUNT(*) FROM audit_log WHERE action LIKE 'ROLE_%'
      AND detail_json LIKE '%before%' AND detail_json LIKE '%after%'" | head -n1)"
if [[ "${R1:-0}" -gt "${R0:-0}" && "${DETAIL:-0}" -ge 1 ]]; then
    pass "角色创建/改名各写 1 条 ROLE_ 审计，detail_json 含 before/after（${R0:-0}→${R1:-0}）"
else
    fail "角色审计缺失或不可解析：${R0:-0}→${R1:-0}，含 before/after 的 ${DETAIL:-0} 条" \
        "AC-19：四类变更都要有 before/after；只写「改了什么」回答不了「改成了什么」"
fi

# ---------------------------------------------------------------- 2. 授权审计
G0="$(audit_count ROLE_PERMISSION)"
CHANGED="$(set_cell "$ADMIN_ORIG" FUNCTION audit:view DENY)"
put_grants "$ADMIN_ID" "$CHANGED" >/dev/null
ADMIN_MUTATED=1
G1="$(audit_count ROLE_PERMISSION)"
HIGH_RISK="$(db_q "SELECT COUNT(*) FROM audit_log WHERE action LIKE 'ROLE_PERMISSION%'
      AND JSON_EXTRACT(detail_json, '$.highRiskGranted') IS NOT NULL" | head -n1)"
if [[ "${G1:-0}" -eq $(( ${G0:-0} + 1 )) && "${HIGH_RISK:-0}" -ge 1 ]]; then
    pass "一次授权保存 = 一条 ROLE_PERMISSION_UPDATE，且 detail_json 带 highRiskGranted"
else
    fail "授权审计不符合「一次保存一条 + highRiskGranted」：${G0:-0}→${G1:-0}，带字段 ${HIGH_RISK:-0}" \
        "全量覆盖写 1980 行不能写 1980 条审计；高危新增必须留痕"
fi

# ---------------------------------------------------------------- 3. 无变更不写审计
G2="$(audit_count ROLE_PERMISSION)"
put_grants "$ADMIN_ID" "$CHANGED" >/dev/null
G3="$(audit_count ROLE_PERMISSION)"
if [[ "${G3:-0}" -eq "${G2:-0}" ]]; then
    pass "无差异重复保存不写审计（保持 ${G3:-0} 条）"
else
    fail "无差异保存仍写审计：${G2:-0}→${G3:-0}" \
        "repeat save 是常见操作（连点两次保存），每次都写会把审计页刷成噪声"
fi

# ---------------------------------------------------------------- 4. 用户审计
api_call POST /api/req/admin/users "{\"username\":\"${TMP_USER}\",\"password\":\"Passw0rd!\",\"name\":\"s8 user\",\"roles\":[\"QA\"]}" >/dev/null
USER_CODE="${HTTP_CODE:-000}"
TMP_UID="$(printf '%s' "$HTTP_BODY" | jq -r '.id // empty')"
case "${USER_CODE}" in
    200|201) ;;
    *)  err "s8: 建临时账号 ${TMP_USER} 失败：HTTP ${USER_CODE} ${HTTP_BODY:0:120}（先 make platform-up，并确认没有同名残留）"
        report_counts; exit 1 ;;
esac
U0="$(audit_count USER_)"
api_call PUT /api/req/admin/users "{\"id\":${TMP_UID},\"name\":\"s8 user\",\"roles\":[\"QA\",\"PMO\"]}" >/dev/null
U1="$(audit_count USER_)"
if [[ "${U1:-0}" -gt "${U0:-0}" ]]; then
    pass "用户角色变更写 USER_ 审计（${U0:-0}→${U1:-0}）"
else
    fail "用户授权变更无审计：${U0:-0}→${U1:-0}" \
        "「谁给了这个人这个角色」是事后追责的第一问"
fi

# ---------------------------------------------------------------- 5. 审计过滤维度
BIZ="$(db_q "SELECT COUNT(*) FROM audit_log WHERE biz_type IN ('ROLE','USER','ORG')" | head -n1)"
if [[ "${BIZ:-0}" -ge 3 ]]; then
    pass "审计可按 biz_type ∈ {ROLE,USER,ORG} 过滤（${BIZ} 条覆盖三类）"
else
    fail "biz_type 覆盖不足：只有 ${BIZ:-0} 条在 ROLE/USER/ORG 之内" \
        "审计页的过滤器直接吃 biz_type；写成 'role' 或空值会让筛选结果永远为空"
fi

# ---------------------------------------------------------------- 6. reset 恢复预设
put_grants "$ADMIN_ID" "$(set_cell "$ADMIN_ORIG" FUNCTION audit:view DENY)" >/dev/null
ADMIN_MUTATED=1
BEFORE_RESET="$(db_q "SELECT access FROM sys_role_permission p JOIN sys_role r ON r.id = p.role_id
      WHERE r.code = 'PLATFORM_ADMIN' AND p.subject_type = 'FUNCTION' AND p.subject_code = 'audit:view'" | head -n1)"
internal_call POST "$REQ_URL" /internal/demo-reset >/dev/null
RESET_CODE="${HTTP_CODE:-000}"
AFTER_RESET="$(db_q "SELECT access FROM sys_role_permission p JOIN sys_role r ON r.id = p.role_id
      WHERE r.code = 'PLATFORM_ADMIN' AND p.subject_type = 'FUNCTION' AND p.subject_code = 'audit:view'" | head -n1)"
[[ "${RESET_CODE}" == "200" ]] && ADMIN_MUTATED=0   # reset 已经把它还原了，cleanup 不必再补
if [[ "${RESET_CODE}" == "200" && "${BEFORE_RESET}" == "DENY" && "${AFTER_RESET}" == "ALLOW" ]]; then
    pass "reset 把被改过的单元恢复到预设（audit:view ${BEFORE_RESET} → ${AFTER_RESET}）"
else
    fail "reset 未恢复预设：HTTP ${RESET_CODE}，audit:view ${BEFORE_RESET:-<空>}→${AFTER_RESET:-<空>}" \
        "重置 = 回到 r17 的行为基线；只清自定义角色不动内置角色，等于没重置"
fi

# ---------------------------------------------------------------- 7. 幂等两次
snapshot() { # 计数快照：两次 reset 必须逐项相等（按演示租户统计：重置只动这一个租户）
    # 按租户过滤而不是数全库：r24 起每个租户都有 11 个内置角色（52 个租户 = 572 行），
    # 全库计数会把「别的租户没被重置」误读成「内置角色不止 11 个」。
    db_q "SELECT CONCAT(
            (SELECT COUNT(*) FROM sys_role_permission p JOIN sys_role r ON r.id = p.role_id
              WHERE r.tenant_id = (SELECT id FROM sys_tenant WHERE code = 't1')),
            '/', (SELECT COUNT(*) FROM sys_menu),
            '/', (SELECT COUNT(*) FROM sys_action),
            '/', (SELECT COUNT(*) FROM sys_role WHERE builtin = 1
              AND tenant_id = (SELECT id FROM sys_tenant WHERE code = 't1')),
            '/', (SELECT COUNT(*) FROM sys_role WHERE builtin = 0
              AND tenant_id = (SELECT id FROM sys_tenant WHERE code = 't1')))" | head -n1
}
S1="$(snapshot)"
internal_call POST "$REQ_URL" /internal/demo-reset >/dev/null
S2="$(snapshot)"
if [[ "${S1}" == "${S2}" && "${S2}" =~ ^[0-9]+/38/81/11/0$ ]]; then
    pass "两次 reset 计数逐项相等（role_permission/sys_menu/sys_action/builtin/自定义 = ${S2}）"
else
    fail "reset 不幂等：${S1:-<空>} vs ${S2:-<空>}" \
        "AC-4：自定义角色必须清 0、内置 11 行保留、1980 行回到预设"
fi

# ---------------------------------------------------------------- 汇总
if (( TOTAL == 0 )); then
    err "s8: 一条断言都没执行：脚本实现有 bug"
    exit 1
fi
if (( PASS != TOTAL )); then
    err "s8-audit-reset: ${FAIL} 项失败 / ${TOTAL} 项"
    printf '%s\n' "$FAILED_LINES" >&2
    report_counts
    exit 1
fi
report_counts
ok "s8-audit-reset: ${PASS}/${TOTAL} 项通过（四类审计 + reset 幂等）"
