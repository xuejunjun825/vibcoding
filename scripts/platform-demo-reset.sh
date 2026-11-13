#!/usr/bin/env bash
# platform-demo-reset.sh — 演示数据重置（PRD AC-13 / 技术方案 §6.2）。
#
# 做三件事：
#   1. 调内部端点 POST /internal/demo-reset，把租户的业务实例数据清空并重建「12 需求小链路」；
#   2. 把响应里的计数表（表名 → 行数 + 不变量实测值）对齐打印，人工一眼看清演示态；
#   3. 复核硬口径：不变量 12 / 8 / 3 / 0、夹具痕迹清零、连跑两次 counts 逐键相等（幂等），
#      再用 po1 / vendor1 / vendor2 真实登录各查一次需求列表，证明隔离边界不是只写在 SQL 里。
#   4. r15：漏删的四张表（report_definition / portal_card_config / api_client / test_executor）
#      统一由 DemoFixture.DELETE_ORDER(+keepFilter) 决定去留，本脚本只做「搬运 counts + 独立复核
#      库/API 现实」——它不拥有表清单，也不维护第二套保留判断（PRE_CLIENTS 前后快照已整体删除）。
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

# 保留策略只有一份实现：DemoFixture.DELETE_ORDER(+keepFilter)。脚本不再拍「重置前快照」
# 再比「重置后相等」——那是 bash 里的第二套保留判断，会和服务端序列给出矛盾结论。

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

# 定义数据残留（r15）：这四张表以前不在 DELETE_ORDER 里，重置后仍带着上一轮的报表/门户/客户端痕迹
check_count reports        0 "报表定义（report_definition）= 0"
check_count portalCards    0 "门户卡片配置（portal_card_config）= 0"
check_count portalPins     0 "门户个人 pin（user_id <> 0）= 0"
check_count executors      2 "执行器保留 2 条（STUB-LOCAL / LEGACY-OFF）"
check_count apiClients     0 "开放客户端（api_client）= 0"
# probeReports 是 reports=0 的子集：只作「251 条探针确实清空」的诊断，不得据它推导别的期望
check_count probeReports   0 "报表探针残留 = 0（诊断信号）"

# 运行时残留（r15-fix / QA D-01+D-02）：这三张表都不是「整表不进序列」能挡住的污染——
# test_strategy 是 release 的派生实例（209 行孤儿），其余三张是门禁按 RUN_TAG 命名的行
# （verify-wf-<hhmmss> / neg-L1-<hhmmss> / 验证模块L1-<hhmmss> / QA环境-<hhmmss>）。
# 键名与 DemoFixture 的常量一一对应，期望值仍只有服务端一处拥有（这里只做跨进程搬运）。
check_count testStrategies 0 "测试策略（派生自 release，重置后为 0）"
check_count gateResidue    0 "门禁 RUN_TAG 残留（wf_definition/test_module/test_env 合计）= 0"

NOTIFY="$(printf '%s' "${C1}" | jq -r '.notifications // empty')"
[[ -n "${NOTIFY}" && "${NOTIFY}" -eq 4 ]] \
    && pass "通知 ${NOTIFY} 条（精确 4 条，修复前是 99+）" \
    || fail "通知 ${NOTIFY:-<缺失>} 条，期望 4" "DemoFixture.NOTIFICATION_COUNT = 4：通知必须精确，不是「不超过」"

# ---------------------------------------------------------------- 库内复核（db_q 口径）

DEMO_REQS="$(db_count requirement "tenant_id = ${TENANT_ID} AND code LIKE 'DEMO-R-%'")"
[[ "${DEMO_REQS}" == "12" ]] \
    && pass "库内 DEMO-R-* 需求 12 行" \
    || fail "库内 DEMO-R-* 需求 ${DEMO_REQS} 行，期望 12" "重置未把所有固定行插入（看服务日志）"

SUPPLY_REQS="$(db_count requirement "tenant_id = ${TENANT_ID} AND origin_org_id IN (SELECT id FROM sys_org WHERE tenant_id = ${TENANT_ID} AND code = 'SUPPLY')")"
[[ "${SUPPLY_REQS}" == "8" ]] \
    && pass "库内 po1 组织（SUPPLY）提报需求 8 行" \
    || fail "库内 SUPPLY 提报需求 ${SUPPLY_REQS} 行，期望 8" "8/3/1 的分布是隔离基线的前提，改数据要同步改断言"

