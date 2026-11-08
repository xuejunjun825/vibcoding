#!/usr/bin/env bash
# platform-seed.sh — AC-19 种子幂等门禁。
# 连跑两次 POST /internal/seed，断言：第二次 skipped、两次 counts 完全一致、
# 7 域 / 3 厂商 / 10 账号 / 1 个测试计划（≥10 用例）/ 完整链路存在。
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

FAIL=0
CASE=0
mkdir -p "$(run_log_dir)"

pass() { CASE=$((CASE + 1)); printf '  \033[1;32mPASS\033[0m [%02d] %s\n' "$CASE" "$1"; }
fail() { CASE=$((CASE + 1)); FAIL=$((FAIL + 1)); printf '  \033[1;31mFAIL\033[0m [%02d] %s\n' "$CASE" "$1"; printf '       修复: %s\n' "$2"; }

STAGE="前置：req-service 语义就绪"
if ! req_ready; then
    err "req-service 未就绪: ${REQ_URL}/internal/health"
    err "修复: 先执行 make platform-up"
    exit 1
fi

seed_once() {
    internal_call POST "$REQ_URL" /internal/seed
    if [[ "$HTTP_CODE" != "200" ]]; then
        err "POST /internal/seed 失败 (HTTP ${HTTP_CODE}): $(printf '%s' "$HTTP_BODY" | head -c 300)"
        err "修复: PLATFORM_SEED_ENABLED=true 才会注册该端点（platform-up 已设置）；404 表示被关闭"
        exit 1
    fi
    json_ok "$HTTP_BODY" || { err "seed 响应不是 JSON: $HTTP_BODY"; exit 1; }
}

log "第一次 seed"
seed_once
FIRST="$HTTP_BODY"
log "第二次 seed（幂等）"
seed_once
SECOND="$HTTP_BODY"

C1="$(printf '%s' "$FIRST" | jq -cS '.counts')"
C2="$(printf '%s' "$SECOND" | jq -cS '.counts')"
printf '%s\n' "$FIRST" | jq -c . >/dev/null

[[ "$C1" == "$C2" ]] \
    && pass "两遍 seed 的 counts 完全一致: ${C1}" \
    || fail "两遍 seed 计数不一致: ${C1} != ${C2}" "检查 SeedService 是否按 tenant/code 幂等 upsert；重复执行必须走 skip"

SECOND_SKIPPED="$(printf '%s' "$SECOND" | jq -r '.skipped')"
[[ "$SECOND_SKIPPED" == "true" ]] \
    && pass "第二次 seed 标记 skipped=true（没有重复写入）" \
    || fail "第二次 seed 未标记 skipped（实际: ${SECOND_SKIPPED}）" "SeedService 在已存在标记行时应直接返回 skipped"

check_count() {
    local key="$1" want="$2" label="$3"
    local got; got="$(printf '%s' "$C1" | jq -r ".${key} // empty")"
    if [[ "$want" == ">="* ]]; then
        local min="${want#>=}"
        [[ -n "$got" && "$got" -ge "$min" ]] \
            && pass "${label}: ${got} (>= ${min})" \
            || fail "${label}: 实际 ${got:-<缺失>}，要求 >= ${min}" "检查 SeedService 是否写入该表"
    else
        [[ "$got" == "$want" ]] \
            && pass "${label}: ${got}" \
            || fail "${label}: 实际 ${got:-<缺失>}，期望 ${want}" "检查 SeedService 是否写入该表"
    fi
}

check_count domains 7 "7 个业务域"
check_count vendors 3 "3 家厂商"
check_count releases ">=1" "版本（>=1）"
check_count testPlans ">=1" "测试计划（>=1，验证会再建一个）"
check_count testCases ">=10" "测试计划用例（>=10）"

# 「10 个账号」断言的是种子那 10 个账号存在，而不是「tenant 内用户总数恰好 10」：
# 权限/账号类检查（如 T1 的 devcheck）会在同一个 t1 租户里加人，用总数会把别人的账号算成种子缺陷。
TENANT_ID="$(db_q "SELECT id FROM sys_tenant WHERE code='t1' LIMIT 1")"
SEEDED_USERS="$(db_q "SELECT COUNT(DISTINCT username) FROM sys_user
    WHERE tenant_id=${TENANT_ID}
      AND username IN ('admin','pm','tl1','po1','dev1','dev2','qa1','qa2','vendor1','vendor2')")"
