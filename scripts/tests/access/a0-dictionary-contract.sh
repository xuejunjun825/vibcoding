#!/usr/bin/env bash
# a0-dictionary-contract.sh — 字典端点契约（PRD AC-1/AC-2/AC-3/AC-4，TECHNICAL §8.1 a0，29 断言）
#
# 为什么先测契约：页面矩阵（a2）与导航（a3/a4）都建立在「字典读得到」之上。
# 端点字段一多、一少，或管理面被顺手放开，后面所有断言都会在错误的前提下变绿。
# 断言分四组：
#   1) 未登录调三个字典端点 → 401（不是 403，也不是 200）
#   2) 7 角色 × 3 端点 → 200（字典是登录可读，不是管理面）
#   3) 三个端点的 JSON 键集合 == 字段白名单（record 形状），且非空
#   4) 未知子路径 → 404；vendor1 调 /admin/domains 仍 403（隔离没被字典放开）
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/lib.sh"

TOTAL=0
PASS=0
FAIL=0
FAILED_LINES=""
T1_TENANT="${A0_TENANT:-t1}"
FIX_HINT="先执行 make platform-up && make platform-seed"

section() { printf '\n\033[1;34m---- %s ----\033[0m\n' "$*"; }

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

# expect_code <desc> <want> [fix]
expect_code() {
    local desc="$1" want="$2" fix="${3:-$FIX_HINT}"
    if [[ "$HTTP_CODE" == "$want" ]]; then
        pass "$desc"
    else
        fail "$desc（期望 ${want}，实际 ${HTTP_CODE}: $(printf '%s' "$HTTP_BODY" | head -c 220)）" "$fix"
    fi
}

# expect_jq <desc> <filter> <expected> [fix]
expect_jq() {
    local desc="$1" filter="$2" want="$3" fix="${4:-$FIX_HINT}"
    local got; got="$(jget "$HTTP_BODY" "$filter")"
    if [[ "$got" == "$want" ]]; then
        pass "$desc"
    else
        fail "$desc（期望 ${want}，实际 ${got:-<空>}: $(printf '%s' "$HTTP_BODY" | head -c 220)）" "$fix"
    fi
}

# 把本段计数交给访问门禁聚合器（它只求和，不解析 stdout）
report_counts() {
    if [[ -n "${ACCESS_COUNTS:-}" ]]; then
        printf 'a0 %s %s\n' "$PASS" "$TOTAL" >>"$ACCESS_COUNTS"
    fi
}

DICT_ENDPOINTS=(
    "/api/req/dictionaries/domains"
    "/api/req/dictionaries/vendors"
    "/api/req/dictionaries/users"
)
DICT_NAMES=(domains vendors users)

# =====================================================================
section "1/4 未登录调字典：必须 401（仅认证，不匿名放行）"
API_TOKEN=""
API_TENANT=""
for i in "${!DICT_ENDPOINTS[@]}"; do
    api_call GET "${DICT_ENDPOINTS[$i]}"
    expect_code "未登录 GET ${DICT_ENDPOINTS[$i]} → 401" 401 \
        "字典端点只要求认证：检查 SecurityConfig 是否把它放进了白名单（AC-1）"
done

# =====================================================================
section "2/4 7 角色 × 3 端点：登录即可读（21 条）"
ROLE_LIST="admin:Admin@12345 pm:Passw0rd! tl1:Passw0rd! po1:Passw0rd! dev1:Passw0rd! qa1:Passw0rd! vendor1:Passw0rd!"
for spec in $ROLE_LIST; do
    role="${spec%%:*}"
    role_pass="${spec#*:}"
    platform_login "$T1_TENANT" "$role" "$role_pass" >/dev/null || die "角色 ${role} 登录失败（先 make platform-seed）"
    for i in "${!DICT_ENDPOINTS[@]}"; do
        api_call GET "${DICT_ENDPOINTS[$i]}"
        expect_code "${role} GET ${DICT_ENDPOINTS[$i]} → 200" 200 \
            "字典不该受 org:manage 把守：检查 PermissionRouteTable 是否无人给它加规则（AC-2）"
    done
done

# =====================================================================
section "3/4 字段白名单：键集合 == record 形状，且非空"
DICT_KEY_FILTER='if length == 0 then "EMPTY" else ([.[] | (keys | sort | join(","))] | unique | join(";")) end'
API_TOKEN=""
API_TENANT=""
platform_login "$T1_TENANT" admin "Admin@12345" >/dev/null || die "admin 登录失败（先 make platform-seed）"

api_call GET /api/req/dictionaries/domains
expect_jq "domains 每条仅 {code,id,name}（DictionaryViews.DomainOption）" "$DICT_KEY_FILTER" "code,id,name" \
    "字段白名单靠 record 形状维护：多返回一个 enabled 就是白名单破了（AC-3）"

api_call GET /api/req/dictionaries/vendors
expect_jq "vendors 每条仅 {code,id,name}（DictionaryViews.VendorOption）" "$DICT_KEY_FILTER" "code,id,name" \
    "厂商字典不得携带 status/contact 等管理字段（AC-3）"

api_call GET /api/req/dictionaries/users
expect_jq "users 每条仅 {id,name,username}（DictionaryViews.UserOption）" "$DICT_KEY_FILTER" "id,name,username" \
    "人员字典不得携带 roles/vendorId/domainIds 等管理字段（AC-3）"

# =====================================================================
section "4/4 负向：未知子路径 404；vendor1 调管理面仍 403"
api_call GET /api/req/dictionaries/not-a-real-subpath
expect_code "admin GET /dictionaries/not-a-real-subpath → 404" 404 \
    "未知子路径必须是 404，不能落到某个默认处理方法把管理数据吐出来"

platform_login "$T1_TENANT" vendor1 "Passw0rd!" >/dev/null || die "vendor1 登录失败（先 make platform-seed）"
api_call GET /api/req/admin/domains
expect_code "vendor1 GET /admin/domains → 403（管理面没被字典放开）" 403 \
    "字典是新增只读面，不应改变 /admin/* 的 org:manage 边界（AC-4）"

# =====================================================================
printf '\n'
if (( TOTAL == 0 )); then
    err "a0: 一条断言都没执行：脚本实现有 bug"
    exit 1
fi
if (( PASS != TOTAL )); then
    err "a0-dictionary-contract: ${FAIL} 项失败 / ${TOTAL} 项"
    printf '%s\n' "$FAILED_LINES" >&2
    err "修复: 从第一条 FAIL 开始；字典契约错了，后面页面矩阵没有意义"
    report_counts
    exit 1
fi
report_counts
ok "a0-dictionary-contract: ${PASS}/${TOTAL} 项通过（仅认证 + 字段白名单 + 管理面不松）"
