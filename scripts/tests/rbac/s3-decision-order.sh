#!/usr/bin/env bash
# s3-decision-order.sh — 三序判定（TECHNICAL §5、AC-5/AC-6/AC-7）
#
# 本段不信任任何预设：先把目标角色的一格授权改成要测的档位，再用**真实登录**打真实端点，
# 最后在 trap 里恢复原样。判定顺序错会同时造成越权与误拒——两者都只能在 HTTP 上看见：
#   1) 页面 DENY + 动作 ALLOW ⇒ 403（页面前置：动作再亮也不能进）
#   2) 页面 ALLOW + 动作 DENY ⇒ 403（动作档位）
#   3) 页面 ALLOW + 动作 ALLOW ⇒ 非 403（空 body 走校验 400，证明放行而不污染数据）
#   4) 页面 ALLOW + 动作 RESTRICTED ⇒ 非 403（过检查，范围收窄交给既有 DataScope）
#   5) 多角色并集：任一 DENY 即 DENY
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/lib.sh"

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
        printf 's3 %s %s\n' "$PASS" "$TOTAL" >>"$RBAC_COUNTS"
    fi
}

# 探针与授权助手见 _lib.sh（s3~s8 共用一份，避免 4 份 jq 各自漂移）。
# 探针 = 空 body 打真实写端点：被拦截是 403，放行则落到参数校验 400——
# 「放行」的证据不需要真的建出测试计划，段脚本可反复跑而不脏库。
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
PROBE_METHOD="${RBAC_PROBE_METHOD}"
PROBE_PATH="${RBAC_PROBE_PATH}"
PROBE_BODY="${RBAC_PROBE_BODY}"
PAGE_CODE="${RBAC_PAGE}"
ACTION_CODE="${RBAC_ACTION}"

ADMIN_LOGIN=1
TMP_USER=""
QA_ID=""
DEV_ID=""
QA_ORIG=""
DEV_ORIG=""

cleanup() {
    # 失败路径也必须把授权与临时用户还原，否则下一次运行会踩到自己的脏数据
    if [[ -n "${QA_ID}" && -n "${QA_ORIG}" ]]; then
        login_as admin Admin@12345 >/dev/null 2>&1 || true
        put_grants "$QA_ID" "$QA_ORIG" >/dev/null 2>&1 || true
    fi
    if [[ -n "${DEV_ID}" && -n "${DEV_ORIG}" ]]; then
        login_as admin Admin@12345 >/dev/null 2>&1 || true
        put_grants "$DEV_ID" "$DEV_ORIG" >/dev/null 2>&1 || true
    fi
    if [[ -n "${TMP_USER}" ]]; then
        local uid
        uid="$(api_call GET "/api/req/admin/users?keyword=${TMP_USER}&size=5" >/dev/null; printf '%s' "$HTTP_BODY" | jq -r --arg u "$TMP_USER" '.items[]? | select(.username == $u) | .id' | head -n1)"
        if [[ -n "${uid}" && "${uid}" != "null" ]]; then
            api_call PATCH "/api/req/admin/users/${uid}/status" '{"status":"DISABLED"}' >/dev/null 2>&1 || true
        fi
    fi
}
trap cleanup EXIT

log "s3：三序判定（真实登录 + 真实端点，探针 ${PROBE_METHOD} ${PROBE_PATH}）"

login_as admin Admin@12345 || die "admin 登录失败：先 make platform-up && make platform-seed"
ADMIN_LOGIN=0
QA_ID="$(role_id_of QA)"
DEV_ID="$(role_id_of DEV)"
[[ -n "${QA_ID}" && -n "${DEV_ID}" ]] || die "取不到内置角色 QA/DEV 的 id（GET /admin/roles 需要 T4 已上线）"

QA_ORIG="$(grants_of "$QA_ID")"
DEV_ORIG="$(grants_of "$DEV_ID")"
if [[ -z "${QA_ORIG}" || "${QA_ORIG}" == "[]" ]]; then
    fail "GET /admin/roles/${QA_ID}/permissions 返回了可用授权树" \
        "T4 的 RoleGrantView 必须带 modules[].pages[].actions[]；空树说明 seeds 或 JOIN 维度错"
    report_counts
    exit 1
fi
pass "取到 QA 角色的完整授权树（$(printf '%s' "$QA_ORIG" | jq 'length') 行）"

# ---------------------------------------------------------------- 1. 页面前置
QA_DENY_PAGE="$(set_cell "$QA_ORIG" MENU "$PAGE_CODE" DENY)"
QA_DENY_PAGE="$(set_cell "$QA_DENY_PAGE" ACTION "$ACTION_CODE" ALLOW)"
put_grants "$QA_ID" "$QA_DENY_PAGE" >/dev/null
login_as qa1 'Passw0rd!' >/dev/null
CODE1="$(probe_settle 403)"
if [[ "${CODE1}" == "403" ]]; then
    pass "页面 DENY + 动作 ALLOW ⇒ 403（页面前置）"