# 账号断言的是「种子账号仍然存在」，不是「tenant 内用户总数恰好 N」：权限/账号类门禁
# 会在同一个 t1 租户里加人（如 T1 devcheck），用总数会把别人的账号算成种子缺陷。
# 名单与 SeedResult.loginUsers() 同源（20261110-r9 起 13 个，含 mgr1）。
KEPT_USERS="$(db_count sys_user "tenant_id = ${TENANT_ID} AND username IN ('admin','pm','tl1','po1','biz1','suite1','dev1','dev2','qa1','qa2','vendor1','vendor2','mgr1')")"
KEPT_DOMAINS="$(db_count biz_domain "tenant_id = ${TENANT_ID}")"
KEPT_VENDORS="$(db_count sys_vendor "tenant_id = ${TENANT_ID}")"
[[ "${KEPT_USERS}" == "13" && "${KEPT_DOMAINS}" == "7" && "${KEPT_VENDORS}" == "3" ]] \
    && pass "定义数据保留：种子账号 13 / 域 7 / 厂商 3" \
    || fail "定义数据被误删：种子账号 ${KEPT_USERS} / 域 ${KEPT_DOMAINS} / 厂商 ${KEPT_VENDORS}" \
            "DELETE_ORDER 只能包含业务实例表，账号/域/厂商属定义数据"

# 协作域定义数据（V9 ⑤ 种子）：重置不重建，删掉演示页就是空白
KEPT_POLICIES="$(db_count branch_policy "tenant_id = ${TENANT_ID}")"
[[ "${KEPT_POLICIES:-0}" -ge 1 ]] \
    && pass "定义数据保留：分支策略 ${KEPT_POLICIES} 条（V9 ⑤ 种子）" \
    || fail "分支策略被重置清空（${KEPT_POLICIES:-0} 条）" "branch_policy 是定义数据：从 DELETE_ORDER 移除，不能删了又不重放"

# r15 漏删的 4 张表：库内现实复核（counts 是服务端自报，这里直接问数据库）
DB_REPORTS="$(db_count report_definition "tenant_id = ${TENANT_ID}")"
DB_PORTAL_CARDS="$(db_count portal_card_config "tenant_id = ${TENANT_ID}")"
DB_CLIENTS="$(db_count api_client "tenant_id = ${TENANT_ID}")"
DB_EXECUTORS="$(db_count test_executor "tenant_id = ${TENANT_ID}")"
[[ "${DB_REPORTS}" == "0" ]] \
    && pass "库内 report_definition = 0 行" \
    || fail "库内 report_definition ${DB_REPORTS} 行，期望 0" "报表定义属实例数据：DELETE_ORDER 必须整表清空，否则 /reports 留着上一轮的探针"
[[ "${DB_PORTAL_CARDS}" == "0" ]] \
    && pass "库内 portal_card_config = 0 行" \
    || fail "库内 portal_card_config ${DB_PORTAL_CARDS} 行，期望 0" "门户卡片配置属实例数据：角色模板由代码提供，个人 pin 必须清掉"
[[ "${DB_CLIENTS}" == "0" ]] \
    && pass "库内 api_client = 0 行（无种子可重放，全清）" \
    || fail "库内 api_client ${DB_CLIENTS} 行，期望 0" "api_client 不在 V9 种子里，删了没人重放 → DELETE_ORDER 必须无条件清空它"
[[ "${DB_EXECUTORS}" == "2" ]] \
    && pass "库内 test_executor = 2 行（恰为保留的两条）" \
    || fail "库内 test_executor ${DB_EXECUTORS} 行，期望 2" "test_executor 靠 DELETE_ORDER 的 keepFilter 保留，多删/少删都在这里暴露"
# 迁移种子若改名，keepFilter 会静默匹配不到而执行器被删——只验 ==2 条不够，必须验 code 字面量
EXEC_CODES="$(db_q "SELECT GROUP_CONCAT(code ORDER BY code) FROM test_executor WHERE tenant_id = ${TENANT_ID}" | head -n1)"
[[ "${EXEC_CODES}" == "LEGACY-OFF,STUB-LOCAL" ]] \
    && pass "执行器 code 恰为 LEGACY-OFF / STUB-LOCAL" \
    || fail "执行器 code = '${EXEC_CODES:-<空>}'，期望 'LEGACY-OFF,STUB-LOCAL'" \
            "V9 种子改名后 keepFilter（code IN ...）会失配：同步 DemoFixture.purgeExcept 的字面量"
# keepFilter 只保住行、不保住字段：s7 门禁和手工上报都会把 STUB-LOCAL 的 last_report_at 改成 NOW，
# 行留下来但健康态从 NEVER 变成 ONLINE，演示台账就再也回不到种子事实。重置必须把字段也还原。
EXEC_BASELINE="$(db_q "SELECT GROUP_CONCAT(CONCAT(code, ':', enabled, ':', IFNULL(last_report_at, 'NULL'), ':', report_count) ORDER BY code) FROM test_executor WHERE tenant_id = ${TENANT_ID}" | head -n1)"
[[ "${EXEC_BASELINE}" == "LEGACY-OFF:0:NULL:0,STUB-LOCAL:1:NULL:0" ]] \
    && pass "保留执行器已还原种子基线（STUB-LOCAL 从未上报 / LEGACY-OFF 停用）" \
    || fail "保留执行器基线 = '${EXEC_BASELINE:-<空>}'，期望 'LEGACY-OFF:0:NULL:0,STUB-LOCAL:1:NULL:0'" \
            "DemoFixture.RESTORE_KEPT_EXECUTOR_BASELINE 必须把 last_report_at/report_count/enabled 清回种子值"

