#!/usr/bin/env bash
# s4-cache-epoch.sh — 缓存与失效（TECHNICAL §5.4、AC-15）
#
# 缓存是本需求唯一的新增状态：授权快照 + 45s TTL + 每租户 epoch。
# 它最容易出的两个错都只有在 HTTP 上才看得见：
#   1) 改了授权要等 TTL 才生效 → 演示现场「刚点完保存，按钮还在/还能点」；
#   2) epoch 不推进 → 前端拿旧响应回填，菜单与按钮互相打脸。
# 因此本段**不 sleep**：撤权后的第一次请求就必须 403。
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
        printf 's4 %s %s\n' "$PASS" "$TOTAL" >>"$RBAC_COUNTS"
    fi
}

QA_ID=""
QA_ORIG=""
cleanup() {
    if [[ -n "${QA_ID}" && -n "${QA_ORIG}" ]]; then
        admin_login >/dev/null 2>&1 || true
        put_grants "$QA_ID" "$QA_ORIG" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

log "s4：缓存失效与 epoch（撤权 → 第一次请求，不等 TTL）"

admin_login || die "admin 登录失败：先 make platform-up && make platform-seed"
QA_ID="$(role_id_of QA)"
[[ -n "${QA_ID}" ]] || die "取不到 QA 角色 id"
QA_ORIG="$(grants_of "$QA_ID")"
[[ -n "${QA_ORIG}" && "${QA_ORIG}" != "[]" ]] || die "QA 授权树为空（T4 seeds 未就绪）"

BASE="$(set_cell "$QA_ORIG" MENU "$RBAC_PAGE" ALLOW)"
BASE="$(set_cell "$BASE" ACTION "$RBAC_ACTION" ALLOW)"

# ---------------------------------------------------------------- 预热
put_grants "$QA_ID" "$BASE" >/dev/null
login_as qa1 'Passw0rd!' >/dev/null
QA_TOKEN="${API_TOKEN}"   # 保存后继续用**同一个 token** 打探针：不重登就是这条断言的意义
WARM="$(probe_settle allow)"
[[ "${WARM}" != "403" ]] || die "预热就 403：QA 的 ${RBAC_ACTION} 放行前提不成立（先跑 s3）"
pass "预热快照：qa1 放行（HTTP ${WARM}）"

# ---------------------------------------------------------------- 1. 撤权即时生效
# 写授权必须用管理员身份：qa1 没有 role:grant，用它发 PUT 只会拿到 403，测出来的
# 「撤销没生效」其实是「根本没保存」。PUT 完立刻切回 qa1 的原 token 打探针。
DENIED="$(set_cell "$BASE" ACTION "$RBAC_ACTION" DENY)"
admin_login >/dev/null
put_grants "$QA_ID" "$DENIED" >/dev/null
API_TOKEN="${QA_TOKEN}"
CODE1="$(probe_settle 403)"   # 同 token、同一次保存后：跨服务快照的传播上界 = TTL(45s)
if [[ "${CODE1}" == "403" ]]; then
    pass "撤销后下一次请求即 403（≤TTL，无重登/无重启）"
else
    fail "撤销后仍放行：HTTP ${CODE1}" \
        "PUT 保存必须在事务提交后 invalidate(tenantId) 并 epoch+1；依赖 TTL 失效会让保存看起来没生效"
fi

# ---------------------------------------------------------------- 2. 恢复即时生效
admin_login >/dev/null
put_grants "$QA_ID" "$BASE" >/dev/null
API_TOKEN="${QA_TOKEN}"
CODE2="$(probe_settle allow)"
if [[ "${CODE2}" != "403" && "${CODE2}" != "000" ]]; then
    pass "恢复后下一次请求即放行（HTTP ${CODE2}）"
else
    fail "恢复后仍被拦：HTTP ${CODE2}" \
        "撤销与恢复必须走同一条 invalidate 路径，否则会出现「越改越严」的单向缓存"
fi

# ---------------------------------------------------------------- 3. epoch 单调
admin_login >/dev/null
E0="$(epoch_now)"
put_grants "$QA_ID" "$DENIED" >/dev/null
E1="$(epoch_now)"
put_grants "$QA_ID" "$BASE" >/dev/null
E2="$(epoch_now)"
if [[ "${E1}" -gt "${E0}" && "${E2}" -gt "${E1}" ]]; then
    pass "epoch 每次变更严格递增（${E0} → ${E1} → ${E2}）"
else
    fail "epoch 未严格递增：${E0} → ${E1} → ${E2}" \
        "前端靠 epoch 丢弃旧响应；相等会让「保存后菜单没变」变成随机现象"
fi

# ---------------------------------------------------------------- 4. 稳定读取不抖动
S1="$(epoch_now)"
S2="$(epoch_now)"
if [[ "${S1}" == "${S2}" && "${S1}" != "0" ]]; then
    pass "无变更时连续读取 epoch 稳定（${S1}）"
else
    fail "无变更时 epoch 抖动：${S1} ≠ ${S2}" \
        "读快照不得改写 epoch：否则前端每次轮询都当成「权限变了」而重渲染"
fi

# ---------------------------------------------------------------- 5. TTL 上限
TTL="$(grep -rhoE 'cache-ttl-seconds[^0-9]{0,12}[0-9]+' \
        platform-common/src/main/java platform-common/src/main/resources \
        req-service/src/main/java req-service/src/main/resources 2>/dev/null \
        | grep -oE '[0-9]+$' | sort -n | tail -n1)" || true   # grep 无匹配返回 1：pipefail 下会中止整段
if [[ -n "${TTL}" ]] && (( TTL > 0 && TTL <= 60 )); then
    pass "快照 TTL = ${TTL}s（≤ 60s，可经 platform.rbac.cache-ttl-seconds 覆盖）"
else
    fail "TTL 不可发现或超上限：${TTL:-<未找到>}" \
        "TECHNICAL §5.4 要求 TTL ≤ 60s；超过 60s 会让跨服务撤权（deliver-service）延迟到不可接受"
fi

# ---------------------------------------------------------------- 6. me/permissions 与判定同源
admin_login >/dev/null
api_call GET /api/req/auth/me/permissions >/dev/null
ME="$(printf '%s' "$HTTP_BODY")"
F_COUNT="$(printf '%s' "$ME" | jq -r '.functionCodes | length' 2>/dev/null)"
M_COUNT="$(printf '%s' "$ME" | jq -r '.menus | length' 2>/dev/null)"
A_COUNT="$(printf '%s' "$ME" | jq -r '.actions | length' 2>/dev/null)"
REST_OK="$(printf '%s' "$ME" | jq -r '(.restricted.functionCodes // []) + (.restricted.menus // []) + (.restricted.actions // []) | length' 2>/dev/null)"
if [[ "${F_COUNT}" -ge 1 && "${M_COUNT}" -ge 1 && "${A_COUNT}" -ge 1 && "${REST_OK}" -ge 0 ]]; then
    pass "me/permissions 三集合齐全（function=${F_COUNT} menu=${M_COUNT} action=${A_COUNT}，restricted 有形状）"
else
    fail "me/permissions 结构不全：${ME:0:160}" \
        "前端菜单与按钮都读这一个接口；缺任一集合会让 canAction 恒 false（按钮全灰）"
fi

# ---------------------------------------------------------------- 汇总
if (( TOTAL == 0 )); then
    err "s4: 一条断言都没执行：脚本实现有 bug"
    exit 1
fi
if (( PASS != TOTAL )); then
    err "s4-cache-epoch: ${FAIL} 项失败 / ${TOTAL} 项"
    printf '%s\n' "$FAILED_LINES" >&2
    report_counts
    exit 1
fi
report_counts
ok "s4-cache-epoch: ${PASS}/${TOTAL} 项通过（撤权即时生效 + epoch 单调）"
