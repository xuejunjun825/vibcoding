#!/usr/bin/env bash
# a2-page-matrix.sh — 5 页 × 7 角色准入矩阵（PRD AC-5；TECHNICAL §5；35 断言）
#
# 每格断言的是**精确状态码**：⛔ 必须 403（200 也算红），✅ 必须 200（403 说明字典修复
# 只修了一半）。探测端点取各页首屏请求，与真实页面同一条链路（网关 → JWT → 权限码）。
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/lib.sh"

TOTAL=0
PASS=0
FAIL=0
FAILED_LINES=""
T1_TENANT="${A2_TENANT:-t1}"
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

report_counts() {
    if [[ -n "${ACCESS_COUNTS:-}" ]]; then
        printf 'a2 %s %s\n' "$PASS" "$TOTAL" >>"$ACCESS_COUNTS"
    fi
}

# ---------------------------------------------------------------- 矩阵定义
PAGE_LABELS=("需求池" "版本计划" "审批中心" "测试计划" "分支策略")
PAGE_ENDPOINTS=(
    "/api/req/requirements"
    "/api/req/releases"
    "/api/req/approvals/todo?page=1&size=5"
    "/api/deliver/test-plans?page=1&size=5"
    "/api/req/code/policies"
)
PAGE_GUARDS=("仅认证" "release:plan" "仅认证" "plan:manage" "CCODE_POLICY_VIEW")
ROLES=(admin pm tl1 po1 dev1 qa1 vendor1)

# expected_code <page-index> <role> → 精确状态码（TECHNICAL §5 的 5 个 ⛔）
expected_code() {
    case "$1:$2" in
        3:po1|3:dev1) echo 403 ;;          # 测试计划：PRODUCT / DEV 对 plan:manage 是 DENY
        4:pm|4:po1|4:qa1) echo 403 ;;      # 分支策略：PMO / SUB_BUSINESS_LEADER / QA 无 CCODE_POLICY_VIEW
        *) echo 200 ;;
    esac
}

# =====================================================================
section "5 页 × 7 角色：精确状态码（30 ✅ / 5 ⛔）"
for i in "${!PAGE_LABELS[@]}"; do
    printf '\n  \033[1m%s\033[0m（%s，守码 %s）\n' "${PAGE_LABELS[$i]}" "${PAGE_ENDPOINTS[$i]}" "${PAGE_GUARDS[$i]}"
    for role in "${ROLES[@]}"; do
        role_pass="Passw0rd!"
        [[ "$role" == "admin" ]] && role_pass="Admin@12345"
        platform_login "$T1_TENANT" "$role" "$role_pass" >/dev/null \
            || die "角色 ${role} 登录失败（先 make platform-seed）"
        api_call GET "${PAGE_ENDPOINTS[$i]}"
        want="$(expected_code "$i" "$role")"
        desc="${role} → ${PAGE_LABELS[$i]} ${want}"
        if [[ "$HTTP_CODE" == "$want" ]]; then
            pass "$desc"
        else
            fail "$desc（实际 ${HTTP_CODE}: $(printf '%s' "$HTTP_BODY" | head -c 200)）" \
                "矩阵见 TECHNICAL §5：⛔ 必须 403（200 也算红），✅ 必须 200；守码 ${PAGE_GUARDS[$i]}"
        fi
    done
done

# =====================================================================
printf '\n'
if (( TOTAL == 0 )); then
    err "a2: 一条断言都没执行：脚本实现有 bug"
    exit 1
fi
if (( PASS != TOTAL )); then
    err "a2-page-matrix: ${FAIL} 项失败 / ${TOTAL} 项"
    printf '%s\n' "$FAILED_LINES" >&2
    err "修复: 先看角色/页面组合，再对照 TECHNICAL §5 的守码"
    report_counts
    exit 1
fi
report_counts
ok "a2-page-matrix: ${PASS}/${TOTAL} 项通过（5 页 × 7 角色精确匹配）"
