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
#   5. r16：report_definition 从「清空后为 0」改为「清空后回填 2 张演示报表」，因此三处断言从
#      0 改成 2 + 名称集合，并在末尾用真实登录 run/export 自证两张表开箱可用（AC-1/AC-3/AC-5）。
#
# 不进 make test / platform-up / platform-seed（AC-14）：重置会删掉业务实例数据，
# 只能由人（或演示前的一次手工动作）触发。
#
# 20261114-r20（BUG-3 / AC-9、AC-10）：失败必须可诊断、可重入——
#   * 分类：transport（连接被重置/不可达/超时）/ api（4xx，服务端已应答但拒绝）/ assert（数据不符）；
#   * 传输层失败立刻停：打印「停止项 + case + 已执行/未执行清单 + 重跑命令」，退出码恒非 0；
#   * 断言层失败继续跑完（一次看全所有差异），末尾同样打印分类与清单；
#   * 脚本内**不重试、不 sleep**：重试只发生在网关（仅幂等 GET、仅 1 次），脚本若静默重试
#     会把「间歇失败」掩盖成「偶尔慢」。
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

FAIL=0
CASE=0
# 本脚本固定 57 项断言（AC-9 的「57/57」口径）。断言只增：新增/删除断言时同步改这里，
# 末尾会校验实际执行数 == 声明数，漂移直接红——否则「57/57」会变成一句没人维护的祝福。
TOTAL_CHECKS=61
FAIL_TRANSPORT=0
FAIL_API=0
FAIL_ASSERT=0
# 段清单只服务失败时的「未执行清单」：哪一段没跑到，就是那一段的检查全没执行。
SECTIONS=("计数表与幂等" "库内复核" "接口复核" "可见性复核" "授权回预设")
SECTION_IDX=0
mkdir -p "$(run_log_dir)"

pass() { CASE=$((CASE + 1)); printf '  \033[1;32mPASS\033[0m [%02d] %s\n' "$CASE" "$1"; }
fail() { CASE=$((CASE + 1)); FAIL=$((FAIL + 1)); printf '  \033[1;31mFAIL\033[0m [%02d] %s\n' "$CASE" "$1"; printf '       修复: %s\n' "$2"; }

section() { # section <1-based index>：打印段头，供失败清单定位「断在第几段」
    SECTION_IDX="$1"
    printf '\n\033[1m==> 段 %d/%d: %s\033[0m\n' "$SECTION_IDX" "${#SECTIONS[@]}" "${SECTIONS[$((SECTION_IDX - 1))]}"
}

# 失败分类：传输层（连接被重置/不可达/超时）→ transport；4xx → api；其余 → assert。
# 只在「服务端/网络已经给了答案」的调用点使用；纯数据比对（db 查询）走 fail 即可。
failure_case() {
    case "${1:-}" in
        000|502|503|504) printf 'transport'; return ;;
    esac
    # 用 grep 读尽整条流（不用 -q）：pipefail 下 -q 提前退出会让 printf 收 SIGPIPE(141)，
    # 把「确实含 Connection reset」误判成 assert。
    if printf '%s' "${2:-}" | grep -iE 'connection reset|connection refused|timed out|prematureclose|sockettimeoutexception' >/dev/null; then
        printf 'transport'; return
    fi
    if [[ "${1:-}" =~ ^4[0-9][0-9]$ ]]; then printf 'api'; return; fi
    printf 'assert'
}

unexecuted_list() {
    local i out=""
    for ((i = SECTION_IDX + 1; i <= ${#SECTIONS[@]}; i++)); do
        out+="${out:+/}${SECTIONS[$((i - 1))]}"
    done
    printf '%s' "${out:-无（已是最后一段）}"
}

# fail_stop <停止项> <case> <http_code> <body> [提示]：传输层/致命错误的唯一出口。
# 打印停止项、分类、已执行/未执行清单与重跑命令，然后恒 exit 1（不吞错、不重试）。
fail_stop() {
    local what="$1" kind="$2" code="${3:-?}" body="${4:-}" hint="${5:-}"
    err "停止项: ${what}（case=${kind}，HTTP ${code}）"
    case "${kind}" in
        transport)
            err "  归类: 传输层——网关/服务连接被重置或不可达，不是数据面问题"
            err "  排查: logs/api-gateway.log 的 HttpClientConnect、logs/deliver-service.log 的 Thread starvation"
            err "        先 make platform-up 或等 deliver JVM 恢复；重置幂等，恢复后可直接重跑" ;;
        api)
            err "  归类: 接口层——服务端已应答但拒绝了请求：$(printf '%s' "${body}" | head -c 300)" ;;
        *)
            err "  归类: 断言层——接口已应答，但内容与期望不符：$(printf '%s' "${body}" | head -c 300)" ;;
    esac
    if [[ -n "${hint}" ]]; then err "  ${hint}"; fi
    err "已执行 ${CASE} 项（PASS $((CASE - FAIL)) / FAIL ${FAIL}）/ 未执行 $((TOTAL_CHECKS - CASE)) 项（共 ${TOTAL_CHECKS}）"
    err "未执行清单: $(unexecuted_list)"
    err "重跑: bash scripts/platform-demo-reset.sh（重置幂等，可直接重跑）"
    exit 1
}