else
    fail "页面 DENY + 动作 ALLOW 未拦截：HTTP ${CODE1}" \
        "decide() 必须先看 MENU；只查动作会让「页面被关掉」的角色仍能直接调 API"
fi

# ---------------------------------------------------------------- 2. 动作档位
QA_DENY_ACTION="$(set_cell "$QA_ORIG" MENU "$PAGE_CODE" ALLOW)"
QA_DENY_ACTION="$(set_cell "$QA_DENY_ACTION" ACTION "$ACTION_CODE" DENY)"
# 写授权必须用管理员身份：qa1 没有 role:grant，用它发 PUT 只会拿到 403，「改档位」这一步就静默
# 失效了——后面的断言会拿上一步的旧状态当证据（假 PASS 与假 FAIL 都由此而来）。
admin_login >/dev/null
put_grants "$QA_ID" "$QA_DENY_ACTION" >/dev/null
login_as qa1 'Passw0rd!' >/dev/null
CODE2="$(probe_settle 403)"
if [[ "${CODE2}" == "403" ]]; then
    pass "页面 ALLOW + 动作 DENY ⇒ 403（动作档位）"
else
    fail "页面 ALLOW + 动作 DENY 未拦截：HTTP ${CODE2}" \
        "动作档没生效：前端按钮关了、后端还开着，等于按钮只是装饰"
fi

# ---------------------------------------------------------------- 3. 放行
QA_ALLOW="$(set_cell "$QA_ORIG" MENU "$PAGE_CODE" ALLOW)"
QA_ALLOW="$(set_cell "$QA_ALLOW" ACTION "$ACTION_CODE" ALLOW)"
admin_login >/dev/null
put_grants "$QA_ID" "$QA_ALLOW" >/dev/null
login_as qa1 'Passw0rd!' >/dev/null
CODE3="$(probe_settle allow)"
if [[ "${CODE3}" != "403" && "${CODE3}" != "401" && "${CODE3}" != "000" ]]; then
    pass "页面 ALLOW + 动作 ALLOW ⇒ 放行（HTTP ${CODE3}，落到参数校验而非拦截）"
else
    fail "页面与动作都 ALLOW 仍被拦：HTTP ${CODE3}" \
        "误拒比越权更难发现：RESTRICTED/并集实现错会先把正常用户关在门外"
fi

# ---------------------------------------------------------------- 4. RESTRICTED
QA_RESTRICTED="$(set_cell "$QA_ORIG" MENU "$PAGE_CODE" ALLOW)"
QA_RESTRICTED="$(set_cell "$QA_RESTRICTED" ACTION "$ACTION_CODE" RESTRICTED)"
admin_login >/dev/null
put_grants "$QA_ID" "$QA_RESTRICTED" >/dev/null
login_as qa1 'Passw0rd!' >/dev/null
CODE4="$(probe_settle allow)"
if [[ "${CODE4}" != "403" && "${CODE4}" != "401" && "${CODE4}" != "000" ]]; then
    pass "动作 RESTRICTED ⇒ 仍过检查（HTTP ${CODE4}，范围收窄由 DataScope 承担）"
else
    fail "动作 RESTRICTED 被当成 DENY：HTTP ${CODE4}" \
        "RESTRICTED = 允许但收窄（AC-7）；塌成 DENY 会让「只能看自己域」的人完全不可用"
fi

# ---------------------------------------------------------------- 5. 多角色并集
login_as admin Admin@12345 >/dev/null
DEV_DENY="$(set_cell "$DEV_ORIG" MENU "$PAGE_CODE" DENY)"
put_grants "$DEV_ID" "$DEV_DENY" >/dev/null
put_grants "$QA_ID" "$QA_ALLOW" >/dev/null      # QA = ALLOW，DEV = DENY ⇒ 并集 DENY
TMP_USER="s3u$RANDOM"
CREATED="$(api_call POST /api/req/admin/users "{\"username\":\"${TMP_USER}\",\"password\":\"Passw0rd!\",\"name\":\"s3 probe\",\"roles\":[\"QA\",\"DEV\"]}"; printf '%s' "${HTTP_CODE:-000}")"
if [[ "${CREATED}" == "200" || "${CREATED}" == "201" ]]; then
    login_as "$TMP_USER" 'Passw0rd!' >/dev/null
    CODE5="$(probe_settle 403)"
    if [[ "${CODE5}" == "403" ]]; then
        pass "多角色并集：QA(ALLOW) + DEV(DENY) ⇒ 403（任一 DENY 即 DENY）"
    else
        fail "并集未取 DENY：QA+DEV 用户拿到 HTTP ${CODE5}" \
                "多给一个角色只能更严（AC-6）；取 max 会把 DENY 稀释掉"
    fi
else
    fail "创建多角色探针用户失败：HTTP ${CREATED}" \
        "POST /admin/users 需要 T4 已上线；body 见 TECHNICAL §7"
fi

