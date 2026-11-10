#!/usr/bin/env bash
# platform-demo-reset.sh — 演示数据重置（PRD AC-13 / 技术方案 §6.2）。
#
# 做三件事：
#   1. 调内部端点 POST /internal/demo-reset，把租户的业务实例数据清空并重建「12 需求小链路」；
#   2. 把响应里的计数表（表名 → 行数 + 不变量实测值）对齐打印，人工一眼看清演示态；
#   3. 复核硬口径：不变量 12 / 8 / 3 / 0、夹具痕迹清零、连跑两次 counts 逐键相等（幂等），
#      再用 po1 / vendor1 / vendor2 真实登录各查一次需求列表，证明隔离边界不是只写在 SQL 里。
#
# 不进 make test / platform-up / platform-seed（AC-14）：重置会删掉业务实例数据，
# 只能由人（或演示前的一次手工动作）触发。
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

FAIL=0
CASE=0
mkdir -p "$(run_log_dir)"

pass() { CASE=$((CASE + 1)); printf '  \033[1;32mPASS\033[0m [%02d] %s\n' "$CASE" "$1"; }
fail() { CASE=$((CASE + 1)); FAIL=$((FAIL + 1)); printf '  \033[1;31mFAIL\033[0m [%02d] %s\n' "$CASE" "$1"; printf '       修复: %s\n' "$2"; }

# ---------------------------------------------------------------- 前置

if ! req_ready; then
    err "req-service 未就绪: ${REQ_URL}/internal/health"
    err "修复: 先执行 make platform-up（重置复用演示栈，不隐式起栈、不 SKIP）"
    exit 1
fi

TENANT_CODE="${PLATFORM_TENANT:-t1}"
TENANT_ID="$(db_q "SELECT id FROM sys_tenant WHERE code = '${TENANT_CODE}'" | head -n1)"
if [[ -z "$TENANT_ID" ]]; then
    err "租户 ${TENANT_CODE} 不存在"
    err "修复: 先执行 make platform-seed（重置只清业务实例数据，不造账号/域/厂商）"
    exit 1
fi

# ---------------------------------------------------------------- 调用

reset_once() {
    internal_call POST "$REQ_URL" /internal/demo-reset
    if [[ "${HTTP_CODE}" != "200" ]]; then
        err "POST /internal/demo-reset 失败 (HTTP ${HTTP_CODE}): $(printf '%s' "${HTTP_BODY}" | head -c 400)"
        err "修复: 404 = PLATFORM_SEED_ENABLED=false（platform-up 已置 true）；403 = X-Internal-Token 不匹配；"
        err "      409 DEMO_RESET_INVARIANT = 不变量不满足，事务已整体回滚（按上面列出的差异改 DemoFixture）"
        exit 1
    fi
    json_ok "${HTTP_BODY}" || { err "响应不是 JSON: $(printf '%s' "${HTTP_BODY}" | head -c 300)"; exit 1; }
}

render_table() {
    printf '\n  %-22s %s\n' "表名 / 指标" "行数"
    printf '  %s\n' "------------------------------------------"
    printf '%s' "$1" | jq -r '.counts | to_entries[] | "\(.key) \(.value)"' \
        | while read -r key value; do printf '  %-22s %s\n' "${key}" "${value}"; done
    printf '  %s\n\n' "------------------------------------------"
}

# 集成配置是租户级定义数据，重置前后必须原样保留（DELETE_ORDER 不含 api_client，D-02）
PRE_CLIENTS="$(db_count api_client "tenant_id = ${TENANT_ID}")"

log "第一次重置（tenant=${TENANT_CODE}）"
reset_once
FIRST="${HTTP_BODY}"
render_table "${FIRST}"

log "第二次重置（幂等：连跑两次计数必须逐键相等）"
reset_once
SECOND="${HTTP_BODY}"

C1="$(printf '%s' "${FIRST}" | jq -cS '.counts')"
C2="$(printf '%s' "${SECOND}" | jq -cS '.counts')"
[[ "${C1}" == "${C2}" ]] \
    && pass "两遍重置的计数表逐键一致（幂等）" \
    || fail "两遍重置计数不一致: ${C1} != ${C2}" "DemoFixture 的 code 必须固定；重置要先删后插，不能依赖自增 id"