# fail_http <名称> <提示>：对刚返回的 HTTP_CODE/HTTP_BODY 分类后记账（不更新 CASE 之外的语义）。
# 传输层直接停（后续检查已无意义），api/assert 记 FAIL 继续跑完，末尾统一汇总。
fail_http() {
    local label="$1" hint="$2" kind
    kind="$(failure_case "${HTTP_CODE}" "${HTTP_BODY}")"
    case "${kind}" in
        transport) FAIL_TRANSPORT=$((FAIL_TRANSPORT + 1)); fail_stop "${label}" transport "${HTTP_CODE}" "${HTTP_BODY}" "${hint}" ;;
        api)       FAIL_API=$((FAIL_API + 1)) ;;
        *)         FAIL_ASSERT=$((FAIL_ASSERT + 1)) ;;
    esac
    fail "${label}（case=${kind}，HTTP ${HTTP_CODE}）" "${hint}"
}

# ---------------------------------------------------------------- 前置

# 前置也算「停止项」：断栈时最先失败的就是这里，必须同样给出 case=transport 与重跑命令，
# 而不是只丢一句「未就绪」。
if ! req_ready; then
    fail_stop "req-service 就绪 (${REQ_URL}/internal/health)" transport 000 "" \
        "先执行 make platform-up（重置复用演示栈，不隐式起栈、不 SKIP）"
fi
if ! db_up; then
    fail_stop "MySQL 就绪 (${PLATFORM_DB_HOST}:${PLATFORM_DB_PORT})" transport 000 "" \
        "数据库不可达：先 make platform-up；重置的全部断言都要直连库复核"
fi

TENANT_CODE="${PLATFORM_TENANT:-t1}"
TENANT_ID="$(db_q "SELECT id FROM sys_tenant WHERE code = '${TENANT_CODE}'" | head -n1)"
if [[ -z "$TENANT_ID" ]]; then
    fail_stop "租户 ${TENANT_CODE} 存在" assert "${HTTP_CODE:-?}" "" \
        "先执行 make platform-seed（重置只清业务实例数据，不造账号/域/厂商）"
fi

# r16 演示报表（AC-1/AC-2）：脚本侧的规范名集合。服务端 DemoFixture.REPORT_NAMES 是另一份
# （跨进程只能各写一份），两侧各自内部一致即可：中文名称排序不可依赖 MySQL collation，所以
# 库内用 name IN 命中数、接口用 `jq sort` 规范化，绝不比较任何一方的原始顺序。
DEMO_REPORTS=('需求状态分布' '厂商交付概览')
DEMO_REPORT_NAMES_SQL="'${DEMO_REPORTS[0]}','${DEMO_REPORTS[1]}'"
DEMO_REPORT_NAMES_EXPECTED="$(jq -rn --args '$ARGS.positional | sort | join(",")' --args "${DEMO_REPORTS[@]}")"

# ---------------------------------------------------------------- 调用