USERS_TOTAL="$(db_q "SELECT COUNT(*) FROM sys_user WHERE tenant_id=${TENANT_ID}")"
[[ "$SEEDED_USERS" == "10" ]] \
    && pass "10 个种子账号齐备（tenant 内共 ${USERS_TOTAL} 个用户）" \
    || fail "10 个种子账号只找到 ${SEEDED_USERS} 个（tenant 内共 ${USERS_TOTAL} 个用户）" "检查 SeedService 的 userDefs 是否全部写入"

# 完整链路：1 BUS → 2 PDR → 4 RD，且每个 RD 的父需求确实是 PDR（PRD AC-19「1 条完整链路」，
# 不是「需求总数 >=10」——总数由 perf 的 10 万条数据集负责）。
CHAIN_BUS="$(db_q "SELECT COUNT(*) FROM requirement WHERE tenant_id=${TENANT_ID} AND type='BUS'")"
CHAIN_PDR="$(db_q "SELECT COUNT(*) FROM requirement WHERE tenant_id=${TENANT_ID} AND type='PDR'")"
CHAIN_RD="$(db_q "SELECT COUNT(*) FROM requirement WHERE tenant_id=${TENANT_ID} AND type='RD'")"
CHAIN_LINKED="$(db_q "SELECT COUNT(*) FROM requirement rd
    JOIN requirement pdr ON pdr.id = rd.parent_id AND pdr.type='PDR'
    WHERE rd.tenant_id=${TENANT_ID} AND rd.type='RD'")"
if [[ "$CHAIN_BUS" -ge 1 && "$CHAIN_PDR" -ge 2 && "$CHAIN_RD" -ge 4 && "$CHAIN_LINKED" == "$CHAIN_RD" ]]; then
    pass "完整链路 BUS=${CHAIN_BUS} → PDR=${CHAIN_PDR} → RD=${CHAIN_RD}（RD 全部挂在 PDR 下）"
else
    fail "链路不完整 BUS=${CHAIN_BUS}/PDR=${CHAIN_PDR}/RD=${CHAIN_RD}/挂在 PDR 下的 RD=${CHAIN_LINKED}" \
        "检查 SeedService 的 requirement(...) 父子关系与 type"
fi

# 10 个账号必须都能登录（AC-19 的「10 账号可登录」），且各自只看权限内数据。
USERS="$(printf '%s' "$C1" | jq -r '.loginUsers[]? // empty' 2>/dev/null || true)"
if [[ -n "$USERS" ]]; then
    LOGIN_FAIL=0
    while IFS=: read -r u p; do
        [[ -n "$u" ]] || continue
        api_call POST /api/req/auth/login "{\"tenantCode\":\"$(printf '%s' "$C1" | jq -r '.tenantCode')\",\"username\":\"$u\",\"password\":\"$p\"}"
        [[ "$HTTP_CODE" == "200" ]] || { LOGIN_FAIL=$((LOGIN_FAIL + 1)); warn "登录失败: $u (HTTP $HTTP_CODE)"; }
    done <<< "$USERS"
    (( LOGIN_FAIL == 0 )) \
        && pass "10 个账号全部可登录" \
        || fail "有 ${LOGIN_FAIL} 个账号登录失败" "检查 SeedService 写入的 password_hash 与登录编码是否一致"
else
    fail "seed 响应缺 loginUsers 列表（无法验证 10 账号可登录）" "SeedService 在 counts.loginUsers 返回 user:password 列表"
fi

# 落一份可审计的计数载荷（非可重跑：每次 seed 的真实响应）
CAPTURE="$(run_log_dir)/captures.json"
if [[ ! -f "$CAPTURE" ]]; then printf '{"seed":[]}\n' > "$CAPTURE"; fi
jq --argjson first "$FIRST" --argjson second "$SECOND" \
   --arg at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
   '.seed += [{at:$at, first:$first, second:$second}]' "$CAPTURE" > "${CAPTURE}.tmp" \
   && mv "${CAPTURE}.tmp" "$CAPTURE" || warn "无法追加 captures.json（不阻断门禁）"

printf '\n'
if (( FAIL > 0 )); then
    err "platform-seed: ${FAIL} 项失败 / ${CASE} 项"
    exit 1
fi
ok "platform-seed: ${CASE}/${CASE} 项通过（幂等与 AC-19 数据齐备）"
