#!/usr/bin/env bash
# access-verify.sh — 角色×页面×菜单准入验收（20261110-r7 P0 修复的门禁）
#
# 五段按关注点分段，失败能定位到「字典契约 / 隔离 / 页面矩阵 / 导航声明 / 菜单可见集」：
#   a0 字典契约（29）· a1 租户与厂商隔离（8）· a2 5 页 × 7 角色精确状态码（35）
#   a3 导航静态审计（8）· a4 7 角色真实权限 → 菜单可见集（14）
# 聚合器只做编排：各段自己打印断言明细并写一行计数到 ${ACCESS_COUNTS}，
# 这里求和后做 PASS==TOTAL、五段齐全、总数 ≥45（PRD AC-16 下限）三道守卫。
#
# 用法：
#   ./scripts/access-verify.sh            # 全部五段（需 make platform-up + platform-seed）
#   ./scripts/access-verify.sh a0 a2      # 只跑指定段（调试用，仍要求栈就绪）
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

ACCESS_DIR="$ROOT/scripts/tests/access"
ALL_SEGMENTS=(a0 a1 a2 a3 a4)

SELECTED=()
if (( $# > 0 )); then
    for want in "$@"; do
        found=""
        for s in "${ALL_SEGMENTS[@]}"; do
            [[ "$want" == "$s" || "$want" == "${s}-"* ]] && found="$s"
        done
        [[ -n "$found" ]] || die "未知段: ${want}（可选: ${ALL_SEGMENTS[*]}）"
        SELECTED+=("$found")
    done
else
    SELECTED=("${ALL_SEGMENTS[@]}")
fi

segment_cmd() {
    case "$1" in
        a0|a1|a2) printf 'bash %s/%s-*.sh' "$ACCESS_DIR" "$1" ;;
        a3|a4)    printf 'node %s/%s-*.mjs' "$ACCESS_DIR" "$1" ;;
    esac
}
segment_file() {
    case "$1" in
        a0) printf '%s/a0-dictionary-contract.sh' "$ACCESS_DIR" ;;
        a1) printf '%s/a1-isolation.sh' "$ACCESS_DIR" ;;
        a2) printf '%s/a2-page-matrix.sh' "$ACCESS_DIR" ;;
        a3) printf '%s/a3-nav-audit.mjs' "$ACCESS_DIR" ;;
        a4) printf '%s/a4-nav-visibility.mjs' "$ACCESS_DIR" ;;
    esac
}

printf '\033[1;34m角色×页面准入验收（20261110-r7）：%d 段\033[0m\n' "${#SELECTED[@]}"
printf '  req %s · web %s · 网关 %s\n' "$REQ_URL" "$WEB_URL" "$GATEWAY_URL"

# ---- 0. 栈就绪：栈没起就断言，只会得到一串误导性的 401/000 ----
req_ready || die "平台栈未就绪：req-service（${REQ_URL}）不是 READY —— 先执行 make platform-up"
web_up || die "平台栈未就绪：web（${WEB_URL}）未响应 —— 先执行 make platform-up"
LOGIN_PROBE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 6 \
    -X POST "${GATEWAY_URL}/api/req/auth/login" -H 'Content-Type: application/json' \
    -d '{"tenantCode":"t1","username":"admin","password":"Admin@12345"}' 2>/dev/null || true)"
[[ "$LOGIN_PROBE" == "200" ]] || \
    die "平台栈未就绪：网关登录探针 HTTP ${LOGIN_PROBE}（期望 200）—— 先执行 make platform-up && make platform-seed"

# ---- 1. 夹具事实：厂商账号名单（登录响应不带 vendorId，只能从 DB 取权威口径）----
TID="$(db_q "SELECT id FROM sys_tenant WHERE code='t1' LIMIT 1")"
if [[ -n "$TID" ]]; then
    A4_VENDOR_USERS="$(db_q "SELECT username FROM sys_user WHERE tenant_id=${TID} AND vendor_id IS NOT NULL AND status='ACTIVE' ORDER BY id" | paste -sd, -)"
fi
[[ -n "${A4_VENDOR_USERS:-}" ]] || A4_VENDOR_USERS="vendor1"
export A4_VENDOR_USERS
export GATEWAY_URL
printf '  夹具：租户 %s · 厂商账号 %s\n' "${TID:-?}" "$A4_VENDOR_USERS"

COUNTS_FILE="$(mktemp "${TMPDIR:-/tmp}/access-counts.XXXXXX")"
trap 'rm -f "$COUNTS_FILE"' EXIT
export ACCESS_COUNTS="$COUNTS_FILE"

FAILED_SEGMENTS=()
for seg in "${SELECTED[@]}"; do
    file="$(segment_file "$seg")"
    [[ -f "$file" ]] || { err "缺少脚本: ${file}"; FAILED_SEGMENTS+=("${seg}(缺失)"); continue; }
    printf '\n\033[1;35m────────── %s ──────────\033[0m\n' "$(basename "$file")"
    start=$(date +%s)
    if [[ "$seg" == a3 || "$seg" == a4 ]]; then
        node "$file"; rc=$?
    else
        bash "$file"; rc=$?
    fi
    secs=$(( $(date +%s) - start ))
    printf '  (%s 用时 %ss)\n' "$seg" "$secs"
    (( rc == 0 )) || FAILED_SEGMENTS+=("$seg")
done

# ---- 2. 汇总：每段必须留下 "aN pass total" 一行，缺失 = 该段中途死了 ----
TOTAL=0; PASS=0
MISSING=()
for seg in "${SELECTED[@]}"; do
    line="$(grep -E "^${seg} [0-9]+ [0-9]+\$" "$COUNTS_FILE" 2>/dev/null | tail -n1 || true)"
    if [[ -z "$line" ]]; then MISSING+=("$seg"); continue; fi
    read -r _ p t <<<"$line"
    PASS=$(( PASS + p )); TOTAL=$(( TOTAL + t ))
    printf '  %s: %s/%s\n' "$seg" "$p" "$t"
done

printf '\n'
(( ${#MISSING[@]} == 0 )) || err "以下段没有产出计数（脚本中途失败）: ${MISSING[*]}"
(( ${#FAILED_SEGMENTS[@]} == 0 )) || err "失败段: ${FAILED_SEGMENTS[*]}"

# 45 条是「全量五段」的下限；调试子集（如 a0 a3）不适用，否则调试永远红
if (( ${#SELECTED[@]} == ${#ALL_SEGMENTS[@]} && TOTAL < 45 )); then
    err "断言总数 ${TOTAL} < 45（PRD AC-16 下限）：有段没跑起来，或断言被删"
    exit 1
fi
if (( PASS != TOTAL )); then
    err "准入验收未通过：PASS ${PASS} != TOTAL ${TOTAL}"
    exit 1
fi
if (( ${#MISSING[@]} > 0 || ${#FAILED_SEGMENTS[@]} > 0 )); then
    exit 1
fi

ok "角色×页面准入验收通过：${PASS}/${TOTAL} 项（字典契约 / 隔离 / 页面矩阵 / 导航声明 / 菜单可见集）"
