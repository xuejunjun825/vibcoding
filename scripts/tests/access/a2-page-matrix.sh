#!/usr/bin/env bash
# a2-page-matrix.sh — 9 页 × 8 角色准入矩阵（PRD AC-5 / r13 AC-17；r24 加 vpm1；TECHNICAL §5；73 断言）
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
# r13 新增末四页（岗位工作台 / 数据看板 / 自助报表 / 报表编辑器）：权限矩阵给十角色
# 全开 PORTAL_VIEW 与 REPORT_VIEW（PermissionMatrix），因此这 28 格期望一律 200，
# 不写任何 403 —— 若后端真收紧，会以 FAIL 暴露而不是被静默放宽。
PAGE_LABELS=("需求池" "版本计划" "审批中心" "测试计划" "分支策略" "岗位工作台" "数据看板" "自助报表" "报表编辑器")
PAGE_ENDPOINTS=(
    "/api/req/requirements"
    "/api/req/releases"
    "/api/req/approvals/todo?page=1&size=5"
    "/api/deliver/test-plans?page=1&size=5"
    "/api/req/code/policies"
    "/api/deliver/portal"
    "/api/deliver/analytics/dashboard"
    "/api/deliver/reports?page=1&size=5"
    "/api/deliver/reports/datasets"
)
# r18：准入主体从「功能码」换成「页面码」（MENU 层）。同一页面的功能码仍参与
# 双条件（前端菜单），但**服务端拦人靠页面码**——所以这里的第二列写页面码，
# 状态矩阵本身不变：页面码由 max(绑定功能码) 推导，单码页面与 r17 逐格一致。
PAGE_CODES=("req.pool" "req.release" "me.approvals" "test.plans" "code.policies" "ana.portal" "ana.dashboard" "ana.reports" "ana.reports")
PAGE_GUARDS=("仅认证" "release:plan" "仅认证" "plan:manage" "CCODE_POLICY_VIEW" "PORTAL_VIEW" "PORTAL_VIEW" "REPORT_VIEW" "REPORT_VIEW")
# r24：vpm1（VENDOR_PM）是第 8 个探测角色。它不改任何既有格——矩阵按角色取值，加一行只新增 9 格。
# 只看两页是 ⛔：分支策略（CCODE_POLICY_VIEW 对它是 DENY）。测试计划对它反而是 ✅ ——
# 页面码 test.plans 绑定 plan:manage，而 VENDOR_PM 的 plan:manage 是 ALLOW（PRD：参与排期与测试计划）。
ROLES=(admin pm tl1 po1 dev1 qa1 vendor1 vpm1)

# expected_code <page-index> <role> → 精确状态码（TECHNICAL §5 的 ⛔ 格）
expected_code() {
    case "$1:$2" in
        3:po1|3:dev1) echo 403 ;;          # 测试计划：PRODUCT / DEV 对 plan:manage 是 DENY
        4:pm|4:po1|4:qa1) echo 403 ;;      # 分支策略：PMO / SUB_BUSINESS_LEADER / QA 无 CCODE_POLICY_VIEW
        4:vpm1) echo 403 ;;                # 分支策略：VENDOR_PM 无 CCODE_POLICY_VIEW（r24）
        *) echo 200 ;;                     # 含 r13 新增四页：十角色全开 PORTAL_VIEW/REPORT_VIEW
    esac
}

# =====================================================================
section "9 页 × 8 角色：精确状态码（66 ✅ / 6 ⛔）"
FORBIDDEN_SEEN=0
FORBIDDEN_BAD=""
for i in "${!PAGE_LABELS[@]}"; do
    printf '\n  \033[1m%s\033[0m（%s，隐藏页面 %s / 功能码 %s）\n' "${PAGE_LABELS[$i]}" \
        "${PAGE_ENDPOINTS[$i]}" "${PAGE_CODES[$i]}" "${PAGE_GUARDS[$i]}"
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
            # 变量后面直接跟全角字符必须写成 ${desc}：`$desc（` 在 C.UTF-8 下会被 bash
            # 当成变量名的一部分（全角括号被算进标识符），报 “desc?: unbound variable”。
            fail "${desc}（实际 ${HTTP_CODE}: $(printf '%s' "$HTTP_BODY" | head -c 200)）" \
                "矩阵见 TECHNICAL §5：⛔ 必须 403（200 也算红），✅ 必须 200；页面码 ${PAGE_CODES[$i]}"
        fi
        # r18 契约：403 必须说清是谁拦的（detail.page / detail.access），否则排障只能猜
        if [[ "$HTTP_CODE" == "403" ]]; then
            FORBIDDEN_SEEN=$((FORBIDDEN_SEEN + 1))
            got_page="$(jget "$HTTP_BODY" '.detail.page // empty' 2>/dev/null || true)"
            got_access="$(jget "$HTTP_BODY" '.detail.access // empty' 2>/dev/null || true)"
            got_code="$(jget "$HTTP_BODY" '.code // empty' 2>/dev/null || true)"
            if [[ -z "$got_page" || "$got_access" != "DENY" || "$got_code" != "FORBIDDEN" ]]; then
                FORBIDDEN_BAD="${FORBIDDEN_BAD} ${role}:${PAGE_LABELS[$i]}(${got_code}/${got_page:-<无 page>}/${got_access:-<无 access>})"
            fi
        fi
    done
done

# 单独一条：5 个 ⛔ 的响应体都自带 page 与 DENY，前端与审计都靠这两个字段定位
if [[ "$FORBIDDEN_SEEN" -eq 0 ]]; then
    fail "403 响应契约：一个 403 样本都没采到" "矩阵若全绿就没有 ⛔ 格，说明页面准入被放开了（AC-5）"
elif [[ -z "$FORBIDDEN_BAD" ]]; then
    pass "403 响应契约：${FORBIDDEN_SEEN} 个 ⛔ 格全部带 code=FORBIDDEN + detail.page + detail.access=DENY"
else
    fail "403 响应体缺 page/access 或 code 不是 FORBIDDEN:${FORBIDDEN_BAD}" \
        "TECHNICAL §6.4：detail 必须带 page/action/access/method/path，前端据此定位、审计据此留痕"
fi

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
ok "a2-page-matrix: ${PASS}/${TOTAL} 项通过（9 页 × 8 角色精确匹配）"