reset_once() {
    internal_call POST "$REQ_URL" /internal/demo-reset
    if [[ "${HTTP_CODE}" != "200" ]]; then
        local kind
        kind="$(failure_case "${HTTP_CODE}" "${HTTP_BODY}")"
        fail_stop "POST /internal/demo-reset" "${kind}" "${HTTP_CODE}" "${HTTP_BODY}" \
            "404 = PLATFORM_SEED_ENABLED=false（platform-up 已置 true）；403 = X-Internal-Token 不匹配；409 DEMO_RESET_INVARIANT = 不变量不满足，事务已整体回滚（按响应差异改 DemoFixture）"
    fi
    if ! json_ok "${HTTP_BODY}"; then
        fail_stop "POST /internal/demo-reset 响应是 JSON" assert "${HTTP_CODE}" "${HTTP_BODY}" \
            "响应不是 JSON：先看 req-service.log，再确认网关/反代没有截断包体"
    fi
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

section 1
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
# r16：report_definition 不再收到 0——清表后立刻回填 2 张演示报表（AC-1），其余三张仍应为 0。
check_count reports        2 "报表定义（report_definition）= 2 张演示报表"
check_count portalCards    0 "门户卡片配置（portal_card_config）= 0"
check_count portalPins     0 "门户个人 pin（user_id <> 0）= 0"
check_count executors      2 "执行器保留 2 条（STUB-LOCAL / LEGACY-OFF）"
check_count apiClients     0 "开放客户端（api_client）= 0"
# probeReports 是 reports=2 上的探针过滤：只作「251 条探针确实清空」的诊断，不得据它推导别的期望
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

section 2
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
# 名单与 SeedResult.loginUsers() 同源（20261110-r9 起 13 个，含 mgr1；r24 加 vpm1 共 14 个）。
KEPT_USERS="$(db_count sys_user "tenant_id = ${TENANT_ID} AND username IN ('admin','pm','tl1','po1','biz1','suite1','dev1','dev2','qa1','qa2','vendor1','vendor2','mgr1','vpm1')")"
KEPT_DOMAINS="$(db_count biz_domain "tenant_id = ${TENANT_ID}")"
KEPT_VENDORS="$(db_count sys_vendor "tenant_id = ${TENANT_ID}")"
[[ "${KEPT_USERS}" == "14" && "${KEPT_DOMAINS}" == "7" && "${KEPT_VENDORS}" == "3" ]] \
    && pass "定义数据保留：种子账号 14 / 域 7 / 厂商 3" \
    || fail "定义数据被误删：种子账号 ${KEPT_USERS} / 域 ${KEPT_DOMAINS} / 厂商 ${KEPT_VENDORS}" \
            "DELETE_ORDER 只能包含业务实例表，账号/域/厂商属定义数据"

# 协作域定义数据（V9 ⑤ 种子）：重置不重建，删掉演示页就是空白
KEPT_POLICIES="$(db_count branch_policy "tenant_id = ${TENANT_ID}")"
[[ "${KEPT_POLICIES:-0}" -ge 1 ]] \
    && pass "定义数据保留：分支策略 ${KEPT_POLICIES} 条（V9 ⑤ 种子）" \
    || fail "分支策略被重置清空（${KEPT_POLICIES:-0} 条）" "branch_policy 是定义数据：从 DELETE_ORDER 移除，不能删了又不重放"

# r15 漏删的 4 张表：库内现实复核（counts 是服务端自报，这里直接问数据库）
# r16：report_definition 是「清空后回填」，所以行数从 0 变 2；数量与名称集合都要对——只对数量
# 会放过「回填成别的表」，只对名称会放过「多回填一张」。名称走 IN 命中数，避开中文 collation。
DB_REPORTS="$(db_count report_definition "tenant_id = ${TENANT_ID}")"
DB_REPORT_NAMED="$(db_count report_definition "tenant_id = ${TENANT_ID} AND name IN (${DEMO_REPORT_NAMES_SQL})")"
DB_PORTAL_CARDS="$(db_count portal_card_config "tenant_id = ${TENANT_ID}")"
DB_CLIENTS="$(db_count api_client "tenant_id = ${TENANT_ID}")"
DB_EXECUTORS="$(db_count test_executor "tenant_id = ${TENANT_ID}")"
[[ "${DB_REPORTS}" == "2" && "${DB_REPORT_NAMED}" == "2" ]] \
    && pass "库内 report_definition = 2 行，且恰为两张演示报表（名称命中 2/2）" \
    || fail "库内 report_definition ${DB_REPORTS} 行 / 名称命中 ${DB_REPORT_NAMED} 行，期望 2 行且命中 2" \
            "报表定义仍属实例数据（DELETE_ORDER 整表清空），但清完必须回填 DemoFixture.REPORTS 的两张表"
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

# login_or_stop <user> <password> <label>：登录失败先分类——网关不可达(000/502/503)是传输层，
# 必须当场停并给出重跑命令，而不是记一条「登录失败」再继续跑到下一个接口才停。
login_or_stop() {
    local user="$1" pwd="$2" label="$3"
    platform_login "${TENANT_CODE}" "${user}" "${pwd}" && return 0
    if [[ "$(failure_case "${HTTP_CODE:-000}" "${HTTP_BODY:-}")" == "transport" ]]; then
        fail_stop "${label} 登录（网关不可达）" transport "${HTTP_CODE:-000}" "${HTTP_BODY:-}" \
            "登录就 000 说明网关或 req-service 未起：先 make platform-up 再重跑"
    fi
    fail "${label} 登录失败（HTTP ${HTTP_CODE:-?}）" "账号由 platform-seed 创建；重置不删账号"
    return 1
}

section 3
# 演示页的数据面：重置脚本自称「可直接演示」，这两个页面必须真的有数据
login_or_stop admin 'Admin@12345' admin || true
api_call GET "/api/req/code/policies"
POLICY_ROWS="$(printf '%s' "${HTTP_BODY}" | jq 'length' 2>/dev/null || echo 0)"
[[ "${HTTP_CODE}" == "200" && "${POLICY_ROWS:-0}" -ge 1 ]] \
    && pass "分支策略页接口非空（${POLICY_ROWS} 条）" \
    || fail_http "分支策略页接口为空（${POLICY_ROWS:-0} 条）" "重置后 /code-policies 必须是可演示状态"
api_call GET "/api/deliver/test-executors"
EXEC_ROWS="$(jget "${HTTP_BODY}" '.total' 2>/dev/null || echo 0)"
[[ "${HTTP_CODE}" == "200" && "${EXEC_ROWS:-0}" == "2" ]] \
    && pass "执行器页接口恰 2 条（${EXEC_ROWS} 条）" \
    || fail_http "执行器页接口（${EXEC_ROWS:-0} 条）" "重置后 /test-executors 必须恰为保留的两条执行器"

# r15：报表列表与门户配置直读 DB（不吃 deliver 的 AnalyticsCache），所以适合做 API 级复核
# r16：列表不再是空态——重置回填 2 张演示报表，计数与名称集合都要对（AC-1/AC-2）；名称集合
# 用同一 `jq sort | join` 规范化，与脚本侧期望集比较，不依赖任何一方返回的原始顺序。
api_call GET "/api/deliver/reports?page=1&size=20"
REPORT_TOTAL="$(jget "${HTTP_BODY}" '.total' 2>/dev/null || echo '')"
REPORT_NAMES="$(printf '%s' "${HTTP_BODY}" | jq -r '[.items[].name] | sort | join(",")' 2>/dev/null || echo '')"
[[ "${HTTP_CODE}" == "200" && "${REPORT_TOTAL:-x}" == "2" && "${REPORT_NAMES}" == "${DEMO_REPORT_NAMES_EXPECTED}" ]] \
    && pass "报表列表 total = 2，名称集合 = 两张演示报表（${REPORT_NAMES}）" \
    || fail_http "报表列表 total = ${REPORT_TOTAL:-<缺失>}，名称集合 '${REPORT_NAMES:-<空>}'，期望 2 / '${DEMO_REPORT_NAMES_EXPECTED}'" \
            "回填的名称必须与 DemoFixture.REPORT_NAMES 逐字相同，且不得多回填第三张"

# AC-3/AC-4：两张表开箱就能出数——run 走 codec.read(definition_json) + 缓存 + 聚合，形状写错
# 会 400/500；这里只判「非空 + 首度量正值」，中文表头与 colCodes/rowCodes 同长同序由 QA 探针补。
report_id_by_name() {
    printf '%s' "${HTTP_BODY}" | jq -r --arg n "$1" '[.items[] | select(.name == $n) | .id][0] // empty' 2>/dev/null || true
}
run_report() { # run_report <id>：body 省略 → 用库内 definition（ReportService.run:322-324）
    api_call POST "/api/deliver/reports/$1/run?preset=30d&reqType=RD"
}
REPORT_A_ID="$(report_id_by_name "${DEMO_REPORTS[0]}")"
REPORT_B_ID="$(report_id_by_name "${DEMO_REPORTS[1]}")"

if [[ -n "${REPORT_A_ID}" ]]; then
    run_report "${REPORT_A_ID}"
    A_ROWS="$(jget "${HTTP_BODY}" '.rowCodes | length')"
    A_COLS="$(jget "${HTTP_BODY}" '.colHeaders | length')"
    A_FIRST="$(jget "${HTTP_BODY}" '.total.values[0]')"
    A_OK="$(printf '%s' "${HTTP_BODY}" | jq -r '((.rowCodes | length) >= 1) and ((.colHeaders | length) >= 2) and ((.total.values[0] // 0) > 0)' 2>/dev/null || echo false)"
    [[ "${HTTP_CODE}" == "200" && "${A_OK}" == "true" ]] \
        && pass "报表 A（${DEMO_REPORTS[0]}）run 出数：rowCodes ${A_ROWS} 行 / colHeaders ${A_COLS} 列 / 首度量合计 ${A_FIRST}" \
        || fail_http "报表 A（${DEMO_REPORTS[0]}）run 异常：rowCodes ${A_ROWS:-?} / colHeaders ${A_COLS:-?} / total.values[0] ${A_FIRST:-?}" \
                "definition_json 必须能被 ReportDefinitionCodec.read 反序列化；rows=[domain]/columns=[status] 在演示需求集上应各有值"
else
    fail "报表 A（${DEMO_REPORTS[0]}）不在列表里，无法 run" "先修回填：名称与 DemoFixture.REPORT_NAMES 必须逐字相同"
fi

if [[ -n "${REPORT_B_ID}" ]]; then
    run_report "${REPORT_B_ID}"
    B_ROWS="$(jget "${HTTP_BODY}" '.rowCodes | length')"
    B_EST="$(jget "${HTTP_BODY}" '.total.values[1]')"
    # 厂商维按 requirement.assignee_vendor_id 分组、标签取 sys_vendor.name（甲方科技/丙方信息），
    # 所以断言「≥2 行且第二度量有数」而不是拿 code 当行键（AnalyticsSql.java:172、RequirementService.java:392-393）。
    B_NAMES="$(printf '%s' "${HTTP_BODY}" | jq -r '[(.rowHeaders // []) | .[]] | join("/")' 2>/dev/null || echo '')"
    B_OK="$(printf '%s' "${HTTP_BODY}" | jq -r '((.rowCodes | length) >= 2) and ((.total.values[1] // 0) > 0)' 2>/dev/null || echo false)"
    [[ "${HTTP_CODE}" == "200" && "${B_OK}" == "true" ]] \
        && pass "报表 B（${DEMO_REPORTS[1]}）run 出数：厂商 ${B_ROWS} 行（${B_NAMES}），预估人天合计 ${B_EST}" \
        || fail_http "报表 B（${DEMO_REPORTS[1]}）run 异常：厂商行 ${B_ROWS:-?}（${B_NAMES:-?}）/ total.values[1] ${B_EST:-?}" \
                "厂商行只有 1 行且名为「未设置」= 演示需求没写 assignee_vendor_id（vendor 维读该列，见 AnalyticsSql.java:172）；度量只用 requirement 已注册的人天口径"
else
    fail "报表 B（${DEMO_REPORTS[1]}）不在列表里，无法 run" "先修回填：名称与 DemoFixture.REPORT_NAMES 必须逐字相同"
fi

# AC-5：导出的 xlsx 是「自带出处的交付物」——必须 200、PK 头合法、表内带水印（WatermarkSheet:44）。
# pipefail 下 `grep -q` 会因 SIGPIPE 把 141 当失败，所以用 `grep -F -c` 读完整个流再比数。
TMPD="$(mktemp -d -t demo-report-export.XXXXXX)"
trap 'rm -rf "${TMPD:-}"' EXIT
EXPORT_OK=0
EXPORT_BAD=""
EXPORT_DETAIL=""
for spec in "A:${REPORT_A_ID}" "B:${REPORT_B_ID}"; do
    tag="${spec%%:*}" rid="${spec#*:}"
    [[ -n "${rid}" ]] || { EXPORT_BAD+="${tag}=无 id; "; continue; }
    xfile="${TMPD}/report-${rid}.xlsx"
    xcode="$(curl -sS -o "${xfile}" -w '%{http_code}' --max-time 30 \
        "${GATEWAY_URL}/api/deliver/reports/${rid}/export?format=xlsx&preset=30d&reqType=RD" \
        -H "Authorization: Bearer ${API_TOKEN}" -H "X-Tenant-Id: ${API_TENANT}" || true)"
    xmagic="$(head -c 2 "${xfile}" 2>/dev/null || true)"
    xmarks="$(unzip -p "${xfile}" xl/sharedStrings.xml 2>/dev/null | grep -F -c '内部资料（水印）' || true)"
    xsize="$(wc -c <"${xfile}" 2>/dev/null | tr -d ' ' || true)"
    EXPORT_DETAIL+="${tag} ${xsize:-?} 字节/水印 ${xmarks:-0}; "
    if [[ "${xcode}" == "200" && "${xmagic}" == "PK" && "${xmarks:-0}" -ge 1 ]]; then
        EXPORT_OK=$((EXPORT_OK + 1))
    else
        EXPORT_BAD+="${tag}(HTTP ${xcode:-?} / 头 ${xmagic:-?} / 水印 ${xmarks:-0}); "
    fi
done
[[ "${EXPORT_OK}" == "2" ]] \
    && pass "两张演示报表 export xlsx 均 200 + PK 头 + 水印（${EXPORT_DETAIL% }）" \
    || fail "导出未全通过（${EXPORT_OK}/2）：${EXPORT_BAD}实际 ${EXPORT_DETAIL% }" \
            "导出走 WatermarkSheet.xlsx：水印写在首末行的共享字符串里，缺了说明导出绕过了统一写出器"

api_call GET "/api/deliver/portal/config"
PORTAL_CARDS="$(jget "${HTTP_BODY}" '.cards | length' 2>/dev/null || echo '')"
PORTAL_REPORT_KEYS="$(jget "${HTTP_BODY}" '[.cardKeys[] | select(startswith("report:"))] | length' 2>/dev/null || echo '')"
[[ "${HTTP_CODE}" == "200" && "${PORTAL_CARDS:-x}" == "4" ]] \
    && pass "门户配置 cards = 4（admin 角色模板）" \
    || fail_http "门户配置 cards = ${PORTAL_CARDS:-<缺失>}，期望 4" \
            "portal_card_config 清空后必须回退到角色模板：admin 模板固定 4 张卡"
[[ "${HTTP_CODE}" == "200" && "${PORTAL_REPORT_KEYS:-x}" == "0" ]] \
    && pass "门户配置无 report: 前缀卡片（个人 pin 证据在 portalPins=0）" \
    || fail_http "门户配置仍有 ${PORTAL_REPORT_KEYS:-?} 个 report: 卡片" \
            "config 只由角色模板构造，出现 report: 说明个人 pin 行没被清掉"

# r15-fix（QA D-02）：这两页直读 DB、不吃缓存，适合做 API 级复核——
# 重置后流程定义页与用例模块树不得再出现上一轮门禁的 RUN_TAG 痕迹。
api_call GET "/api/req/admin/workflows"
WF_TOTAL="$(printf '%s' "${HTTP_BODY}" | jq 'length' 2>/dev/null || echo '')"
WF_RESIDUE="$(printf '%s' "${HTTP_BODY}" | jq '[.[] | select(.name | test("^(verify-|neg-)"))] | length' 2>/dev/null || echo '')"
[[ "${HTTP_CODE}" == "200" && "${WF_RESIDUE:-x}" == "0" ]] \
    && pass "流程定义接口无门禁残留（共 ${WF_TOTAL} 条）" \
    || fail_http "流程定义接口仍有 ${WF_RESIDUE:-?} 条 verify-/neg- 残留（共 ${WF_TOTAL:-?} 条）" \
            "wf_definition 的种子行要留、RUN_TAG 探针行要删：见 DemoFixture.GATE_RUN_TAG_RESIDUE"
api_call GET "/api/deliver/test-modules"
MOD_TOTAL="$(printf '%s' "${HTTP_BODY}" | jq 'length' 2>/dev/null || echo '')"
MOD_RESIDUE="$(printf '%s' "${HTTP_BODY}" | jq '[.[] | select(.name | test("^(验证模块|neg-)"))] | length' 2>/dev/null || echo '')"
[[ "${HTTP_CODE}" == "200" && "${MOD_RESIDUE:-x}" == "0" ]] \
    && pass "用例模块树无门禁残留（共 ${MOD_TOTAL} 条）" \
    || fail_http "用例模块树仍有 ${MOD_RESIDUE:-?} 条 验证模块*/neg-* 残留（共 ${MOD_TOTAL:-?} 条）" \
            "test_module 的种子模块树要留、门禁探针模块要删"

section 4
# ---------------------------------------------------------------- 真实登录复核（端到端）

check_visible() { # check_visible <user> <expected>
    local user="$1" expected="$2" total
    login_or_stop "${user}" 'Passw0rd!' "${user}" || return 0
    api_call GET "/api/req/requirements?page=1&size=1"
    [[ "${HTTP_CODE}" == "200" ]] || {
        fail_http "${user} 查需求列表" "检查网关与 token/tenant 头；000/502/503 属传输层，须先修栈而非改断言"
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
# r24：vpm1 与 vendor1 同厂商不同角色 —— 隔离按厂商，所以需求可见数与 vendor1 相同（3）。
# 这条登录本身也是断言：VENDOR_PM 必须能进系统，否则授权表再对也没人能用。
check_visible vpm1 3
API_TOKEN=""
API_TENANT=""

section 5
# ---------------------------------------------------------------- r18 三层授权回到预设（AC-4）

# 重置不只是「业务数据回演示态」：被手改过的授权格也必须回到预设。否则演示时一个上一轮
# 误设的 DENY 会长期沉默地挡人——它在演示里表现为「按钮不见了」，而不是一条报错。
check_count_any() {
    local k1="$1" k2="$2" want="$3" label="$4" got
    got="$(printf '%s' "${C1}" | jq -r ".${k1} // .${k2} // empty")"
    [[ "${got}" == "${want}" ]] \
        && pass "${label}" \
        || fail "${label}（期望 ${want}，实际 ${got:-<缺 ${k1}/${k2} 键>}）" \
            "重置计数由 DemoResetService 返回，缺键说明 r18 段还没接进 reset 响应"
}
check_count_any rolePermission role_permission 1848 "授权行 role_permission = 1848（11 角色 × 168）"
check_count_any customRoles custom_roles 0 "自定义角色 = 0（重置只留内置 11 个）"

RBAC_TENANT_ROWS="$(db_count sys_role_permission "tenant_id = ${TENANT_ID}")"
[[ "${RBAC_TENANT_ROWS}" == "1848" ]] \
    && pass "库内授权行 ${RBAC_TENANT_ROWS} 行（服务端计数之外的独立复核）" \
    || fail "库内授权行 ${RBAC_TENANT_ROWS:-<空>} 行，期望 1848" \
            "计数表自报与服务端同源；这条独立查库，两处都说 1848 才算数"

# r24：第 11 个内置角色在演示账号上必须有落脚点 —— 角色与厂商必须成对出现，
# 少一个就是「列表看得见、写什么都 403」的空壳账号（PRD AC-12）。
VPM_ROLE_VENDOR="$(db_q "SELECT CONCAT(r.code, '/', COALESCE(v.code, 'NULL'))
        FROM sys_user u
        JOIN sys_user_role ur ON ur.user_id = u.id AND ur.tenant_id = u.tenant_id
        JOIN sys_role r ON r.id = ur.role_id AND r.tenant_id = ur.tenant_id
        LEFT JOIN sys_vendor v ON v.id = u.vendor_id AND v.tenant_id = u.tenant_id
       WHERE u.tenant_id = ${TENANT_ID} AND u.username = 'vpm1'")"
[[ "${VPM_ROLE_VENDOR}" == "VENDOR_PM/VEN-A" ]] \
    && pass "vpm1 = VENDOR_PM + VEN-A（演示入口的角色与厂商都绑上了）" \
    || fail "vpm1 角色/厂商为 ${VPM_ROLE_VENDOR:-<空>}，期望 VENDOR_PM/VEN-A" \
            "SeedService 的 userDefs 必须给 vpm1 同时写角色码与厂商码"

VPM_ROWS="$(db_count sys_role_permission "tenant_id = ${TENANT_ID} AND role_id = (SELECT id FROM sys_role WHERE tenant_id = ${TENANT_ID} AND code = 'VENDOR_PM')")"
[[ "${VPM_ROWS}" == "168" ]] \
    && pass "VENDOR_PM 授权 ${VPM_ROWS} 行（11 个角色各 168 格）" \
    || fail "VENDOR_PM 授权 ${VPM_ROWS:-<空>} 行，期望 168" \
            "RbacSeeder 必须给第 11 个内置角色落满 168 格，V13 只保证角色存在"

BAD_ROLE_ROWS="$(db_q "SELECT COUNT(*) FROM (
        SELECT r.id FROM sys_role r
         WHERE r.tenant_id = ${TENANT_ID} AND r.builtin = 1
           AND (SELECT COUNT(*) FROM sys_role_permission p
                 WHERE p.tenant_id = r.tenant_id AND p.role_id = r.id) <> 168) x")"
[[ "${BAD_ROLE_ROWS}" == "0" ]] \
    && pass "11 个内置角色每个恰好 168 行授权" \
    || fail "有 ${BAD_ROLE_ROWS:-?} 个内置角色的授权行数不等于 168" \
            "1848 = 11 × 168：缺格说明预设没覆盖到新角色，多格说明有重复 upsert"

roleperm_audit_view() {
    db_q "SELECT p.access
            FROM sys_role_permission p
            JOIN sys_role r ON r.id = p.role_id AND r.tenant_id = p.tenant_id
           WHERE p.tenant_id = ${TENANT_ID} AND r.code = 'PLATFORM_ADMIN'
             AND p.subject_type = 'FUNCTION' AND p.subject_code = 'audit:view'" | head -n1
}
PRESET_ACCESS="$(roleperm_audit_view)"
if [[ -z "${PRESET_ACCESS}" ]]; then
    fail "PLATFORM_ADMIN 的 audit:view 授权行存在" \
        "V12 + RbacSeeder 未落地：没有授权行可回，重置就只是清业务数据"
else
    # 污染一格（预设置信：矩阵里 PLATFORM_ADMIN 对 audit:view 是 ALLOW），重置后必须回 ALLOW
    db_q "UPDATE sys_role_permission p
             JOIN sys_role r ON r.id = p.role_id AND r.tenant_id = p.tenant_id
             SET p.access = 'DENY'
           WHERE p.tenant_id = ${TENANT_ID} AND r.code = 'PLATFORM_ADMIN'
             AND p.subject_type = 'FUNCTION' AND p.subject_code = 'audit:view'" >/dev/null
    DIRTIED="$(roleperm_audit_view)"
    reset_once
    RESTORED="$(roleperm_audit_view)"
    [[ "${DIRTIED}" == "DENY" && "${RESTORED}" == "${PRESET_ACCESS}" ]] \
        && pass "被改过的授权格回到预设（audit:view ${DIRTIED} → ${RESTORED}）" \
        || fail "授权格未回预设：污染后 ${DIRTIED:-<空>}，重置后 ${RESTORED:-<空>}，预设 ${PRESET_ACCESS}" \
                "重置必须用 RbacSeeder 的推导覆盖内置角色的 1848 行（upsert），不能只删自定义角色的行"
fi

CUSTOM_ROLES_DB="$(db_count sys_role "tenant_id = ${TENANT_ID} AND builtin = 0")"
[[ "${CUSTOM_ROLES_DB}" == "0" ]] \
    && pass "库内自定义角色 0 行（重置删除自定义角色连带其授权）" \
    || fail "库内仍有 ${CUSTOM_ROLES_DB:-?} 个自定义角色" \
            "删角色不删授权行会留下孤儿行，之后同 code 重建角色会「带上」上一轮的权限"

# ---------------------------------------------------------------- 结果

if (( CASE != TOTAL_CHECKS )); then
    err "断言数漂移: 实际执行 ${CASE} 项，声明 ${TOTAL_CHECKS} 项——增删断言时必须同步 TOTAL_CHECKS，否则 57/57 口径失效"
    exit 1
fi
if (( FAIL > 0 )); then
    err "platform-demo-reset: ${CASE} 项中 ${FAIL} 项失败（transport ${FAIL_TRANSPORT} / api ${FAIL_API} / assert ${FAIL_ASSERT}）"
    err "失败分类：transport = 栈或网络层（先 make platform-up 后重跑）；api = 接口语义；assert = 数据未回演示态"
    err "提示：assert 类若只有个别计数偏移（如需求 16 条、test_strategy 1 行），先确认没有第二个写者"
    err "      在同一条演示栈上并发（另一次 reset/seed/门禁探针）——重置本身幂等，独占重跑即应回到 57/57"
    err "重跑: bash scripts/platform-demo-reset.sh"
    exit 1
fi
ok "platform-demo-reset: ${CASE}/${CASE} 全部通过（演示数据已重建，可直接演示；5/5 段）"