# ---------------------------------------------------------------- 不变量（AC-13）

check_count() {
    local key="$1" want="$2" label="$3" got
    got="$(printf '%s' "${C1}" | jq -r ".${key} // empty")"
    [[ "${got}" == "${want}" ]] \
        && pass "${label}: ${got}" \
        || fail "${label}: 实际 ${got:-<缺失>}，期望 ${want}" "核对 DemoFixture 的期望常量与插入条数"
}

check_count requirements   12 "需求 12 条（小链路）"
check_count releases        1 "版本 1 个"
check_count milestones      1 "里程碑 1 个（甘特版本视图）"
check_count sprints         1 "迭代 1 个"
check_count tasks           8 "任务 8 行（甘特任务视图）"
check_count testCases      12 "用例 12 条"
check_count testPlans       1 "测试计划 1 个"
check_count planCases      10 "计划-用例 10 条（≥10 才可执行）"
check_count defects         3 "缺陷 3 条"
check_count po1Visible      8 "隔离基线 po1 可见需求 = 8"
check_count vendorAVisible  3 "隔离基线 vendor1（VEN-A）可见需求 = 3"
check_count vendorBVisible  0 "隔离基线 vendor2（VEN-B）可见需求 = 0"
check_count fixtureTraces   0 "夹具痕迹（s1-/s5-/s7-/neg-sm-）= 0"
check_count actualDaysOver30 0 "人天 > 30 的残留 = 0"
check_count overdueGanttRows 0 "甘特逾期行 = 0"

NOTIFY="$(printf '%s' "${C1}" | jq -r '.notifications // empty')"
[[ -n "${NOTIFY}" && "${NOTIFY}" -le 5 ]] \
    && pass "通知 ${NOTIFY} 条（上限 5，修复前是 99+）" \
    || fail "通知 ${NOTIFY:-<缺失>} 条，超过上限 5" "DemoFixture.NOTIFICATIONS 只能保留演示需要的那几条"

# ---------------------------------------------------------------- 库内复核（db_q 口径）

DEMO_REQS="$(db_count requirement "tenant_id = ${TENANT_ID} AND code LIKE 'DEMO-R-%'")"
[[ "${DEMO_REQS}" == "12" ]] \
    && pass "库内 DEMO-R-* 需求 12 行" \
    || fail "库内 DEMO-R-* 需求 ${DEMO_REQS} 行，期望 12" "重置未把所有固定行插入（看服务日志）"

SUPPLY_REQS="$(db_count requirement "tenant_id = ${TENANT_ID} AND origin_org_id IN (SELECT id FROM sys_org WHERE tenant_id = ${TENANT_ID} AND code = 'SUPPLY')")"
[[ "${SUPPLY_REQS}" == "8" ]] \
    && pass "库内 po1 组织（SUPPLY）提报需求 8 行" \
    || fail "库内 SUPPLY 提报需求 ${SUPPLY_REQS} 行，期望 8" "8/3/1 的分布是隔离基线的前提，改数据要同步改断言"

KEPT_USERS="$(db_count sys_user "tenant_id = ${TENANT_ID}")"
KEPT_DOMAINS="$(db_count biz_domain "tenant_id = ${TENANT_ID}")"
KEPT_VENDORS="$(db_count sys_vendor "tenant_id = ${TENANT_ID}")"
[[ "${KEPT_USERS}" == "12" && "${KEPT_DOMAINS}" == "7" && "${KEPT_VENDORS}" == "3" ]] \
    && pass "定义数据保留：账号 12 / 域 7 / 厂商 3" \
    || fail "定义数据被误删：账号 ${KEPT_USERS} / 域 ${KEPT_DOMAINS} / 厂商 ${KEPT_VENDORS}" \
            "DELETE_ORDER 只能包含业务实例表，账号/域/厂商属定义数据"

