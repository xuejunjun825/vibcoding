#!/usr/bin/env bash
# access-verify.sh — 角色×页面×菜单准入验收（20261110-r7 P0 修复的门禁；r13 扩到 143）
#
# 六段按关注点分段，失败能定位到「字典契约 / 隔离 / 页面矩阵 / 导航声明 / 菜单可见集 / 能力探测」：
#   a0 字典契约（29）· a1 租户与厂商隔离（8）· a2 9 页 × 7 角色精确状态码（63）
#   a3 导航静态审计（9）· a4 7 角色真实权限 → 菜单可见集（14）
#   a5 可选能力探测：7 账号 × 2 端点 + 调用点静态审计 + 审计器自检（22）
# 聚合器只做编排：各段自己打印断言明细并写一行计数到 ${ACCESS_COUNTS}，
# 这里求和后做 PASS==TOTAL、六段齐全、总数 ≥143（r13 AC-17 下限）、分段下限四道守卫。
# 注：AC-17 的 143 = 29+8+63+9+14+20 是按 a5 的**下限 20** 算的，而 a5 实际产出 22 条，
# 所以真实总数是 145；下限仍取 143，绝不为凑数字去删 a5 的断言（AC-17 明令不得删项放宽）。
# 分段下限比总数更早定位「哪一段掉了几条」；只对本次实际跑过的段生效（调试子集不误伤）。
#
# 用法：
#   ./scripts/access-verify.sh            # 全部六段（需 make platform-up + platform-seed）
#   ./scripts/access-verify.sh a0 a2      # 只跑指定段（调试用，仍要求栈就绪）
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

ACCESS_DIR="$ROOT/scripts/tests/access"
ALL_SEGMENTS=(a0 a1 a2 a3 a4 a5)

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
        a3|a4|a5) printf 'node %s/%s-*.mjs' "$ACCESS_DIR" "$1" ;;
    esac
}
segment_file() {
    case "$1" in
        a0) printf '%s/a0-dictionary-contract.sh' "$ACCESS_DIR" ;;
        a1) printf '%s/a1-isolation.sh' "$ACCESS_DIR" ;;
        a2) printf '%s/a2-page-matrix.sh' "$ACCESS_DIR" ;;
        a3) printf '%s/a3-nav-audit.mjs' "$ACCESS_DIR" ;;
        a4) printf '%s/a4-nav-visibility.mjs' "$ACCESS_DIR" ;;
        a5) printf '%s/a5-capability-probe.mjs' "$ACCESS_DIR" ;;
    esac
}

# 分段下限（TECHNICAL §8.3）：总数守卫看得见「少了」，分段下限直接指出「哪段少了」。
# 这里用 case 而不是 declare -A：macOS 自带 bash 3.2 没有关联数组，聚合器必须在
# 默认 shell 下就能跑（a0–a4 的既有断言数一条不减少，a5 下限 20）。
segment_min() {
    case "$1" in
        a0) printf 29 ;;
        a1) printf 8 ;;
        a2) printf 63 ;;
        a3) printf 9 ;;
        a4) printf 14 ;;
        a5) printf 20 ;;
        *)  printf 0 ;;
    esac
}

printf '\033[1;34m角色×页面准入验收（20261110-r7 / r8 a5）：%d 段\033[0m\n' "${#SELECTED[@]}"
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
    if [[ "$seg" == a3 || "$seg" == a4 || "$seg" == a5 ]]; then
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
SEG_BELOW=()
for seg in "${SELECTED[@]}"; do
    line="$(grep -E "^${seg} [0-9]+ [0-9]+\$" "$COUNTS_FILE" 2>/dev/null | tail -n1 || true)"
    if [[ -z "$line" ]]; then MISSING+=("$seg"); continue; fi
    read -r _ p t <<<"$line"
    PASS=$(( PASS + p )); TOTAL=$(( TOTAL + t ))
    min="$(segment_min "$seg")"
    printf '  %s: %s/%s（下限 %s）\n' "$seg" "$p" "$t" "$min"
    if (( t < min )); then
        SEG_BELOW+=("${seg}(${t}<${min})")
    fi
done

printf '\n'
(( ${#SEG_BELOW[@]} == 0 )) || err "以下段断言数低于下限（该段被删断言或中途失败）: ${SEG_BELOW[*]}"
(( ${#MISSING[@]} == 0 )) || err "以下段没有产出计数（脚本中途失败）: ${MISSING[*]}"
(( ${#FAILED_SEGMENTS[@]} == 0 )) || err "失败段: ${FAILED_SEGMENTS[*]}"

# 143 条是「全量六段」的下限（r13 AC-17：29+8+63+9+14+20；a5 实际 22 → 真实 145）；调试子集（如 a0 a3）不适用，否则调试永远红
if (( ${#SELECTED[@]} == ${#ALL_SEGMENTS[@]} && TOTAL < 143 )); then
    err "断言总数 ${TOTAL} < 143（r13 AC-17 下限）：有段没跑起来，或断言被删（a0-a5 一条都不许减少）"
    exit 1
fi
if (( PASS != TOTAL )); then
    err "准入验收未通过：PASS ${PASS} != TOTAL ${TOTAL}"
    exit 1
fi
if (( ${#MISSING[@]} > 0 || ${#FAILED_SEGMENTS[@]} > 0 )); then
    exit 1
fi

ok "角色×页面准入验收通过：${PASS}/${TOTAL} 项（字典契约 / 隔离 / 页面矩阵 / 导航声明 / 菜单可见集 / 能力探测）"