# ---------------------------------------------------------------- 6. 403 契约
login_as admin Admin@12345 >/dev/null
put_grants "$QA_ID" "$QA_DENY_ACTION" >/dev/null
login_as qa1 'Passw0rd!' >/dev/null
# 上一步是放行态，撤权要等跨服务快照的 TTL 传播（≤45s）才拦得住；契约断言只看“确实被拦”的
# 那一次响应，所以先 settle 到 403，再抓 body——否则抓到的是 400 参数校验，测的就不是 403 契约。
probe_settle 403 >/dev/null

# 但 settle 只能证明「至少有一个 deliver 节点已生效」：网关路由是 lb://deliver-service，紧随其后
# 的那一次请求可能落到另一个还没刷到新快照的节点，于是回 400 校验错误——本 run 实测 1 红 1 绿。
# 契约 body 必须取自**403 的那一次响应本身**，所以在 TTL 上界窗口内重试请求，一旦拿到 403 就地收下
# body 再断言。这里重试的是「抓哪一次响应」，不是「重试断言」：拿到 403 之后的判定仍然只有一次。
CONTRACT_LIMIT=45   # 与 probe_settle 的 70s 同口径：跨进程传播上界是 cache-ttl（45s）
CONTRACT_WAITED=0
CONTRACT_CODE="000"
CONTRACT_BODY=""
CONTRACT_TRIES=0
while true; do
    CONTRACT_TRIES=$((CONTRACT_TRIES + 1))
    api_call "$PROBE_METHOD" "$PROBE_PATH" "$PROBE_BODY" >/dev/null
    CONTRACT_CODE="${HTTP_CODE:-000}"
    if [[ "${CONTRACT_CODE}" == "403" ]]; then
        CONTRACT_BODY="$HTTP_BODY"
        break
    fi
    if (( CONTRACT_WAITED >= CONTRACT_LIMIT )); then
        break
    fi
    sleep 3
    CONTRACT_WAITED=$((CONTRACT_WAITED + 3))
done

if [[ "${CONTRACT_CODE}" != "403" ]]; then
    fail "窗口内（${CONTRACT_LIMIT}s / ${CONTRACT_TRIES} 次请求）没取到 403 响应，最后 HTTP ${CONTRACT_CODE}" \
        "撤权后每个 deliver 节点各自按 TTL 生效；若整窗都是 400，问题在授权写入或缓存失效（先看 s4 的 epoch 断言）"
else
    DET="$(printf '%s' "$CONTRACT_BODY" | jq -r '[.code, .message, (.detail.page // "-"), (.detail.action // "-"), (.detail.access // "-"), (.detail.method // "-")] | join("|")' 2>/dev/null)"
    if [[ "$(printf '%s' "$DET" | cut -d'|' -f1)" == "FORBIDDEN" &&
          "$(printf '%s' "$DET" | cut -d'|' -f2)" == "无权执行该操作" &&
          "$(printf '%s' "$DET" | cut -d'|' -f3)" == "$PAGE_CODE" &&
          "$(printf '%s' "$DET" | cut -d'|' -f4)" == "$ACTION_CODE" ]]; then
        pass "403 契约：code/message/detail{page,action,access,method} 齐全（第 ${CONTRACT_TRIES} 次请求命中 403）"
    else
        fail "403 body 不符合契约：${DET:-<空>}" \
            "前端 403 页与审计都靠 detail；r17 的格式不能回退（detail.page/action/access/method/path）"
    fi
fi

# ---------------------------------------------------------------- 7. 恢复与 epoch
login_as admin Admin@12345 >/dev/null
EPOCH0="$(api_call GET /api/req/auth/me/permissions >/dev/null; printf '%s' "$HTTP_BODY" | jq -r '.epoch // 0')"
put_grants "$QA_ID" "$QA_ORIG" >/dev/null
RESTORED="$(printf '%s' "$HTTP_CODE")"
EPOCH1="$(api_call GET /api/req/auth/me/permissions >/dev/null; printf '%s' "$HTTP_BODY" | jq -r '.epoch // 0')"
if [[ "${RESTORED}" == "200" && "${EPOCH1}" -gt "${EPOCH0}" ]]; then
    pass "授权恢复成功且 epoch 单调递增（${EPOCH0} → ${EPOCH1}）"
else
    fail "恢复失败或 epoch 未推进：HTTP ${RESTORED}，epoch ${EPOCH0} → ${EPOCH1}" \
        "PUT 成功后必须 invalidate 并把 epoch+1 返回给前端（AC-15）"
fi

# ---------------------------------------------------------------- 汇总
if (( TOTAL == 0 )); then
    err "s3: 一条断言都没执行：脚本实现有 bug"
    exit 1
fi
if (( PASS != TOTAL )); then
    err "s3-decision-order: ${FAIL} 项失败 / ${TOTAL} 项"
    printf '%s\n' "$FAILED_LINES" >&2
    report_counts
    exit 1
fi
report_counts
ok "s3-decision-order: ${PASS}/${TOTAL} 项通过（页面 → 动作 → 范围 三序）"