# r15-fix（QA D-01/D-02）：残留复核不靠 counts 自报，直接问库——上一轮门禁写的行
# 是否真的没了、种子行是否还在。谓词与 DemoFixture.GATE_RUN_TAG_RESIDUE 同源（跨进程只能各写一份）。
GATE_RESIDUE_WHERE="tenant_id = ${TENANT_ID} AND (name LIKE 'verify-%' OR name LIKE 'neg-%' OR name LIKE '验证模块%' OR name LIKE 'QA环境-%')"
DB_STRATEGIES="$(db_count test_strategy "tenant_id = ${TENANT_ID}")"
DB_STRATEGY_ORPHANS="$(db_count test_strategy "tenant_id = ${TENANT_ID} AND release_id NOT IN (SELECT id FROM \`release\` WHERE tenant_id = ${TENANT_ID})")"
DB_WF_RESIDUE="$(db_count wf_definition "${GATE_RESIDUE_WHERE}")"
DB_MODULE_RESIDUE="$(db_count test_module "${GATE_RESIDUE_WHERE}")"
DB_ENV_RESIDUE="$(db_count test_env "${GATE_RESIDUE_WHERE}")"
DB_WF_ROWS="$(db_count wf_definition "tenant_id = ${TENANT_ID}")"
DB_MODULE_ROWS="$(db_count test_module "tenant_id = ${TENANT_ID}")"
DB_ENV_ROWS="$(db_count test_env "tenant_id = ${TENANT_ID}")"
[[ "${DB_STRATEGIES}" == "0" ]] \
    && pass "库内 test_strategy = 0 行（派生数据无种子，重置后必须清空）" \
    || fail "库内 test_strategy ${DB_STRATEGIES} 行，期望 0（其中孤儿 ${DB_STRATEGY_ORPHANS} 行）" \
            "test_strategy 只由 V5 按既有 release 回填一次：release 删光后留一行就是一行孤儿"
[[ "${DB_STRATEGY_ORPHANS}" == "0" ]] \
    && pass "库内 test_strategy 无孤儿（release_id 全部可解析）" \
    || fail "库内 test_strategy 有 ${DB_STRATEGY_ORPHANS} 行指向已删 release" "DemoFixture.DELETE_ORDER 里 test_strategy 必须排在 release 之前"
[[ "${DB_WF_RESIDUE}" == "0" && "${DB_MODULE_RESIDUE}" == "0" && "${DB_ENV_RESIDUE}" == "0" ]] \
    && pass "库内门禁 RUN_TAG 残留 = 0（wf ${DB_WF_ROWS} / module ${DB_MODULE_ROWS} / env ${DB_ENV_ROWS} 行只剩种子与人工行）" \
    || fail "库内门禁残留：wf_definition ${DB_WF_RESIDUE} / test_module ${DB_MODULE_RESIDUE} / test_env ${DB_ENV_RESIDUE} 行" \
            "三张种子表由 purgeRunTagResidue 只清 RUN_TAG 命中行：残留不为 0 = 过滤谓词与门禁命名脱节"
[[ "${DB_WF_ROWS:-0}" -ge 4 && "${DB_MODULE_ROWS:-0}" -ge 3 && "${DB_ENV_ROWS:-0}" -ge 1 ]] \
    && pass "种子行仍在：流程定义 ${DB_WF_ROWS} / 模块 ${DB_MODULE_ROWS} / 环境台账 ${DB_ENV_ROWS}" \
    || fail "种子行被误删：流程定义 ${DB_WF_ROWS:-0}（期望≥4）/ 模块 ${DB_MODULE_ROWS:-0}（期望≥3）/ 环境 ${DB_ENV_ROWS:-0}（期望≥1）" \
            "这三张表的种子由 SeedService 种下、重置不重放：删了页面就是空白（D-02 的故障形态）"

# 演示页的数据面：重置脚本自称「可直接演示」，这两个页面必须真的有数据
platform_login "${TENANT_CODE}" admin 'Admin@12345' || fail "admin 登录失败" "账号由 platform-seed 创建；重置不删账号"
api_call GET "/api/req/code/policies"
POLICY_ROWS="$(printf '%s' "${HTTP_BODY}" | jq 'length' 2>/dev/null || echo 0)"
[[ "${HTTP_CODE}" == "200" && "${POLICY_ROWS:-0}" -ge 1 ]] \
    && pass "分支策略页接口非空（${POLICY_ROWS} 条）" \
    || fail "分支策略页接口为空（HTTP ${HTTP_CODE}，${POLICY_ROWS:-0} 条）" "重置后 /code-policies 必须是可演示状态"
api_call GET "/api/deliver/test-executors"
EXEC_ROWS="$(jget "${HTTP_BODY}" '.total' 2>/dev/null || echo 0)"
[[ "${HTTP_CODE}" == "200" && "${EXEC_ROWS:-0}" == "2" ]] \
    && pass "执行器页接口恰 2 条（${EXEC_ROWS} 条）" \
    || fail "执行器页接口 (HTTP ${HTTP_CODE}，${EXEC_ROWS:-0} 条)" "重置后 /test-executors 必须恰为保留的两条执行器"

# r15：报表列表与门户配置直读 DB（不吃 deliver 的 AnalyticsCache），所以适合做 API 级复核
api_call GET "/api/deliver/reports?page=1&size=1"
REPORT_TOTAL="$(jget "${HTTP_BODY}" '.total' 2>/dev/null || echo '')"
[[ "${HTTP_CODE}" == "200" && "${REPORT_TOTAL:-x}" == "0" ]] \
    && pass "报表列表 total = 0（${REPORT_TOTAL}）" \
    || fail "报表列表 total = ${REPORT_TOTAL:-<缺失>}，期望 0（HTTP ${HTTP_CODE}）" \
            "report_definition 属实例数据：DELETE_ORDER 整表清空后 /reports 必须是空态"

api_call GET "/api/deliver/portal/config"
PORTAL_CARDS="$(jget "${HTTP_BODY}" '.cards | length' 2>/dev/null || echo '')"
PORTAL_REPORT_KEYS="$(jget "${HTTP_BODY}" '[.cardKeys[] | select(startswith("report:"))] | length' 2>/dev/null || echo '')"
[[ "${HTTP_CODE}" == "200" && "${PORTAL_CARDS:-x}" == "4" ]] \
    && pass "门户配置 cards = 4（admin 角色模板）" \
    || fail "门户配置 cards = ${PORTAL_CARDS:-<缺失>}，期望 4（HTTP ${HTTP_CODE}）" \
            "portal_card_config 清空后必须回退到角色模板：admin 模板固定 4 张卡"
[[ "${HTTP_CODE}" == "200" && "${PORTAL_REPORT_KEYS:-x}" == "0" ]] \
    && pass "门户配置无 report: 前缀卡片（个人 pin 证据在 portalPins=0）" \
    || fail "门户配置仍有 ${PORTAL_REPORT_KEYS:-?} 个 report: 卡片（HTTP ${HTTP_CODE}）" \
            "config 只由角色模板构造，出现 report: 说明个人 pin 行没被清掉"

# r15-fix（QA D-02）：这两页直读 DB、不吃缓存，适合做 API 级复核——
# 重置后流程定义页与用例模块树不得再出现上一轮门禁的 RUN_TAG 痕迹。
api_call GET "/api/req/admin/workflows"
WF_TOTAL="$(printf '%s' "${HTTP_BODY}" | jq 'length' 2>/dev/null || echo '')"
WF_RESIDUE="$(printf '%s' "${HTTP_BODY}" | jq '[.[] | select(.name | test("^(verify-|neg-)"))] | length' 2>/dev/null || echo '')"
[[ "${HTTP_CODE}" == "200" && "${WF_RESIDUE:-x}" == "0" ]] \
    && pass "流程定义接口无门禁残留（共 ${WF_TOTAL} 条）" \
    || fail "流程定义接口仍有 ${WF_RESIDUE:-?} 条 verify-/neg- 残留（HTTP ${HTTP_CODE}，共 ${WF_TOTAL:-?} 条）" \
            "wf_definition 的种子行要留、RUN_TAG 探针行要删：见 DemoFixture.GATE_RUN_TAG_RESIDUE"
api_call GET "/api/deliver/test-modules"
MOD_TOTAL="$(printf '%s' "${HTTP_BODY}" | jq 'length' 2>/dev/null || echo '')"
MOD_RESIDUE="$(printf '%s' "${HTTP_BODY}" | jq '[.[] | select(.name | test("^(验证模块|neg-)"))] | length' 2>/dev/null || echo '')"
[[ "${HTTP_CODE}" == "200" && "${MOD_RESIDUE:-x}" == "0" ]] \
    && pass "用例模块树无门禁残留（共 ${MOD_TOTAL} 条）" \
    || fail "用例模块树仍有 ${MOD_RESIDUE:-?} 条 验证模块*/neg-* 残留（HTTP ${HTTP_CODE}，共 ${MOD_TOTAL:-?} 条）" \
            "test_module 的种子模块树要留、门禁探针模块要删"

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