# 协作域定义数据（V9 ⑤ 种子）与集成配置：重置不重建它们，删掉演示页就是空白（D-02）
KEPT_POLICIES="$(db_count branch_policy "tenant_id = ${TENANT_ID}")"
KEPT_EXECUTORS="$(db_count test_executor "tenant_id = ${TENANT_ID}")"
KEPT_CLIENTS="$(db_count api_client "tenant_id = ${TENANT_ID}")"
[[ "${KEPT_POLICIES:-0}" -ge 1 ]] \
    && pass "定义数据保留：分支策略 ${KEPT_POLICIES} 条（V9 ⑤ 种子）" \
    || fail "分支策略被重置清空（${KEPT_POLICIES:-0} 条）" "branch_policy 是定义数据：从 DELETE_ORDER 移除，不能删了又不重放"
[[ "${KEPT_EXECUTORS:-0}" -ge 2 ]] \
    && pass "定义数据保留：执行器 ${KEPT_EXECUTORS} 条（STUB-LOCAL / LEGACY-OFF）" \
    || fail "执行器台账被重置清空（${KEPT_EXECUTORS:-0} 条）" "test_executor 是定义数据：从 DELETE_ORDER 移除，不能删了又不重放"
[[ "${KEPT_CLIENTS:-0}" == "${PRE_CLIENTS:-0}" ]] \
    && pass "集成配置保留：api_client 重置前后均 ${KEPT_CLIENTS} 条" \
    || fail "api_client 被重置改写（前 ${PRE_CLIENTS:-?} → 后 ${KEPT_CLIENTS:-?}）" "api_client 属集成配置，DELETE_ORDER 不得包含"

# 演示页的数据面：重置脚本自称「可直接演示」，这两个页面必须真的有数据
platform_login "${TENANT_CODE}" admin 'Admin@12345' || fail "admin 登录失败" "账号由 platform-seed 创建；重置不删账号"
api_call GET "/api/req/code/policies"
POLICY_ROWS="$(printf '%s' "${HTTP_BODY}" | jq 'length' 2>/dev/null || echo 0)"
[[ "${HTTP_CODE}" == "200" && "${POLICY_ROWS:-0}" -ge 1 ]] \
    && pass "分支策略页接口非空（${POLICY_ROWS} 条）" \
    || fail "分支策略页接口为空（HTTP ${HTTP_CODE}，${POLICY_ROWS:-0} 条）" "重置后 /code-policies 必须是可演示状态"
api_call GET "/api/deliver/test-executors"
EXEC_ROWS="$(jget "${HTTP_BODY}" '.total' 2>/dev/null || echo 0)"
[[ "${HTTP_CODE}" == "200" && "${EXEC_ROWS:-0}" -ge 2 ]] \
    && pass "执行器页接口非空（${EXEC_ROWS} 条）" \
    || fail "执行器页接口为空（HTTP ${HTTP_CODE}，${EXEC_ROWS:-0} 条）" "重置后 /test-executors 必须是可演示状态"

# ---------------------------------------------------------------- 真实登录复核（端到端）

check_visible() { # check_visible <user> <expected>
    local user="$1" expected="$2" total
    platform_login "${TENANT_CODE}" "${user}" 'Passw0rd!' || {
        fail "${user} 登录失败" "账号由 platform-seed 创建；重置不删账号"
        return
    }
    api_call GET "/api/req/requirements?page=1&size=1"
    [[ "${HTTP_CODE}" == "200" ]] || {
        fail "${user} 查需求列表 (HTTP ${HTTP_CODE})" "检查网关与 token/tenant 头"
        return
    }
    total="$(jget "${HTTP_BODY}" '.total')"
    [[ "${total}" == "${expected}" ]] \
        && pass "${user} 需求列表可见 ${total} 条（期望 ${expected}）" \
        || fail "${user} 需求列表可见 ${total:-<缺失>} 条，期望 ${expected}" \
                "隔离口径：po1=biz1/suite1 按 origin 组织子树，vendor 按 owner/assignee 厂商"
}

check_visible po1 8
check_visible vendor1 3
check_visible vendor2 0
API_TOKEN=""
API_TENANT=""

# ---------------------------------------------------------------- 结果

if (( FAIL > 0 )); then
    err "platform-demo-reset: ${CASE} 项中 ${FAIL} 项失败"
    exit 1
fi
ok "platform-demo-reset: ${CASE}/${CASE} 全部通过（演示数据已重建，可直接演示）"
