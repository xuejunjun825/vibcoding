#!/usr/bin/env bash
# a1-isolation.sh — 字典的租户/厂商裁剪（PRD AC-2、§7 隔离；TECHNICAL §8.1 a1，8 断言）
#
# 字典是最容易漏掉行级边界的「新读面」：它绕过了业务表的 DataScope SQL，
# 如果只按 tenant_id 过滤，厂商账号就会看到别家厂商和全公司人员。
# 断言分三组：域边界（⊆ 启用集）、厂商边界（== 自己 / == 本厂商人员）、
# 租户边界（人员集合不跨租户、X-Tenant-Id 不符必须 403）。
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/lib.sh"

TOTAL=0
PASS=0
FAIL=0
FAILED_LINES=""
T1_TENANT="${A1_TENANT:-t1}"
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

# expect_eq <desc> <got> <want> [fix]
expect_eq() {
    local desc="$1" got="$2" want="$3" fix="${4:-$FIX_HINT}"
    if [[ "$got" == "$want" ]]; then
        pass "$desc"
    else
        fail "$desc（期望 ${want}，实际 ${got:-<空>}）" "$fix"
    fi
}

# is_subset <comma-list> <comma-list>：前者每个元素都在后者中
is_subset() {
    local sub="$1" sup=",$2," x
    for x in $(printf '%s' "$sub" | tr ',' ' '); do
        [[ "$sup" == *",$x,"* ]] || return 1
    done
    return 0
}

# 逗号分隔的 id 集合，排序后可比
ids_of() { jget "$HTTP_BODY" '[.[].id] | sort | join(",")'; }
codes_of() { jget "$HTTP_BODY" '[.[].code] | sort | join(",")'; }
usernames_of() { jget "$HTTP_BODY" '[.[].username] | sort | join(",")'; }

report_counts() {
    if [[ -n "${ACCESS_COUNTS:-}" ]]; then
        printf 'a1 %s %s\n' "$PASS" "$TOTAL" >>"$ACCESS_COUNTS"
    fi
}

# =====================================================================
section "1/3 域与厂商边界（对照 DB 与 /admin/domains 启用集）"
platform_login "$T1_TENANT" admin "Admin@12345" >/dev/null || die "admin 登录失败（先 make platform-seed）"
api_call GET /api/req/admin/domains
ADMIN_DOMAIN_IDS="$(jget "$HTTP_BODY" '[.[] | select(.enabled) | .id] | sort | join(",")')"
[[ -n "$ADMIN_DOMAIN_IDS" ]] || die "admin 读 /admin/domains 失败（HTTP ${HTTP_CODE}），a1 无法建立对照基准"

api_call GET /api/req/dictionaries/domains
DICT_DOMAIN_IDS="$(ids_of)"
expect_eq "admin 域字典 == /admin/domains 启用集" "$DICT_DOMAIN_IDS" "$ADMIN_DOMAIN_IDS" \
    "字典 domains 的 WHERE 条件必须与启用域一致（biz_domain.tenant_id + enabled=1）"

ENABLED_DB_IDS="$(db_q "SELECT id FROM biz_domain WHERE tenant_id=1 AND enabled=1 ORDER BY id" | paste -sd, -)"
expect_eq "admin 域字典 == DB 启用域（独立信源复核）" "$DICT_DOMAIN_IDS" "$ENABLED_DB_IDS" \
    "HTTP 与 DB 两处都要对：只看 200 的假绿不算通过"

platform_login "$T1_TENANT" vendor1 "Passw0rd!" >/dev/null || die "vendor1 登录失败（先 make platform-seed）"
api_call GET /api/req/dictionaries/vendors
expect_eq "vendor1 厂商字典 == [VEN-A]（仅自身厂商）" "$(codes_of)" "VEN-A" \
    "厂商账号的 vendors 必须由 sys_user.vendor_id 裁剪，不能返回全租户厂商"

api_call GET /api/req/dictionaries/domains
VENDOR_DOMAIN_IDS="$(ids_of)"
if is_subset "$VENDOR_DOMAIN_IDS" "$DICT_DOMAIN_IDS"; then
    pass "vendor1 域字典 ⊆ admin 域集"
else
    fail "vendor1 域字典 ⊆ admin 域集（vendor1=${VENDOR_DOMAIN_IDS:-<空>}，admin=${DICT_DOMAIN_IDS}）" \
        "厂商账号可见域取 sys_user_domain，不能越过租户启用域"
fi

# =====================================================================
section "2/3 人员边界（不跨厂商、不跨租户）"
platform_login "$T1_TENANT" vendor2 "Passw0rd!" >/dev/null || die "vendor2 登录失败（先 make platform-seed）"
api_call GET /api/req/dictionaries/users
VEN_B_USERS="$(db_q "SELECT u.username FROM sys_user u JOIN sys_vendor v ON v.id=u.vendor_id WHERE u.tenant_id=1 AND u.status='ACTIVE' AND v.code='VEN-B' ORDER BY u.username" | paste -sd, -)"
expect_eq "vendor2 人员字典 == VEN-B 在职人员" "$(usernames_of)" "$VEN_B_USERS" \
    "厂商账号只能看到本厂商人员：人员字典要按 sys_user.vendor_id 裁剪"

platform_login "$T1_TENANT" po1 "Passw0rd!" >/dev/null || die "po1 登录失败（先 make platform-seed）"
api_call GET /api/req/dictionaries/users
PO1_USERS="$(usernames_of)"
TENANT_USERS="$(db_q "SELECT username FROM sys_user WHERE tenant_id=1 AND status='ACTIVE' ORDER BY username" | paste -sd, -)"
expect_eq "po1 人员字典 == 租户 1 在职人员（无跨租户）" "$PO1_USERS" "$TENANT_USERS" \
    "内部账号看到的是本租户全部人员：任何别的租户用户名出现即越界"

# =====================================================================
section "3/3 租户头不一致必须 403；三个字典都非空"
SAVED_TENANT="$API_TENANT"
API_TENANT=2
api_call GET /api/req/dictionaries/domains
expect_eq "po1 token + X-Tenant-Id:2 → 403（租户头不可越权）" "$HTTP_CODE" "403" \
    "网关会按 Long 解析 X-Tenant-Id 并与 JWT 的 tenantId 比对，不一致必须拒绝"
API_TENANT="$SAVED_TENANT"

EMPTY_DICTS=""
for i in domains vendors users; do
    api_call GET "/api/req/dictionaries/${i}"
    [[ "$(jget "$HTTP_BODY" 'length > 0')" == "true" ]] || EMPTY_DICTS="${EMPTY_DICTS} ${i}"
done
if [[ -z "$EMPTY_DICTS" ]]; then
    pass "admin 三个字典均非空（domains/vendors/users）"
else
    fail "admin 字典存在空表:${EMPTY_DICTS}" \
        "字典为空说明种子或裁剪条件写死了，先 make platform-seed"
fi

# =====================================================================
printf '\n'
if (( TOTAL == 0 )); then
    err "a1: 一条断言都没执行：脚本实现有 bug"
    exit 1
fi
if (( PASS != TOTAL )); then
    err "a1-isolation: ${FAIL} 项失败 / ${TOTAL} 项"
    printf '%s\n' "$FAILED_LINES" >&2
    err "修复: 从第一条 FAIL 开始；隔离断言失败意味着跨租户/跨厂商数据泄漏"
    report_counts
    exit 1
fi
report_counts
ok "a1-isolation: ${PASS}/${TOTAL} 项通过（租户 + 厂商行级边界）"
